package com.atomeng.erp.cst.domain.shared;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * 금액 값 객체 (08-IMPL Step 2, NFR-CST-02). BigDecimal + 통화, HALF_UP 일원화. KRW 는 원 단위(scale 0), 외화 scale 2.
 * 부동소수(double/float)는 생성자·연산 어디에도 받지 않는다.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    public static final Currency KRW = Currency.getInstance("KRW");
    public static final RoundingMode RM = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(scaleOf(currency), RM);
    }

    public static Money krw(long won) {
        return new Money(BigDecimal.valueOf(won), KRW);
    }

    public static Money krw(BigDecimal v) {
        return new Money(v, KRW);
    }

    public static Money zero(Currency c) {
        return new Money(BigDecimal.ZERO, c);
    }

    static int scaleOf(Currency c) {
        return c.equals(KRW) ? 0 : 2;
    }

    public Money plus(Money o) {
        sameCurrency(o);
        return new Money(amount.add(o.amount), currency);
    }

    public Money minus(Money o) {
        sameCurrency(o);
        return new Money(amount.subtract(o.amount), currency);
    }

    /** 곱셈은 원 단위로 한 번만 반올림(중간 결과 반올림 금지) */
    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** fxRate = 외화 1 단위당 KRW */
    public Money toKrw(BigDecimal fxRate) {
        return currency.equals(KRW) ? this : new Money(amount.multiply(fxRate), KRW);
    }

    private void sameCurrency(Money o) {
        if (!currency.equals(o.currency)) {
            throw new IllegalArgumentException("currency mismatch " + currency + "/" + o.currency);
        }
    }

    @Override
    public int compareTo(Money o) {
        sameCurrency(o);
        return amount.compareTo(o.amount);
    }
}
