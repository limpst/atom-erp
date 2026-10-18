package com.atomeng.erp.sal.domain.fee;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.domain.SalCodes;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FEE_STANDARD 규칙 본문(설계 01 B3.1 JSON) — 기준일에 ACTIVE 인 sal.fee_rule_set 1행. 값은 ⚠️ 예시(법령 최신본 확인 필요).
 * 보정계수 조건은 SpEL 이 아니라 "변수 연산자 리터럴" 한 단(화이트리스트 변수)만 허용한다(01-IMPL Step 4 보안 경계).
 */
public record FeeRuleSet(UUID id, String ruleVersion, String legalBasis, Map<String, BigDecimal> laborUnitPrices,
                         RateRange overheadRate, RateRange techFeeRate, Rounding lineRounding, Rounding totalRounding,
                         List<ServiceBand> baseManDays, List<AdjustmentFactor> adjustmentFactors) {

    public record RateRange(BigDecimal min, BigDecimal max, BigDecimal defaultValue) {
        /** BR-SAL-004 / TC-SAL-011: 범위 밖 → 422 SAL-422-002 */
        public BigDecimal requireWithin(BigDecimal v, String label) {
            BigDecimal x = v == null ? defaultValue : v;
            if (x.compareTo(min) < 0 || x.compareTo(max) > 0) {
                throw new DomainException(SalCodes.RATE_OUT_OF_RANGE,
                        "%s %s 는 허용 범위 %s–%s 밖입니다".formatted(label, x.toPlainString(), min.toPlainString(),
                                max.toPlainString()))
                        .with("rate", label).with("min", min).with("max", max);
            }
            return x;
        }
    }

    /** BR-SAL-005 절사 단위 */
    public enum Rounding {
        FLOOR_1, FLOOR_10, HALF_UP_1, FLOOR_1000, FLOOR_10000;

        public BigDecimal apply(BigDecimal v) {
            return switch (this) {
                case FLOOR_1 -> v.setScale(0, RoundingMode.FLOOR);
                case HALF_UP_1 -> v.setScale(0, RoundingMode.HALF_UP);
                case FLOOR_10 -> floorTo(v, 10);
                case FLOOR_1000 -> floorTo(v, 1000);
                case FLOOR_10000 -> floorTo(v, 10000);
            };
        }

        private static BigDecimal floorTo(BigDecimal v, int unit) {
            BigDecimal u = BigDecimal.valueOf(unit);
            return v.divide(u, 0, RoundingMode.FLOOR).multiply(u);
        }
    }

    public record AreaBand(BigDecimal fromM2, BigDecimal toM2, Map<String, BigDecimal> workItems) {
        /** [from, to) — 보간 · 외삽 금지 */
        public boolean contains(BigDecimal area) {
            return area.compareTo(fromM2) >= 0 && area.compareTo(toM2) < 0;
        }
    }

    public record ServiceBand(String serviceType, String facilityClass, List<AreaBand> areaBands,
                              Map<String, BigDecimal> gradeMix) {
        public Optional<AreaBand> findBand(BigDecimal area) {
            return areaBands.stream().filter(b -> b.contains(area)).findFirst();
        }
    }

    public record AdjustmentFactor(String key, String when, BigDecimal factor) {
        private static final Pattern P = Pattern.compile("^(\\w+) (>=|<=|==|>|<) ('?)([\\w.]+)\\3$");
        public static final Set<String> VARS = Set.of("years", "structure", "basementFloors", "inUse", "grossAreaM2",
                "floors");

        public AdjustmentFactor {
            Matcher m = P.matcher(when == null ? "" : when);
            if (!m.matches() || !VARS.contains(m.group(1))) {
                throw new DomainException(SalCodes.RULE_INVALID, "허용되지 않는 보정계수 조건: " + when);
            }
        }

        public boolean matches(FeeModels.FacilityProfile f, int asOfYear) {
            Matcher m = P.matcher(when);
            if (!m.matches()) {
                return false;
            }
            Object left = f.variable(m.group(1), asOfYear);
            String op = m.group(2);
            String right = m.group(4);
            return switch (left) {
                case null -> false;
                case Boolean b -> "==".equals(op) && b == Boolean.parseBoolean(right);
                case String s -> "==".equals(op) && s.equals(right);
                case BigDecimal n -> compare(n, op, new BigDecimal(right));
                case Number n -> compare(new BigDecimal(n.toString()), op, new BigDecimal(right));
                default -> false;
            };
        }

        private static boolean compare(BigDecimal l, String op, BigDecimal r) {
            int c = l.compareTo(r);
            return switch (op) {
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case "==" -> c == 0;
                case ">" -> c > 0;
                default -> c < 0;
            };
        }
    }

    public Optional<ServiceBand> service(String serviceType, String facilityClass) {
        return baseManDays.stream()
                .filter(s -> s.serviceType().equals(serviceType) && s.facilityClass().equals(facilityClass))
                .findFirst();
    }

    public BigDecimal laborUnitPrice(String grade) {
        BigDecimal p = laborUnitPrices.get(grade);
        if (p == null) {
            throw new DomainException(SalCodes.RULE_INVALID, "노임단가 없음: " + grade);
        }
        return p;
    }

    /** 규칙 본문 JSON → 도메인 (구조 오류는 SAL-422-008) */
    public static FeeRuleSet parse(UUID id, JsonNode b) {
        try {
            Map<String, BigDecimal> prices = new LinkedHashMap<>();
            b.path("laborUnitPrices").properties().forEach(e -> {
                if (!e.getKey().startsWith("_")) {
                    prices.put(e.getKey(), e.getValue().decimalValue());
                }
            });
            List<ServiceBand> services = new ArrayList<>();
            for (JsonNode s : b.path("baseManDays")) {
                List<AreaBand> bands = new ArrayList<>();
                for (JsonNode a : s.path("areaBands")) {
                    bands.add(new AreaBand(a.get("fromM2").decimalValue(), a.get("toM2").decimalValue(),
                            decimalMap(a.path("workItems"))));
                }
                Map<String, BigDecimal> mix = decimalMap(s.path("gradeMix"));
                services.add(new ServiceBand(s.get("serviceType").asText(), s.get("facilityClass").asText(),
                        List.copyOf(bands), mix));
            }
            List<AdjustmentFactor> factors = new ArrayList<>();
            for (JsonNode f : b.path("adjustmentFactors")) {
                factors.add(new AdjustmentFactor(f.get("key").asText(), f.get("when").asText(),
                        f.get("factor").decimalValue()));
            }
            JsonNode r = b.path("rounding");
            return new FeeRuleSet(id, b.get("ruleVersion").asText(), b.path("legalBasis").asText(""),
                    prices, range(b.get("overheadRate")), range(b.get("techFeeRate")),
                    Rounding.valueOf(r.path("line").asText("FLOOR_1")),
                    Rounding.valueOf(r.path("total").asText("FLOOR_1000")), List.copyOf(services), List.copyOf(factors));
        } catch (DomainException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DomainException(SalCodes.RULE_INVALID, "대가 규칙 본문 구조 오류: " + e.getMessage());
        }
    }

    /** 01-IMPL Step 4 FeeRuleSemanticValidator — gradeMix 합 1.0, 면적 구간 연속, 요율 min ≤ default ≤ max */
    public List<String> semanticErrors() {
        List<String> errors = new ArrayList<>();
        for (ServiceBand s : baseManDays) {
            BigDecimal mix = s.gradeMix().values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            if (mix.compareTo(BigDecimal.ONE) != 0) {
                errors.add(s.serviceType() + " gradeMix 합계=" + mix.toPlainString());
            }
            for (int i = 1; i < s.areaBands().size(); i++) {
                if (s.areaBands().get(i).fromM2().compareTo(s.areaBands().get(i - 1).toM2()) != 0) {
                    errors.add(s.serviceType() + " 면적 구간 불연속: " + s.areaBands().get(i - 1).toM2() + "–"
                            + s.areaBands().get(i).fromM2());
                }
            }
            for (String g : s.gradeMix().keySet()) {
                if (!laborUnitPrices.containsKey(g)) {
                    errors.add(s.serviceType() + " 노임단가 없는 등급: " + g);
                }
            }
        }
        for (RateRange rr : List.of(overheadRate, techFeeRate)) {
            if (rr.min().compareTo(rr.max()) > 0 || rr.defaultValue().compareTo(rr.min()) < 0
                    || rr.defaultValue().compareTo(rr.max()) > 0) {
                errors.add("요율 범위 오류 min=" + rr.min() + " max=" + rr.max() + " default=" + rr.defaultValue());
            }
        }
        return errors;
    }

    private static RateRange range(JsonNode n) {
        return new RateRange(n.get("min").decimalValue(), n.get("max").decimalValue(), n.get("default").decimalValue());
    }

    /**
     * 공종 · 등급 표준 순서. jsonb 는 키 순서를 보존하지 않으므로(길이 · 사전순 재정렬) 산출내역 라인 순서를 이 순서로 고정한다.
     * 목록에 없는 키는 뒤에 사전순.
     */
    static final List<String> CANONICAL_ORDER = List.of("DATA", "FIELD", "TEST", "ANALYSIS", "REPORT",
            "SPECIAL", "ADVANCED", "INTERMEDIATE", "BEGINNER");

    private static Map<String, BigDecimal> decimalMap(JsonNode n) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>();
        n.properties().forEach(entries::add);
        entries.sort((a, b) -> {
            int ia = CANONICAL_ORDER.indexOf(a.getKey());
            int ib = CANONICAL_ORDER.indexOf(b.getKey());
            if (ia >= 0 && ib >= 0) {
                return Integer.compare(ia, ib);
            }
            if (ia >= 0 || ib >= 0) {
                return ia >= 0 ? -1 : 1;
            }
            return a.getKey().compareTo(b.getKey());
        });
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        entries.forEach(e -> m.put(e.getKey(), e.getValue().decimalValue()));
        return m;
    }
}
