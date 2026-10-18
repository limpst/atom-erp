package com.atomeng.erp.sal.domain.qual;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.domain.SalCodes;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 적격심사 점수 계산 (설계 B7.2 · 01-IMPL Step 12, FR-SAL-006, BR-SAL-014). 순수 도메인.
 * 규칙(QUALIFICATION_CRITERIA JSON)에는 "항목 · 구간표 · 가격점수 유형 코드 + 계수"만 두고 산식은 코드에 고정한다(수식 문자열 금지).
 * 실적 근거는 sal.performance_record 만 — 공동도급은 지분율만큼만(호출자가 합산한 similarAmount 로 전달).
 * ⚠️ 배점 · 구간 · 산식 계수는 예시 — 발주기관 기준 최신본 확인 필요. 추천 투찰률은 참고용(B12-5).
 */
public final class QualificationScorer {

    private static final int SCALE = 2;

    /** 회사 수행능력 스냅샷 — similarAmount = Σ amount_krw × joint_share/100 (최근 N년, 같은 용역 종류) */
    public record CompanySnapshot(BigDecimal similarAmount, int similarCount, Integer engineersQualified,
                                  Integer engineersRequired, String creditGrade, boolean disabledStandardWorkplace) {}

    public record ItemScore(String key, BigDecimal raw, BigDecimal ratio, BigDecimal score, BigDecimal max) {}

    public record Capability(List<ItemScore> items, BigDecimal total) {}

    public record Scenario(BigDecimal bidRate, BigDecimal priceScore, BigDecimal total, boolean pass,
                           boolean belowLowerLimit) {}

    public record Result(String criteriaCode, BigDecimal passScore, BigDecimal lowerLimitRate, Capability capability,
                         List<Scenario> scenarios, BigDecimal recommendedBidRate, String notice) {}

    public Result simulate(JsonNode criteria, CompanySnapshot snap, BigDecimal estPrice, List<BigDecimal> bidRates) {
        if (estPrice == null || estPrice.signum() <= 0) {
            throw new DomainException(SalCodes.INVALID, "추정가격이 필요합니다");
        }
        Capability cap = capability(criteria, snap, estPrice);
        BigDecimal pass = criteria.path("passScore").decimalValue();
        BigDecimal lower = criteria.path("lowerLimitRate").decimalValue();
        JsonNode pf = criteria.path("priceFormula");
        List<Scenario> scenarios = new ArrayList<>();
        for (BigDecimal r : bidRates) {
            boolean below = r.compareTo(lower) < 0;
            BigDecimal price = below ? BigDecimal.ZERO.setScale(SCALE) : priceScore(pf, r);
            BigDecimal total = cap.total().add(price).setScale(SCALE, RoundingMode.HALF_UP);
            scenarios.add(new Scenario(r, price, total, !below && total.compareTo(pass) >= 0, below));
        }
        BigDecimal recommended = scenarios.stream().filter(Scenario::pass).map(Scenario::bidRate)
                .min(Comparator.naturalOrder()).orElse(null);
        return new Result(criteria.path("criteriaCode").asText(), pass, lower, cap, List.copyOf(scenarios), recommended,
                "추천 투찰률은 자사 데이터 · 공개 개찰결과 기반 참고값입니다(최종 판단은 사람)");
    }

    public Capability capability(JsonNode criteria, CompanySnapshot snap, BigDecimal estPrice) {
        List<ItemScore> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode item : criteria.path("capabilityItems")) {
            String key = item.path("key").asText();
            BigDecimal max = item.path("max").decimalValue();
            ItemScore s = switch (key) {
                case "SIMILAR_PERFORMANCE" -> table(key, item, snap.similarAmount(), estPrice, max);
                case "ENGINEERS" -> table(key, item,
                        snap.engineersQualified() == null ? null : BigDecimal.valueOf(snap.engineersQualified()),
                        snap.engineersRequired() == null ? null : BigDecimal.valueOf(snap.engineersRequired()), max);
                case "CREDIT" -> {
                    JsonNode g = snap.creditGrade() == null ? null : item.path("gradeTable").get(snap.creditGrade());
                    BigDecimal score = g == null ? BigDecimal.ZERO : g.decimalValue();
                    yield new ItemScore(key, null, null, clamp(score, max), max);
                }
                case "BONUS_DISABLED_STD" -> new ItemScore(key, null, null,
                        clamp(snap.disabledStandardWorkplace() ? item.path("boolScore").decimalValue() : BigDecimal.ZERO,
                                max), max);
                default -> throw new DomainException(SalCodes.RULE_INVALID, "알 수 없는 수행능력 항목: " + key);
            };
            items.add(s);
            total = total.add(s.score());
        }
        return new Capability(List.copyOf(items), total.setScale(SCALE, RoundingMode.HALF_UP));
    }

    /** 가격점수 — 유형 코드 + 계수(규칙)만 데이터, 산식은 코드. ⚠️ LOCAL_GOV_A 는 예시 산식 */
    public static BigDecimal priceScore(JsonNode spec, BigDecimal bidRate) {
        String type = spec.path("type").asText();
        JsonNode p = spec.path("params");
        return switch (type) {
            case "LOCAL_GOV_A" -> {
                // maxScore − k × |투찰률 − 기준률| × 100, [0, maxScore]
                BigDecimal maxScore = p.path("maxScore").decimalValue();
                BigDecimal k = p.path("k").decimalValue();
                BigDecimal anchor = p.path("anchorRate").decimalValue();
                BigDecimal diff = bidRate.subtract(anchor).abs().multiply(BigDecimal.valueOf(100));
                yield clamp(maxScore.subtract(k.multiply(diff)), maxScore).setScale(SCALE, RoundingMode.HALF_UP);
            }
            default -> throw new DomainException(SalCodes.PRICE_FORMULA, "지원하지 않는 가격점수 유형: " + type);
        };
    }

    private static ItemScore table(String key, JsonNode item, BigDecimal raw, BigDecimal reference, BigDecimal max) {
        if (raw == null || reference == null || reference.signum() <= 0) {
            return new ItemScore(key, raw, null, BigDecimal.ZERO, max);
        }
        BigDecimal ratio = raw.divide(reference, 6, RoundingMode.HALF_UP);
        List<JsonNode> rows = new ArrayList<>();
        item.path("table").forEach(rows::add);
        rows.sort(Comparator.comparing((JsonNode r) -> r.path("ratioGte").decimalValue()).reversed());
        Optional<JsonNode> hit = rows.stream().filter(r -> ratio.compareTo(r.path("ratioGte").decimalValue()) >= 0)
                .findFirst();
        BigDecimal score = hit.map(r -> r.path("score").decimalValue()).orElse(BigDecimal.ZERO);
        return new ItemScore(key, raw, ratio, clamp(score, max), max);
    }

    private static BigDecimal clamp(BigDecimal v, BigDecimal max) {
        if (v.signum() < 0) {
            return BigDecimal.ZERO;
        }
        return v.compareTo(max) > 0 ? max : v;
    }

    /** 응답 · 스냅샷용 맵 */
    public static Map<String, Object> view(ItemScore s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", s.key());
        m.put("raw", s.raw());
        m.put("ratio", s.ratio());
        m.put("score", s.score());
        m.put("max", s.max());
        return m;
    }
}
