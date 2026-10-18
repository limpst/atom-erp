package com.atomeng.erp.rpt.domain.grade;

/** 시설물 안전등급 A(우수) … E(불량) — FAC 등급 이력과 같은 값 */
public enum SafetyGrade {
    A, B, C, D, E;

    public static SafetyGrade worse(SafetyGrade x, SafetyGrade y) {
        return x.ordinal() >= y.ordinal() ? x : y;
    }
}
