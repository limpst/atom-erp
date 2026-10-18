package com.atomeng.erp.rpt.domain.grade;

import java.util.Locale;

/** 부재 상태평가 등급 a(양호) … e(불량). 순서 비교는 ordinal(문자 코드 가정 대신 enum — 06-IMPL Step 6 팁). */
public enum Rating {
    A, B, C, D, E;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Rating of(String code) {
        return valueOf(code.trim().toUpperCase(Locale.ROOT));
    }

    public Rating shift(int delta) {
        int i = Math.max(0, Math.min(values().length - 1, ordinal() + delta));
        return values()[i];
    }

    public static Rating worse(Rating x, Rating y) {
        return x.ordinal() >= y.ordinal() ? x : y;
    }
}
