package com.atomeng.erp.sal.domain;

import com.atomeng.common.core.error.DomainException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * 할인 한도 정책 (BR-SAL-006). 한도는 설정값 {@code atom.sal.discount-limits}(SALES 0.05 · SALES_LEAD 0.10 · EXEC 0.49).
 * 여러 역할 · 그룹이면 가장 큰 한도. 한도 초과 견적은 :issue 에서 PENDING_APPROVAL, EXEC 승인 후 발행.
 * 최종 상한은 DB CHECK(할인율 50% 미만).
 */
public final class DiscountPolicy {

    public static final BigDecimal HARD_CAP = new BigDecimal("0.5");
    public static final String EXEC = "EXEC";

    private final Map<String, BigDecimal> limits;

    public DiscountPolicy(Map<String, BigDecimal> limits) {
        this.limits = Map.copyOf(limits);
        limits.forEach((k, v) -> {
            if (v.signum() < 0 || v.compareTo(HARD_CAP) >= 0) {
                throw new IllegalArgumentException("할인 한도는 0 이상 0.5 미만: " + k + "=" + v);
            }
        });
    }

    /** @param rolesAndGroups 역할(EXEC · SALES …) + 그룹(SALES_LEAD) */
    public BigDecimal limitFor(Set<String> rolesAndGroups) {
        return rolesAndGroups.stream().map(limits::get).filter(java.util.Objects::nonNull)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    public boolean needsApproval(BigDecimal rate, Set<String> rolesAndGroups) {
        return rate.compareTo(limitFor(rolesAndGroups)) > 0;
    }

    /** EXEC 승인 가능 한도(기본 0.49) */
    public BigDecimal execLimit() {
        return limits.getOrDefault(EXEC, new BigDecimal("0.49"));
    }

    public static void requireValidRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(HARD_CAP) >= 0) {
            throw new DomainException(SalCodes.DISCOUNT_RANGE, "할인율은 0 이상 50% 미만이어야 합니다: " + rate);
        }
    }

    public Map<String, BigDecimal> limits() {
        return limits;
    }
}
