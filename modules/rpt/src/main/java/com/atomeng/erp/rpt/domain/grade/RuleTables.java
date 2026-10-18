package com.atomeng.erp.rpt.domain.grade;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 상태평가 · 등급 rule table 묶음 (설계 06 B7.1, 06-IMPL Step 2) — COND_RATING · GRADE_WEIGHT · GRADE_BAND.
 * 본문 형식은 골든 픽스처(golden/rules/BLD_RC-*.json)와 같고, DB(rpt.rule_set · rule_item)도 같은 JSON 으로 조립된다.
 * ⚠️ 값은 예시 — 세부지침 상태평가 기준표로 교체(법령 최신본 확인 필요).
 */
public record RuleTables(String facilityClass, Map<String, String> versions, Map<String, BandRule> items,
                         Extent extent, Map<String, BigDecimal> weights, FloorAgg floorAgg,
                         Map<Rating, BigDecimal> scores, List<GradeBand> bands, List<FloorRule> floorRules) {

    public static final String COND_RATING = "COND_RATING";
    public static final String GRADE_WEIGHT = "GRADE_WEIGHT";
    public static final String GRADE_BAND = "GRADE_BAND";
    public static final List<String> KINDS = List.of(COND_RATING, GRADE_WEIGHT, GRADE_BAND);

    /** 구간: lt == null 이면 else(마지막) */
    public record Band(BigDecimal lt, Rating rating) {}

    public record BandRule(String key, String unit, List<Band> bands) {

        public Band rate(BigDecimal value) {
            for (Band b : bands) {
                if (b.lt() == null || value.compareTo(b.lt()) < 0) {
                    return b;
                }
            }
            throw new DomainException(RptCodes.RULE_INVALID, "구간 없음: " + key);
        }
    }

    /** 분포 보정: 결함 부재 비율 &lt; lt 이고 등급이 floor 보다 나쁘면 max(등급+delta, floor) */
    public record Extent(BigDecimal lt, int delta, Rating floor) {}

    /** 동 점수 집계: AVG 또는 AVG_WORST_BLEND((1−w)×평균 + w×최악층) */
    public record FloorAgg(String mode, BigDecimal worstWeight) {}

    public record GradeBand(BigDecimal lt, SafetyGrade grade) {}

    /** 하한 규칙: memberTypes 중 rating 이상(나쁨)인 부재가 있으면 최소 minGrade */
    public record FloorRule(String id, List<String> memberTypes, Rating rating, SafetyGrade minGrade) {}

    public BandRule item(String key) {
        return items.get(key);
    }

    public BigDecimal weightOf(String memberType) {
        BigDecimal w = weights.get(memberType);
        if (w == null) {
            throw new DomainException(RptCodes.RULE_INVALID, "GRADE_WEIGHT 에 부재유형 가중치 없음: " + memberType);
        }
        return w;
    }

    public SafetyGrade gradeOf(BigDecimal score) {
        for (GradeBand b : bands) {
            if (b.lt() == null || score.compareTo(b.lt()) < 0) {
                return b.grade();
            }
        }
        throw new DomainException(RptCodes.RULE_INVALID, "GRADE_BAND 구간 없음");
    }

    /** 고정 버전 표기 {"COND_RATING":"BLD_RC@2026.1", …} — snapshot.rule_set_versions (BR-RPT-002) */
    public Map<String, String> pinned() {
        Map<String, String> m = new TreeMap<>();
        versions.forEach((k, v) -> m.put(k, facilityClass + "@" + v));
        return m;
    }

    /** 픽스처 · DB 조립 JSON 파싱 + 의미 검증(구간 오름차순 · else 마지막 · 단위 필수 · 단조성 · 가중치 양수) */
    public static RuleTables parse(JsonNode n) {
        try {
            String fc = text(n, "facilityClass");
            Map<String, String> versions = new TreeMap<>();
            if (n.has("versions")) {
                n.get("versions").properties().forEach(e -> versions.put(e.getKey(), e.getValue().asText()));
            } else {
                String v = text(n, "version");
                KINDS.forEach(k -> versions.put(k, v));
            }
            JsonNode cond = n.get("condRating");
            Map<String, BandRule> items = new TreeMap<>();
            cond.get("items").properties().forEach(e -> items.put(e.getKey(), bandRule(e.getKey(), e.getValue())));
            Extent extent = null;
            JsonNode ex = cond.get("extent");
            if (ex != null && !ex.isNull()) {
                extent = new Extent(new BigDecimal(text(ex, "lt")), ex.get("delta").asInt(), Rating.of(text(ex, "floor")));
            }
            JsonNode gw = n.get("gradeWeight");
            Map<String, BigDecimal> weights = new TreeMap<>();
            gw.get("weights").properties().forEach(e -> {
                BigDecimal w = new BigDecimal(e.getValue().asText());
                if (w.signum() <= 0) {
                    throw new DomainException(RptCodes.RULE_INVALID, "가중치는 양수: " + e.getKey());
                }
                weights.put(e.getKey(), w);
            });
            JsonNode fa = gw.get("floorAgg");
            String mode = text(fa, "mode");
            if (!mode.equals("AVG") && !mode.equals("AVG_WORST_BLEND")) {
                throw new DomainException(RptCodes.RULE_INVALID, "floorAgg.mode: " + mode);
            }
            BigDecimal ww = fa.hasNonNull("worstWeight") ? new BigDecimal(text(fa, "worstWeight")) : null;
            if (mode.equals("AVG_WORST_BLEND") && ww == null) {
                throw new DomainException(RptCodes.RULE_INVALID, "AVG_WORST_BLEND 에는 worstWeight 필요");
            }
            JsonNode gb = n.get("gradeBand");
            Map<Rating, BigDecimal> scores = new EnumMap<>(Rating.class);
            gb.get("scores").properties().forEach(e -> scores.put(Rating.of(e.getKey()),
                    new BigDecimal(e.getValue().asText())));
            if (scores.size() != Rating.values().length) {
                throw new DomainException(RptCodes.RULE_INVALID, "GRADE_BAND.scores 는 a~e 5개");
            }
            List<GradeBand> bands = new ArrayList<>();
            BigDecimal prev = null;
            for (JsonNode b : gb.get("bands")) {
                BigDecimal lt = b.path("else").asBoolean(false) ? null : new BigDecimal(text(b, "lt"));
                prev = ascending(prev, lt, "GRADE_BAND.bands");
                bands.add(new GradeBand(lt, SafetyGrade.valueOf(text(b, "grade"))));
            }
            requireElseLast(bands.isEmpty() ? BigDecimal.ONE : bands.get(bands.size() - 1).lt(), "GRADE_BAND.bands");
            List<FloorRule> floorRules = new ArrayList<>();
            for (JsonNode r : gb.path("floorRules")) {
                List<String> types = new ArrayList<>();
                r.get("memberTypes").forEach(t -> types.add(t.asText()));
                floorRules.add(new FloorRule(text(r, "id"), List.copyOf(types), Rating.of(text(r, "rating")),
                        SafetyGrade.valueOf(text(r, "minGrade"))));
            }
            return new RuleTables(fc, Map.copyOf(versions), Map.copyOf(items), extent, Map.copyOf(weights), agg(mode, ww),
                    Map.copyOf(scores), List.copyOf(bands), List.copyOf(floorRules));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DomainException(RptCodes.RULE_INVALID, "rule table 형식 오류: " + e);
        }
    }

    private static FloorAgg agg(String mode, BigDecimal ww) {
        return new FloorAgg(mode, ww);
    }

    private static BandRule bandRule(String key, JsonNode n) {
        if (!n.hasNonNull("unit") || n.get("unit").asText().isBlank()) {
            throw new DomainException(RptCodes.RULE_INVALID, "단위(unit) 누락: " + key);   // 06-IMPL Step 2 팁
        }
        List<Band> bands = new ArrayList<>();
        BigDecimal prev = null;
        for (JsonNode b : n.get("bands")) {
            BigDecimal lt = b.path("else").asBoolean(false) ? null : new BigDecimal(text(b, "lt"));
            prev = ascending(prev, lt, key);
            bands.add(new Band(lt, Rating.of(text(b, "rating"))));
        }
        requireElseLast(bands.isEmpty() ? BigDecimal.ONE : bands.get(bands.size() - 1).lt(), key);
        for (int i = 1; i < bands.size(); i++) {
            if (bands.get(i).rating().ordinal() < bands.get(i - 1).rating().ordinal()) {
                throw new DomainException(RptCodes.RULE_INVALID, "값이 커지면 등급이 나빠지거나 같아야 함(단조성): " + key);
            }
        }
        return new BandRule(key, n.get("unit").asText(), List.copyOf(bands));
    }

    private static BigDecimal ascending(BigDecimal prev, BigDecimal lt, String key) {
        if (lt != null && prev != null && lt.compareTo(prev) <= 0) {
            throw new DomainException(RptCodes.RULE_INVALID, "구간 상한은 오름차순: " + key);
        }
        return lt == null ? prev : lt;
    }

    private static void requireElseLast(BigDecimal lastLt, String key) {
        if (lastLt != null) {
            throw new DomainException(RptCodes.RULE_INVALID, "마지막 구간은 else: " + key);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) {
            throw new DomainException(RptCodes.RULE_INVALID, "필드 누락: " + field);
        }
        return v.asText();
    }
}
