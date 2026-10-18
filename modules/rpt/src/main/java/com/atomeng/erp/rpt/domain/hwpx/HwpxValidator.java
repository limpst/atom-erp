package com.atomeng.erp.rpt.domain.hwpx;

import java.io.ByteArrayInputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * HWPX 검증기 — 한컴 소프트웨어 없이 압축을 풀어 구조 · 스키마 · 참조 무결성을 본다 (06-IMPL Step 8 HwpxPackageTest).
 * <ol>
 *   <li>첫 엔트리 mimetype · STORED · application/hwp+zip, 필수 엔트리 존재</li>
 *   <li>모든 XML 정형성(DTD 금지 파서)</li>
 *   <li>container.xml rootfile → Contents/content.hpf, manifest item href 존재 · spine idref · BinData 등록 여부</li>
 *   <li>section*.xml 이 부분 XSD(owpml-section-subset.xsd)에 맞는지</li>
 *   <li>charPrIDRef · paraPrIDRef · styleIDRef · borderFillIDRef 가 header.xml 정의를 가리키는지, binaryItemIDRef ↔ manifest</li>
 *   <li>표: rowCnt = tr 수, 행마다 tc = colCnt, cellAddr 행 · 열 번호 일치 / 문단 id 중복 금지 / 남은 {{placeholder}} 금지</li>
 * </ol>
 */
public final class HwpxValidator {

    public static final List<String> REQUIRED = List.of("mimetype", "version.xml", "META-INF/container.xml",
            HwpxPackage.CONTENT_HPF, HwpxPackage.HEADER, HwpxPackage.SECTION0);

    private static volatile Schema sectionSchema;

    public record Report(List<String> errors, int paragraphs, int tables, int pictures, int binItems) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private HwpxValidator() {}

    public static Report validate(byte[] zip) {
        List<String> errors = new ArrayList<>();
        List<HwpxPackage.EntryInfo> infos;
        HwpxPackage pkg;
        try {
            infos = HwpxPackage.entryInfos(zip);
            pkg = HwpxPackage.read(zip);
        } catch (RuntimeException e) {
            return new Report(List.of("ZIP_UNREADABLE: " + e.getMessage()), 0, 0, 0, 0);
        }
        if (infos.isEmpty() || !infos.get(0).name().equals("mimetype")) {
            errors.add("MIMETYPE_NOT_FIRST");
        } else if (infos.get(0).method() != ZipEntry.STORED) {
            errors.add("MIMETYPE_COMPRESSED");
        }
        if (pkg.get("mimetype") != null
                && !new String(pkg.get("mimetype"), StandardCharsets.US_ASCII).equals(HwpxPackage.MIMETYPE)) {
            errors.add("MIMETYPE_VALUE");
        }
        for (String r : REQUIRED) {
            if (pkg.get(r) == null) {
                errors.add("MISSING_ENTRY: " + r);
            }
        }
        if (!errors.isEmpty()) {
            return new Report(errors, 0, 0, 0, 0);
        }
        Map<String, Document> xml = new HashMap<>();
        for (var e : pkg.entries().entrySet()) {
            if (e.getKey().endsWith(".xml") || e.getKey().endsWith(".hpf")) {
                try {
                    xml.put(e.getKey(), Owpml.parse(e.getValue(), e.getKey()));
                } catch (RuntimeException ex) {
                    errors.add("XML_MALFORMED: " + e.getKey());
                }
            }
        }
        if (!errors.isEmpty()) {
            return new Report(errors, 0, 0, 0, 0);
        }
        // container → content.hpf
        List<Element> rootfiles = Owpml.all(xml.get("META-INF/container.xml"), Owpml.OCF, "rootfile");
        if (rootfiles.stream().noneMatch(r -> HwpxPackage.CONTENT_HPF.equals(r.getAttribute("full-path")))) {
            errors.add("CONTAINER_ROOTFILE");
        }
        // manifest · spine · BinData
        Document hpf = xml.get(HwpxPackage.CONTENT_HPF);
        Map<String, String> items = new HashMap<>();
        for (Element item : Owpml.all(hpf, Owpml.OPF, "item")) {
            String href = item.getAttribute("href");
            items.put(item.getAttribute("id"), href);
            if (pkg.get(href) == null) {
                errors.add("MANIFEST_HREF_MISSING: " + href);
            }
        }
        for (Element ref : Owpml.all(hpf, Owpml.OPF, "itemref")) {
            if (!items.containsKey(ref.getAttribute("idref"))) {
                errors.add("SPINE_IDREF: " + ref.getAttribute("idref"));
            }
        }
        Set<String> hrefs = new HashSet<>(items.values());
        int binItems = 0;
        for (String name : pkg.entries().keySet()) {
            if (name.startsWith("BinData/")) {
                binItems++;
                if (!hrefs.contains(name)) {
                    errors.add("BINDATA_UNREGISTERED: " + name);
                }
            }
            if (name.matches("Contents/section\\d+\\.xml") && !hrefs.contains(name)) {
                errors.add("SECTION_UNREGISTERED: " + name);
            }
        }
        // header 정의 id
        Document head = xml.get(HwpxPackage.HEADER);
        Set<String> charPr = ids(head, "charPr");
        Set<String> paraPr = ids(head, "paraPr");
        Set<String> style = ids(head, "style");
        Set<String> borderFill = ids(head, "borderFill");
        int paragraphs = 0;
        int tables = 0;
        int pictures = 0;
        for (var e : xml.entrySet()) {
            if (!e.getKey().matches("Contents/section\\d+\\.xml")) {
                continue;
            }
            Document sec = e.getValue();
            errors.addAll(schemaErrors(pkg.get(e.getKey()), e.getKey()));
            Set<String> pids = new HashSet<>();
            for (Element p : Owpml.all(sec, Owpml.HP, "p")) {
                paragraphs++;
                if (!pids.add(p.getAttribute("id"))) {
                    errors.add("DUPLICATE_PARAGRAPH_ID: " + p.getAttribute("id"));
                }
                ref(errors, paraPr, p, "paraPrIDRef");
                ref(errors, style, p, "styleIDRef");
            }
            for (Element run : Owpml.all(sec, Owpml.HP, "run")) {
                ref(errors, charPr, run, "charPrIDRef");
            }
            for (Element t : Owpml.all(sec, Owpml.HP, "t")) {
                if (RunNormalizer.PLACEHOLDER.matcher(t.getTextContent()).find()) {
                    errors.add("UNRESOLVED_PLACEHOLDER: " + t.getTextContent());
                }
            }
            for (Element tbl : Owpml.all(sec, Owpml.HP, "tbl")) {
                tables++;
                ref(errors, borderFill, tbl, "borderFillIDRef");
                List<Element> rows = Owpml.children(tbl, Owpml.HP, "tr");
                int colCnt = intAttr(tbl, "colCnt");
                if (intAttr(tbl, "rowCnt") != rows.size()) {
                    errors.add("TABLE_ROWCNT: " + tbl.getAttribute("rowCnt") + " != " + rows.size());
                }
                for (int r = 0; r < rows.size(); r++) {
                    List<Element> cells = Owpml.children(rows.get(r), Owpml.HP, "tc");
                    int span = 0;
                    for (int c = 0; c < cells.size(); c++) {
                        Element tc = cells.get(c);
                        ref(errors, borderFill, tc, "borderFillIDRef");
                        List<Element> addr = Owpml.children(tc, Owpml.HP, "cellAddr");
                        List<Element> sp = Owpml.children(tc, Owpml.HP, "cellSpan");
                        span += sp.isEmpty() ? 1 : intAttr(sp.get(0), "colSpan");
                        if (addr.isEmpty() || intAttr(addr.get(0), "rowAddr") != r || intAttr(addr.get(0), "colAddr") != c) {
                            errors.add("CELL_ADDR: row " + r + " col " + c);
                        }
                    }
                    if (span != colCnt) {
                        errors.add("TABLE_COLCNT: row " + r + " spans " + span + " != " + colCnt);
                    }
                }
            }
            for (Element img : Owpml.all(sec, Owpml.HC, "img")) {
                pictures++;
                String id = img.getAttribute("binaryItemIDRef");
                if (!items.containsKey(id) || !items.get(id).startsWith("BinData/")) {
                    errors.add("BINARY_ITEM_REF: " + id);
                }
            }
        }
        return new Report(List.copyOf(errors), paragraphs, tables, pictures, binItems);
    }

    private static Set<String> ids(Document head, String local) {
        Set<String> s = new HashSet<>();
        Owpml.all(head, Owpml.HH, local).forEach(e -> s.add(e.getAttribute("id")));
        return s;
    }

    private static void ref(List<String> errors, Set<String> defined, Element e, String attr) {
        String v = e.getAttribute(attr);
        if (!v.isEmpty() && !defined.contains(v)) {
            errors.add("DANGLING_" + attr + ": " + v);
        }
    }

    private static int intAttr(Element e, String attr) {
        try {
            return Integer.parseInt(e.getAttribute(attr));
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    static List<String> schemaErrors(byte[] xml, String name) {
        List<String> errors = new ArrayList<>();
        try {
            Validator v = schema().newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(org.xml.sax.SAXParseException e) {
                    // 경고는 무시
                }

                @Override
                public void error(org.xml.sax.SAXParseException e) {
                    errors.add("SCHEMA(" + name + ":" + e.getLineNumber() + "): " + e.getMessage());
                }

                @Override
                public void fatalError(org.xml.sax.SAXParseException e) {
                    errors.add("SCHEMA_FATAL(" + name + "): " + e.getMessage());
                }
            });
            v.validate(new StreamSource(new ByteArrayInputStream(xml)));
        } catch (Exception e) {
            if (errors.isEmpty()) {
                errors.add("SCHEMA(" + name + "): " + e.getMessage());
            }
        }
        return errors;
    }

    private static Schema schema() throws Exception {
        Schema s = sectionSchema;
        if (s == null) {
            synchronized (HwpxValidator.class) {
                if (sectionSchema == null) {
                    URL url = HwpxValidator.class.getClassLoader().getResource("rpt/hwpx/xsd/owpml-section-subset.xsd");
                    SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                    f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "file,jar");
                    sectionSchema = f.newSchema(url);
                }
                s = sectionSchema;
            }
        }
        return s;
    }
}
