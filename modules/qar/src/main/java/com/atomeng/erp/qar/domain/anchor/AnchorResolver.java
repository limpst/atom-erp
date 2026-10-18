package com.atomeng.erp.qar.domain.anchor;

import com.atomeng.erp.rpt.api.RptRevisionQuery.Block;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Section;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 코멘트 앵커 검증 · 재매핑 (FR-QAR-004 · 005, B7.5, TC-QAR-007 · 014).
 * <ul>
 *   <li>TEXT_RANGE: blockId 가 리비전 블록에 있고 [start, end) 원문 = quote 여야 한다(오프셋은 블록 원문 기준).
 *       start/end 가 없으면 quote 의 첫 위치로 계산</li>
 *   <li>재매핑: ① 같은 블록 quote 정확 일치 → OK(오프셋 같음) / REMAPPED ② 같은 블록 퍼지(레벤슈타인 비율 ≤ 15%) → REMAPPED
 *       ③ 같은 섹션 다른 블록에서 정확 · 퍼지 → REMAPPED(블록 이동) ④ 실패 → LOST(원 인용문 유지)</li>
 * </ul>
 */
public final class AnchorResolver {

    public static final double FUZZY_RATIO = 0.15;

    public enum Status { OK, REMAPPED, LOST }

    public record Remap(Status status, Map<String, Object> anchor) {}

    private AnchorResolver() {}

    /** TEXT_RANGE 검증 → 정규화된 앵커(start · end · quote 확정) 또는 empty(불일치) */
    public static Optional<Map<String, Object>> validateTextRange(Map<String, Object> anchor, Block block) {
        String text = block.text() == null ? "" : block.text();
        Object q = anchor.get("quote");
        String quote = q == null ? null : q.toString();
        Integer start = intOf(anchor.get("start"));
        Integer end = intOf(anchor.get("end"));
        if (quote == null || quote.isEmpty()) {
            return Optional.empty();
        }
        if (start == null || end == null) {
            int i = text.indexOf(quote);
            if (i < 0) {
                return Optional.empty();
            }
            start = i;
            end = i + quote.length();
        }
        if (start < 0 || end > text.length() || start >= end || !text.substring(start, end).equals(quote)) {
            return Optional.empty();
        }
        Map<String, Object> a = new LinkedHashMap<>(anchor);
        a.put("type", "TEXT_RANGE");
        a.put("blockId", block.blockId());
        a.put("sectionKey", block.sectionKey());
        a.put("start", start);
        a.put("end", end);
        a.put("quote", quote);
        return Optional.of(a);
    }

    public static Remap remap(Map<String, Object> old, UUID newRevisionId, List<Section> sections) {
        String type = String.valueOf(old.get("type"));
        Map<String, Object> base = new LinkedHashMap<>(old);
        base.put("revisionId", newRevisionId);
        String sectionKey = old.get("sectionKey") == null ? null : old.get("sectionKey").toString();
        if ("SECTION".equals(type)) {
            boolean exists = sections.stream().anyMatch(s -> s.sectionKey().equals(sectionKey));
            return new Remap(exists ? Status.OK : Status.LOST, base);
        }
        UUID blockId = old.get("blockId") == null ? null : UUID.fromString(old.get("blockId").toString());
        Optional<Block> same = sections.stream().flatMap(s -> s.blocks().stream())
                .filter(b -> b.blockId().equals(blockId)).findFirst();
        if (!"TEXT_RANGE".equals(type)) {
            return new Remap(same.isPresent() ? Status.OK : Status.LOST, base);
        }
        String quote = String.valueOf(old.get("quote"));
        if (same.isPresent()) {
            String text = same.get().text();
            int i = text.indexOf(quote);
            if (i >= 0) {
                boolean moved = !Integer.valueOf(i).equals(intOf(old.get("start")));
                return new Remap(moved ? Status.REMAPPED : Status.OK, at(base, same.get(), i, i + quote.length()));
            }
            Optional<int[]> f = fuzzy(text, quote);
            if (f.isPresent()) {
                return new Remap(Status.REMAPPED, at(base, same.get(), f.get()[0], f.get()[1]));
            }
        }
        for (Section s : sections) {
            if (!s.sectionKey().equals(sectionKey)) {
                continue;
            }
            for (Block b : s.blocks()) {
                int i = b.text().indexOf(quote);
                if (i >= 0) {
                    return new Remap(Status.REMAPPED, at(base, b, i, i + quote.length()));
                }
            }
            for (Block b : s.blocks()) {
                Optional<int[]> f = fuzzy(b.text(), quote);
                if (f.isPresent()) {
                    return new Remap(Status.REMAPPED, at(base, b, f.get()[0], f.get()[1]));
                }
            }
        }
        base.put("lostQuote", quote);
        return new Remap(Status.LOST, base);
    }

    private static Map<String, Object> at(Map<String, Object> base, Block b, int start, int end) {
        Map<String, Object> a = new LinkedHashMap<>(base);
        a.put("blockId", b.blockId());
        a.put("sectionKey", b.sectionKey());
        a.put("start", start);
        a.put("end", end);
        a.put("quote", b.text().substring(start, end));
        return a;
    }

    /** 길이 ±15% 창 중 레벤슈타인 비율 최소(≤ 15%) 구간 */
    static Optional<int[]> fuzzy(String text, String quote) {
        if (text == null || quote.isEmpty()) {
            return Optional.empty();
        }
        int q = quote.length();
        int slack = Math.max(1, (int) Math.ceil(q * FUZZY_RATIO));
        double best = Double.MAX_VALUE;
        int[] span = null;
        for (int len = Math.max(1, q - slack); len <= q + slack; len++) {
            for (int s = 0; s + len <= text.length(); s++) {
                int d = TextNorm.levenshtein(text.substring(s, s + len), quote);
                double r = (double) d / q;
                if (r < best) {
                    best = r;
                    span = new int[] {s, s + len};
                }
            }
        }
        return best <= FUZZY_RATIO ? Optional.of(span) : Optional.empty();
    }

    static Integer intOf(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
