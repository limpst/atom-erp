package com.atomeng.erp.cst.domain.ledger;

import com.atomeng.erp.cst.domain.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * 인건비 원가 계산 (설계 B7.4, BR-CST-004) — 순수 도메인.
 * 인건비 = 투입시간 ÷ 8 × 일 단가. 표준단가(등급별)와 실단가(개인별)를 모두 계산하고, 원 단위 HALF_UP 1회 반올림.
 * 실단가가 없으면 표준단가로 대체하고 플래그(REAL_RATE_FALLBACK)를 남긴다.
 */
public final class LaborCost {

    public static final BigDecimal HOURS_PER_DAY = new BigDecimal("8");
    public static final String FLAG_REAL_RATE_FALLBACK = "REAL_RATE_FALLBACK";

    private LaborCost() {}

    public record Result(BigDecimal hours, BigDecimal mandays, Money amountReal, Money amountStd,
                         BigDecimal origAmount, boolean realRateFallback) {}

    /**
     * @param hours     투입시간(0 초과, 정정 역분개는 별도 negate)
     * @param stdDaily  등급별 표준 일 단가(KRW)
     * @param realDaily 개인 실 일 단가(KRW), 없으면 null
     */
    public static Result compute(BigDecimal hours, BigDecimal stdDaily, BigDecimal realDaily) {
        Objects.requireNonNull(hours, "hours");
        Objects.requireNonNull(stdDaily, "stdDaily");
        if (hours.signum() <= 0) {
            throw new IllegalArgumentException("hours > 0: " + hours);
        }
        if (stdDaily.signum() < 0 || (realDaily != null && realDaily.signum() < 0)) {
            throw new IllegalArgumentException("단가는 0 이상");
        }
        boolean fallback = realDaily == null;
        BigDecimal real = fallback ? stdDaily : realDaily;
        // 정밀도 보존: 단가 × 시간 ÷ 8 (나눗셈은 마지막, 소수 10자리 후 원 단위 반올림)
        BigDecimal realRaw = real.multiply(hours).divide(HOURS_PER_DAY, 10, RoundingMode.HALF_UP);
        BigDecimal stdRaw = stdDaily.multiply(hours).divide(HOURS_PER_DAY, 10, RoundingMode.HALF_UP);
        BigDecimal mandays = hours.divide(HOURS_PER_DAY, 4, RoundingMode.HALF_UP);
        return new Result(hours, mandays, Money.krw(realRaw), Money.krw(stdRaw),
                realRaw.setScale(2, RoundingMode.HALF_UP), fallback);
    }
}
