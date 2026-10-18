package com.atomeng.erp.qar.domain.ai;

import com.atomeng.erp.qar.domain.anchor.TextNorm;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 지적 근거 인용 검증 (설계 B7.4 · 07-IMPL Step 9, 환각 방지). 모든 AI 지적은 보고서 위치를 인용해야 하며:
 * <ul>
 *   <li>인용 없음 → NO_CITATION, 보고서 인용 없음 → NO_REPORT_CITATION</li>
 *   <li>report 인용의 blockId 가 리비전에 없음 → UNKNOWN_BLOCK, anchors.json 이 있는데 그 블록 앵커가 없음 → UNKNOWN_ANCHOR</li>
 *   <li>인용문(quote)이 블록 원문(정규화)에 없음 → QUOTE_NOT_FOUND</li>
 *   <li>data 인용 ref 를 facts 에서 못 찾거나 값이 다름 → DATA_MISMATCH</li>
 *   <li>NUM_MISMATCH 인데 report + data 인용이 함께 없음 → INSUFFICIENT_EVIDENCE</li>
 * </ul>
 * 거부된 지적은 guard_passed=false 로 저장만 하고(모델 평가용) 검토자에게 보이지 않는다(TC-QAR-005).
 */
public final class CitationGuard {

    public static final Set<String> CATEGORIES = Set.of("NUM_MISMATCH", "MISSING_EVIDENCE", "LOGIC_GAP", "RISKY_PHRASE",
            "MISSING_SECTION");

    public record Citation(String kind, UUID blockId, String quote, String ref, Object value) {}

    public record Draft(String category, String severity, String statement, List<Citation> citations) {}

    public record Verdict(boolean passed, String reason) {
        static Verdict reject(String reason) {
            return new Verdict(false, reason);
        }

        static final Verdict ACCEPT = new Verdict(true, null);
    }

    /**
     * @param blockTexts      리비전 블록 id → 원문
     * @param anchorBlockIds  anchors.json 블록 id 집합(렌더 전이면 null → 블록 존재만 확인)
     * @param facts           AI 요청에 실어 보낸 근거 데이터(JSON)
     */
    public record Context(Map<UUID, String> blockTexts, Set<UUID> anchorBlockIds, JsonNode facts) {}

    private CitationGuard() {}

    public static Verdict guard(Draft f, Context ctx) {
        if (f.category() == null || !CATEGORIES.contains(f.category())) {
            return Verdict.reject("INVALID_CATEGORY");
        }
        if (f.citations() == null || f.citations().isEmpty()) {
            return Verdict.reject("NO_CITATION");
        }
        boolean hasReport = false;
        boolean hasData = false;
        for (Citation c : f.citations()) {
            if ("report".equalsIgnoreCase(c.kind())) {
                if (c.blockId() == null || !ctx.blockTexts().containsKey(c.blockId())) {
                    return Verdict.reject("UNKNOWN_BLOCK");
                }
                if (ctx.anchorBlockIds() != null && !ctx.anchorBlockIds().contains(c.blockId())) {
                    return Verdict.reject("UNKNOWN_ANCHOR");
                }
                String quote = TextNorm.normalize(c.quote());
                if (quote.isEmpty() || !TextNorm.normalize(ctx.blockTexts().get(c.blockId())).contains(quote)) {
                    return Verdict.reject("QUOTE_NOT_FOUND");
                }
                hasReport = true;
            } else if ("data".equalsIgnoreCase(c.kind())) {
                Optional<JsonNode> actual = resolve(ctx.facts(), c.ref());
                if (actual.isEmpty() || !sameValue(actual.get(), c.value())) {
                    return Verdict.reject("DATA_MISMATCH");
                }
                hasData = true;
            } else {
                return Verdict.reject("UNKNOWN_CITATION_KIND");
            }
        }
        if (!hasReport) {
            return Verdict.reject("NO_REPORT_CITATION");
        }
        if ("NUM_MISMATCH".equals(f.category()) && !hasData) {
            return Verdict.reject("INSUFFICIENT_EVIDENCE");
        }
        return Verdict.ACCEPT;
    }

    private static final Pattern TOKEN = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(?:\\[([^\\]]+)])?$");

    /** "quantityTable[0].count" · "grade.computed" · "defects.length" · "defects[D-0001].widthMm" */
    public static Optional<JsonNode> resolve(JsonNode root, String ref) {
        if (root == null || ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        JsonNode cur = root;
        for (String part : ref.split("\\.")) {
            if ("length".equals(part) && cur.isArray()) {
                cur = com.fasterxml.jackson.databind.node.IntNode.valueOf(cur.size());
                continue;
            }
            Matcher m = TOKEN.matcher(part);
            if (!m.matches()) {
                return Optional.empty();
            }
            cur = cur.path(m.group(1));
            if (m.group(2) != null) {
                String idx = m.group(2);
                if (idx.matches("\\d+")) {
                    cur = cur.path(Integer.parseInt(idx));
                } else {
                    JsonNode found = null;
                    for (JsonNode e : cur) {
                        if (idx.equals(e.path("id").asText())) {
                            found = e;
                        }
                    }
                    cur = found == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : found;
                }
            }
            if (cur.isMissingNode() || cur.isNull()) {
                return Optional.empty();
            }
        }
        return Optional.of(cur);
    }

    static boolean sameValue(JsonNode actual, Object claimed) {
        if (claimed == null) {
            return false;
        }
        BigDecimal a = decimal(actual.asText());
        BigDecimal c = decimal(String.valueOf(claimed));
        if (a != null && c != null) {
            return a.compareTo(c) == 0;
        }
        return TextNorm.normalize(actual.asText()).equals(TextNorm.normalize(String.valueOf(claimed)));
    }

    private static BigDecimal decimal(String s) {
        try {
            return new BigDecimal(s.trim());
        } catch (NumberFormatException | NullPointerException e) {
            return null;
        }
    }
}
