package com.atomeng.erp.rpt.domain.hwpx;

import com.atomeng.erp.rpt.domain.hwpx.RenderModel.Block;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel.Image;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel.Section;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * HWPX 템플릿 바인딩 (설계 06 B7.3, 06-IMPL Step 8). 템플릿 패키지 + {@link RenderModel} → 새 HWPX 패키지.
 * <ol>
 *   <li>run 분할 정규화({@link RunNormalizer})</li>
 *   <li>반복 표 행: {@code {{#each name}} … {{this.f}} … {{/each}}} 가 든 hp:tr 을 행 템플릿으로 복제, rowAddr · rowCnt 재계산</li>
 *   <li>단순 placeholder {@code {{path}}} 치환(본문 · content.hpf 제목 · 미리보기 텍스트)</li>
 *   <li>{@code {{blocks:sections}}} 문단 → 섹션 제목 · 블록(문단 · 표 · 그림) 문단들로 전개</li>
 *   <li>그림: BinData/imageN.png 추가 + content.hpf manifest item 등록 + hp:pic(hc:img binaryItemIDRef)</li>
 *   <li>문단 id 를 문서 순서로 다시 매김(중복 금지)</li>
 * </ol>
 * 06-IMPL 의 StAX 스트리밍 대신 DOM(이번 단계 문서 규모 기준) — 대용량(사진 3,000장)은 Step 9 렌더 워커에서 스트리밍으로.
 */
public final class HwpxBinder {

    static final Pattern VALUE = Pattern.compile("\\{\\{([A-Za-z0-9_.\\-]+)}}");
    static final Pattern EACH = Pattern.compile("\\{\\{#each ([A-Za-z0-9_\\-]+)}}");
    static final Pattern THIS = Pattern.compile("\\{\\{this\\.([A-Za-z0-9_\\-]+)}}");
    public static final String BLOCKS_PLACEHOLDER = "{{blocks:sections}}";

    /** 본문 폭 170mm(A4 − 좌우 20mm) = 48189 HWPUNIT (1/7200 inch) */
    static final int CONTENT_WIDTH = 48189;
    static final int ROW_HEIGHT = 1418;
    static final int IMAGE_WIDTH = mm(160);

    private int images;
    private int ids = 5000;

    public static int mm(double mm) {
        return (int) Math.round(mm * 7200 / 25.4);
    }

    public static HwpxPackage bind(HwpxPackage template, RenderModel model) {
        return new HwpxBinder().doBind(template, model);
    }

    private HwpxPackage doBind(HwpxPackage template, RenderModel model) {
        Document sec = Owpml.parse(template.get(HwpxPackage.SECTION0), HwpxPackage.SECTION0);
        RunNormalizer.normalize(sec);
        expandRepeats(sec, model.repeats());
        Map<String, Image> binItems = new LinkedHashMap<>();
        expandBlocks(sec, model, binItems);
        replaceValues(sec, model.values());
        renumberParagraphs(sec);

        Document hpf = Owpml.parse(template.get(HwpxPackage.CONTENT_HPF), HwpxPackage.CONTENT_HPF);
        for (Element meta : Owpml.all(hpf, Owpml.OPF, "title")) {
            meta.setTextContent(substitute(meta.getTextContent(), model.values()));
        }
        for (Element meta : Owpml.all(hpf, Owpml.OPF, "meta")) {
            meta.setTextContent(substitute(meta.getTextContent(), model.values()));
        }
        Element manifest = Owpml.all(hpf, Owpml.OPF, "manifest").get(0);
        for (var e : binItems.entrySet()) {
            Element item = hpf.createElementNS(Owpml.OPF, "opf:item");
            item.setAttribute("id", e.getKey());
            item.setAttribute("href", "BinData/" + e.getKey() + ".png");
            item.setAttribute("media-type", "image/png");
            item.setAttribute("isEmbeded", "1");
            manifest.appendChild(item);
        }

        LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
        for (var e : template.entries().entrySet()) {
            byte[] v = e.getValue();
            switch (e.getKey()) {
                case HwpxPackage.SECTION0 -> v = Owpml.serialize(sec);
                case HwpxPackage.CONTENT_HPF -> v = Owpml.serialize(hpf);
                case "Preview/PrvText.txt" -> v = substitute(new String(v, StandardCharsets.UTF_8), model.values())
                        .getBytes(StandardCharsets.UTF_8);
                default -> { }
            }
            out.put(e.getKey(), v);
        }
        binItems.forEach((id, img) -> out.put("BinData/" + id + ".png", img.png()));
        return new HwpxPackage(out);
    }

    // ------------------------------------------------------------------------------------------- 반복 표 행
    private void expandRepeats(Document sec, Map<String, List<Map<String, String>>> repeats) {
        for (Element tr : Owpml.all(sec, Owpml.HP, "tr")) {
            String text = tr.getTextContent();
            Matcher m = EACH.matcher(text);
            if (!m.find()) {
                continue;
            }
            String name = m.group(1);
            List<Map<String, String>> items = repeats.getOrDefault(name, List.of());
            Element tbl = (Element) tr.getParentNode();
            for (Map<String, String> item : items) {
                Element clone = (Element) tr.cloneNode(true);
                for (Element t : Owpml.all(clone, Owpml.HP, "t")) {
                    String s = t.getTextContent().replace("{{#each " + name + "}}", "").replace("{{/each}}", "");
                    Matcher tm = THIS.matcher(s);
                    StringBuilder sb = new StringBuilder();
                    while (tm.find()) {
                        tm.appendReplacement(sb, Matcher.quoteReplacement(item.getOrDefault(tm.group(1), "")));
                    }
                    tm.appendTail(sb);
                    t.setTextContent(sb.toString());
                }
                tbl.insertBefore(clone, tr);
            }
            tbl.removeChild(tr);
            fixTable(tbl);
        }
    }

    static void fixTable(Element tbl) {
        List<Element> rows = Owpml.children(tbl, Owpml.HP, "tr");
        for (int r = 0; r < rows.size(); r++) {
            List<Element> cells = Owpml.children(rows.get(r), Owpml.HP, "tc");
            for (int c = 0; c < cells.size(); c++) {
                Element addr = Owpml.children(cells.get(c), Owpml.HP, "cellAddr").get(0);
                addr.setAttribute("rowAddr", String.valueOf(r));
                addr.setAttribute("colAddr", String.valueOf(c));
            }
        }
        tbl.setAttribute("rowCnt", String.valueOf(Math.max(1, rows.size())));
        List<Element> sz = Owpml.children(tbl, Owpml.HP, "sz");
        if (!sz.isEmpty()) {
            sz.get(0).setAttribute("height", String.valueOf(rows.size() * ROW_HEIGHT));
        }
    }

    // ------------------------------------------------------------------------------------------- 값 치환
    private static void replaceValues(Document sec, Map<String, String> values) {
        for (Element t : Owpml.all(sec, Owpml.HP, "t")) {
            String s = t.getTextContent();
            if (s.contains("{{")) {
                t.setTextContent(substitute(s, values));
            }
        }
    }

    static String substitute(String s, Map<String, String> values) {
        Matcher m = VALUE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = values.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------------------------------- 블록 전개
    private void expandBlocks(Document sec, RenderModel model, Map<String, Image> binItems) {
        for (Element p : Owpml.all(sec, Owpml.HP, "p")) {
            if (!Owpml.paragraphText(p).strip().equals(BLOCKS_PLACEHOLDER)) {
                continue;
            }
            Node parent = p.getParentNode();
            boolean first = true;
            String pageBreak = p.getAttribute("pageBreak");
            for (Section s : model.sections()) {
                Element h = paragraph(sec, "0", "2", s.title());
                if (first) {
                    h.setAttribute("pageBreak", pageBreak.isEmpty() ? "0" : pageBreak);
                    first = false;
                }
                parent.insertBefore(h, p);
                for (Block b : s.blocks()) {
                    for (Element e : blockParagraphs(sec, b, model.images(), binItems)) {
                        parent.insertBefore(e, p);
                    }
                }
            }
            parent.removeChild(p);
        }
    }

    private List<Element> blockParagraphs(Document doc, Block b, Map<String, Image> imgs, Map<String, Image> binItems) {
        List<Element> out = new ArrayList<>();
        switch (b.kind()) {
            case "HEADING" -> out.add(paragraph(doc, "0", "2", b.text()));
            case "TABLE" -> {
                Element p = paragraph(doc, "0", "0", null);
                Element run = Owpml.children(p, Owpml.HP, "run").get(0);
                run.appendChild(table(doc, b.columns(), b.rows()));
                out.add(p);
            }
            case "IMAGE" -> {
                Image img = imgs.get(b.imageKey());
                if (img != null) {
                    String id = "image" + (++images);
                    binItems.put(id, img);
                    Element p = paragraph(doc, "1", "0", null);
                    Element run = Owpml.children(p, Owpml.HP, "run").get(0);
                    run.appendChild(picture(doc, id, img));
                    out.add(p);
                }
                out.add(paragraph(doc, "1", "0", b.text()));
            }
            default -> out.add(paragraph(doc, "0", "0", b.text()));
        }
        return out;
    }

    private Element paragraph(Document doc, String paraPr, String charPr, String text) {
        Element p = doc.createElementNS(Owpml.HP, "hp:p");
        p.setAttribute("id", String.valueOf(++ids));
        p.setAttribute("paraPrIDRef", paraPr);
        p.setAttribute("styleIDRef", "0");
        p.setAttribute("pageBreak", "0");
        p.setAttribute("columnBreak", "0");
        p.setAttribute("merged", "0");
        Element run = doc.createElementNS(Owpml.HP, "hp:run");
        run.setAttribute("charPrIDRef", charPr);
        p.appendChild(run);
        if (text != null) {
            Element t = doc.createElementNS(Owpml.HP, "hp:t");
            t.setTextContent(text);
            run.appendChild(t);
        }
        return p;
    }

    private Element table(Document doc, List<String> columns, List<List<String>> rows) {
        int cols = Math.max(1, columns.size());
        int cellW = CONTENT_WIDTH / cols;
        Element tbl = el(doc, "tbl", "id", String.valueOf(++ids), "zOrder", "0", "numberingType", "TABLE",
                "textWrap", "TOP_AND_BOTTOM", "textFlow", "BOTH_SIDES", "lock", "0", "dropcapstyle", "None",
                "pageBreak", "CELL", "repeatHeader", "1", "rowCnt", String.valueOf(rows.size() + 1),
                "colCnt", String.valueOf(cols), "cellSpacing", "0", "borderFillIDRef", "2", "noAdjust", "0");
        tbl.appendChild(el(doc, "sz", "width", String.valueOf(cellW * cols), "widthRelTo", "ABSOLUTE",
                "height", String.valueOf((rows.size() + 1) * ROW_HEIGHT), "heightRelTo", "ABSOLUTE", "protect", "0"));
        tbl.appendChild(position(doc));
        tbl.appendChild(margin(doc, "outMargin", "0", "0"));
        tbl.appendChild(margin(doc, "inMargin", "510", "141"));
        List<List<String>> all = new ArrayList<>();
        all.add(columns);
        all.addAll(rows);
        for (int r = 0; r < all.size(); r++) {
            Element tr = el(doc, "tr");
            for (int c = 0; c < cols; c++) {
                String v = c < all.get(r).size() && all.get(r).get(c) != null ? all.get(r).get(c) : "";
                Element tc = el(doc, "tc", "name", "", "header", r == 0 ? "1" : "0", "hasMargin", "0", "protect", "0",
                        "editable", "0", "dirty", "0", "borderFillIDRef", "2");
                Element sub = el(doc, "subList", "id", "", "textDirection", "HORIZONTAL", "lineWrap", "BREAK",
                        "vertAlign", "CENTER", "linkListIDRef", "0", "linkListNextIDRef", "0", "textWidth", "0",
                        "textHeight", "0", "hasTextRef", "0", "hasNumRef", "0");
                sub.appendChild(paragraph(doc, r == 0 ? "1" : "0", r == 0 ? "2" : "0", v));
                tc.appendChild(sub);
                tc.appendChild(el(doc, "cellAddr", "colAddr", String.valueOf(c), "rowAddr", String.valueOf(r)));
                tc.appendChild(el(doc, "cellSpan", "colSpan", "1", "rowSpan", "1"));
                tc.appendChild(el(doc, "cellSz", "width", String.valueOf(cellW), "height", String.valueOf(ROW_HEIGHT)));
                tc.appendChild(margin(doc, "cellMargin", "510", "141"));
                tr.appendChild(tc);
            }
            tbl.appendChild(tr);
        }
        return tbl;
    }

    private Element picture(Document doc, String binId, Image img) {
        int w = IMAGE_WIDTH;
        int h = (int) Math.round((double) w * img.heightPx() / Math.max(1, img.widthPx()));
        String sw = String.valueOf(w);
        String sh = String.valueOf(h);
        Element pic = el(doc, "pic", "id", String.valueOf(++ids), "zOrder", "0", "numberingType", "PICTURE",
                "textWrap", "TOP_AND_BOTTOM", "textFlow", "BOTH_SIDES", "lock", "0", "dropcapstyle", "None",
                "href", "", "groupLevel", "0", "instid", String.valueOf(ids), "reverse", "0");
        pic.appendChild(el(doc, "offset", "x", "0", "y", "0"));
        pic.appendChild(el(doc, "orgSz", "width", sw, "height", sh));
        pic.appendChild(el(doc, "curSz", "width", sw, "height", sh));
        pic.appendChild(el(doc, "flip", "horizontal", "0", "vertical", "0"));
        pic.appendChild(el(doc, "rotationInfo", "angle", "0", "centerX", String.valueOf(w / 2),
                "centerY", String.valueOf(h / 2), "rotateimage", "1"));
        Element ri = el(doc, "renderingInfo");
        ri.appendChild(hc(doc, "transMatrix", "e1", "1", "e2", "0", "e3", "0", "e4", "0", "e5", "1", "e6", "0"));
        ri.appendChild(hc(doc, "scaMatrix", "e1", "1", "e2", "0", "e3", "0", "e4", "0", "e5", "1", "e6", "0"));
        ri.appendChild(hc(doc, "rotMatrix", "e1", "1", "e2", "0", "e3", "0", "e4", "0", "e5", "1", "e6", "0"));
        pic.appendChild(ri);
        pic.appendChild(hc(doc, "img", "binaryItemIDRef", binId, "bright", "0", "contrast", "0", "effect", "REAL_PIC",
                "alpha", "0"));
        Element rect = el(doc, "imgRect");
        rect.appendChild(hc(doc, "pt0", "x", "0", "y", "0"));
        rect.appendChild(hc(doc, "pt1", "x", sw, "y", "0"));
        rect.appendChild(hc(doc, "pt2", "x", sw, "y", sh));
        rect.appendChild(hc(doc, "pt3", "x", "0", "y", sh));
        pic.appendChild(rect);
        pic.appendChild(el(doc, "imgClip", "left", "0", "right", String.valueOf(img.widthPx() * 75), "top", "0",
                "bottom", String.valueOf(img.heightPx() * 75)));
        pic.appendChild(margin(doc, "inMargin", "0", "0"));
        pic.appendChild(el(doc, "imgDim", "dimwidth", String.valueOf(img.widthPx() * 75),
                "dimheight", String.valueOf(img.heightPx() * 75)));
        pic.appendChild(el(doc, "effects"));
        pic.appendChild(el(doc, "sz", "width", sw, "widthRelTo", "ABSOLUTE", "height", sh, "heightRelTo", "ABSOLUTE",
                "protect", "0"));
        pic.appendChild(position(doc));
        pic.appendChild(margin(doc, "outMargin", "0", "0"));
        return pic;
    }

    private static Element position(Document doc) {
        return el(doc, "pos", "treatAsChar", "1", "affectLSpacing", "0", "flowWithText", "1", "allowOverlap", "0",
                "holdAnchorAndSO", "0", "vertRelTo", "PARA", "horzRelTo", "PARA", "vertAlign", "TOP",
                "horzAlign", "LEFT", "vertOffset", "0", "horzOffset", "0");
    }

    private static Element margin(Document doc, String name, String lr, String tb) {
        return el(doc, name, "left", lr, "right", lr, "top", tb, "bottom", tb);
    }

    private static Element el(Document doc, String local, String... attrs) {
        Element e = doc.createElementNS(Owpml.HP, "hp:" + local);
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            e.setAttribute(attrs[i], attrs[i + 1]);
        }
        return e;
    }

    private static Element hc(Document doc, String local, String... attrs) {
        Element e = doc.createElementNS(Owpml.HC, "hc:" + local);
        for (int i = 0; i + 1 < attrs.length; i += 2) {
            e.setAttribute(attrs[i], attrs[i + 1]);
        }
        return e;
    }

    private static void renumberParagraphs(Document sec) {
        int i = 0;
        for (Element p : Owpml.all(sec, Owpml.HP, "p")) {
            p.setAttribute("id", String.valueOf(++i));
        }
    }
}
