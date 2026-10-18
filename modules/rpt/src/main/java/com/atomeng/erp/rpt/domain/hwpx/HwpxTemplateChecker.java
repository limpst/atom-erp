package com.atomeng.erp.rpt.domain.hwpx;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

/**
 * 템플릿 검증 (06-IMPL Step 8 TemplateValidator, TC-RPT-005): run 분할 정규화 후
 * 필수 placeholder 존재 · 매니페스트에 없는 placeholder 금지 · {@code {{#each}}}/{@code {{/each}}} 같은 표 행 안 짝.
 */
public final class HwpxTemplateChecker {

    public record Result(List<String> errors, Set<String> placeholders, int normalizedRuns) {
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    private HwpxTemplateChecker() {}

    public static Result check(HwpxPackage template, JsonNode manifest) {
        List<String> errors = new ArrayList<>();
        Document sec = Owpml.parse(template.get(HwpxPackage.SECTION0), HwpxPackage.SECTION0);
        int merged = RunNormalizer.normalize(sec);
        Set<String> found = new TreeSet<>();
        for (Element t : Owpml.all(sec, Owpml.HP, "t")) {
            Matcher m = RunNormalizer.PLACEHOLDER.matcher(t.getTextContent());
            while (m.find()) {
                found.add(m.group());
            }
        }
        JsonNode ph = manifest.path("placeholders");
        Set<String> repeats = new TreeSet<>();
        manifest.path("repeats").properties().forEach(e -> repeats.add(e.getKey()));
        for (String p : found) {
            String inner = p.substring(2, p.length() - 2).strip();
            if (inner.startsWith("#each ")) {
                if (!repeats.contains(inner.substring(6).strip())) {
                    errors.add("UNKNOWN_REPEAT: " + p);
                }
            } else if (inner.equals("/each") || inner.startsWith("this.") || p.equals(HwpxBinder.BLOCKS_PLACEHOLDER)) {
                continue;
            } else if (!ph.has(inner)) {
                errors.add("UNKNOWN_PLACEHOLDER: " + p);
            }
        }
        ph.properties().forEach(e -> {
            if (e.getValue().path("required").asBoolean(false) && !found.contains("{{" + e.getKey() + "}}")) {
                errors.add("REQUIRED_PLACEHOLDER_MISSING: " + e.getKey());
            }
        });
        for (Element tr : Owpml.all(sec, Owpml.HP, "tr")) {
            String text = tr.getTextContent();
            boolean open = HwpxBinder.EACH.matcher(text).find();
            boolean close = text.contains("{{/each}}");
            if (open != close) {
                errors.add("UNPAIRED_EACH: " + text.strip());
            }
        }
        for (Element p : Owpml.all(sec, Owpml.HP, "p")) {
            String text = Owpml.paragraphText(p);
            if (text.contains("{{#each") && !isInRow(p)) {
                errors.add("EACH_OUTSIDE_TABLE_ROW: " + text.strip());
            }
        }
        return new Result(List.copyOf(errors), found, merged);
    }

    private static boolean isInRow(Element p) {
        for (var n = p.getParentNode(); n != null; n = n.getParentNode()) {
            if (n instanceof Element e && Owpml.HP.equals(e.getNamespaceURI()) && "tr".equals(e.getLocalName())) {
                return true;
            }
        }
        return false;
    }
}
