package com.atomeng.erp.cst.domain.evm;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** PV 시간 분포 누적함수 (설계 B7.1, 08-IMPL Step 11): LINEAR, FRONT_LOADED(현장조사형, F(t) = 1 - (1 - t)^2) */
public enum Distribution {
    LINEAR {
        @Override
        BigDecimal f(BigDecimal t) {
            return t;
        }
    },
    FRONT_LOADED {
        @Override
        BigDecimal f(BigDecimal t) {
            BigDecimal r = BigDecimal.ONE.subtract(t);
            return BigDecimal.ONE.subtract(r.multiply(r));
        }
    };

    abstract BigDecimal f(BigDecimal t);

    /** 기간 [start, end] 에서 asOf 까지의 누적 비율(0~1). end 당일 이후는 1 */
    public BigDecimal cdf(LocalDate start, LocalDate end, LocalDate asOf) {
        if (asOf.isBefore(start)) {
            return BigDecimal.ZERO;
        }
        if (!asOf.isBefore(end)) {
            return BigDecimal.ONE;
        }
        BigDecimal t = BigDecimal.valueOf(ChronoUnit.DAYS.between(start, asOf))
                .divide(BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end)), 10, RoundingMode.HALF_UP);
        return f(t);
    }
}
