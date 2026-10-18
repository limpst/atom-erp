package com.atomeng.erp.rpt.domain.hwpx;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * run 분할 정규화 (설계 06 B7.3, 06-IMPL Step 8 · TC-RPT-005).
 * 한글은 {@code {{facility.name}}} 같은 placeholder 를 서식 경계마다 여러 hp:run/hp:t 로 쪼개 저장할 수 있다.
 * 같은 문단(hp:p) 안에서 placeholder 범위에 걸친 hp:t 들을 첫 hp:t 하나로 모으고(첫 run 의 charPrIDRef 유지),
 * 비게 된 run(다른 자식이 없는 경우)은 지운다. 표 셀 안 문단(subList)도 대상이다.
 */
public final class RunNormalizer {

    public static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{[#/]?[A-Za-z0-9_.:\\-\\s]+}}");

    private RunNormalizer() {}

    /** @return 병합한 placeholder 수 */
    public static int normalize(Document doc) {
        int merged = 0;
        for (Element p : Owpml.all(doc, Owpml.HP, "p")) {
            merged += normalizeParagraph(p);
        }
        return merged;
    }

    static int normalizeParagraph(Element p) {
        int merged = 0;
        while (true) {
            List<Element> ts = new ArrayList<>();
            for (Element run : Owpml.children(p, Owpml.HP, "run")) {
                ts.addAll(Owpml.children(run, Owpml.HP, "t"));
            }
            StringBuilder all = new StringBuilder();
            List<int[]> spans = new ArrayList<>();
            for (Element t : ts) {
                int s = all.length();
                all.append(t.getTextContent());
                spans.add(new int[] {s, all.length()});
            }
            Matcher m = PLACEHOLDER.matcher(all);
            boolean changed = false;
            while (m.find()) {
                int first = indexAt(spans, m.start());
                int last = indexAt(spans, m.end() - 1);
                if (first == last) {
                    continue;
                }
                // first: 앞부분 + placeholder 전체 / 중간: 비움 / last: placeholder 뒤 나머지
                String firstText = ts.get(first).getTextContent();
                int cut = m.start() - spans.get(first)[0];
                ts.get(first).setTextContent(firstText.substring(0, cut) + m.group());
                for (int i = first + 1; i < last; i++) {
                    ts.get(i).setTextContent("");
                }
                String lastText = ts.get(last).getTextContent();
                ts.get(last).setTextContent(lastText.substring(m.end() - spans.get(last)[0]));
                removeEmptyRuns(p);
                merged++;
                changed = true;
                break;   // 인덱스가 바뀌었으니 처음부터 다시
            }
            if (!changed) {
                return merged;
            }
        }
    }

    private static int indexAt(List<int[]> spans, int pos) {
        for (int i = 0; i < spans.size(); i++) {
            if (pos >= spans.get(i)[0] && pos < spans.get(i)[1]) {
                return i;
            }
        }
        return spans.size() - 1;
    }

    private static void removeEmptyRuns(Element p) {
        for (Element run : Owpml.children(p, Owpml.HP, "run")) {
            boolean onlyEmptyText = true;
            for (Node c = run.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c instanceof Element e) {
                    boolean emptyT = Owpml.HP.equals(e.getNamespaceURI()) && "t".equals(e.getLocalName())
                            && e.getTextContent().isEmpty();
                    if (!emptyT) {
                        onlyEmptyText = false;
                        break;
                    }
                }
            }
            if (onlyEmptyText) {
                p.removeChild(run);
            } else {
                for (Element t : Owpml.children(run, Owpml.HP, "t")) {
                    if (t.getTextContent().isEmpty() && Owpml.children(run, Owpml.HP, "t").size() > 1) {
                        run.removeChild(t);
                    }
                }
            }
        }
    }
}
