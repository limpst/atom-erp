package com.atomeng.erp.cst.domain.billing;

/**
 * 미수 aging 구간 (BR-CST-BIL-006). DB 함수 {@code cst.invoice_open_items(as_of)} 의 bucket 값과 같은 코드를 쓴다.
 * 경과일 = 기준일 − 발행일. D · E(90일 초과)는 독촉 대상.
 */
public enum AgingBucket {
    A_0_30(0, 30), B_31_60(31, 60), C_61_90(61, 90), D_91_180(91, 180), E_181_PLUS(181, Long.MAX_VALUE);

    private final long fromDays;
    private final long toDays;

    AgingBucket(long fromDays, long toDays) {
        this.fromDays = fromDays;
        this.toDays = toDays;
    }

    public static AgingBucket of(long ageDays) {
        if (ageDays < 0) {
            throw new IllegalArgumentException("ageDays must be >= 0: " + ageDays);
        }
        for (AgingBucket b : values()) {
            if (ageDays >= b.fromDays && ageDays <= b.toDays) {
                return b;
            }
        }
        throw new IllegalStateException("unreachable: " + ageDays);
    }

    /** 90일 초과(D · E) — 독촉 1단계 대상 */
    public boolean overdue() {
        return this == D_91_180 || this == E_181_PLUS;
    }
}
