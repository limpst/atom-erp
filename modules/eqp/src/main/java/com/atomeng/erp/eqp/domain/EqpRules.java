package com.atomeng.erp.eqp.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/** EQP 순수 규칙 모음 — 사용 일수, 차기 교정일 추정, 앤빌 검사, 알림 기준 선택, Override 기간 */
public final class EqpRules {

    /** BR-EQP-003 Override 유효기간 최대 일수(양 끝 포함) */
    public static final int OVERRIDE_MAX_DAYS = 7;

    private EqpRules() {}

    /** 반출일 ~ 반납일(KST 날짜, 양 끝 포함) — 같은 날 반납도 1일 (CST 장비 원가 일수) */
    public static int usageDays(LocalDate outOn, LocalDate inOn) {
        if (inOn.isBefore(outOn)) {
            throw new IllegalArgumentException("반납일이 반출일보다 빠름");
        }
        return Math.toIntExact(ChronoUnit.DAYS.between(outOn, inOn) + 1);
    }

    /** FR-EQP-003 / A8-3: 차기일 미입력 → 교정일 + 규칙 주기(개월) - 1일 (그 날까지 유효) */
    public static LocalDate estimateNextDue(LocalDate calibratedOn, int intervalMonths) {
        return calibratedOn.plusMonths(intervalMonths).minusDays(1);
    }

    /** BR-EQP-006: |값 − 기준| ≤ 허용오차 */
    public static boolean anvilWithin(double value, double target, double tol) {
        return Math.abs(value - target) <= tol + 1e-9;
    }

    /**
     * D-n 알림 기준 선택(FR-EQP-004): 남은 일수 이하인 기준 중 가장 작은 것. 범위 밖(남은 일수 &lt; 0 또는 &gt; 최댓값)이면 empty.
     * 예) 기준 60·30·7·0, 남은 25일 → 30 / 남은 0일 → 0 / 남은 61일 → empty.
     */
    public static Optional<Integer> threshold(long daysLeft, List<Integer> thresholds) {
        if (daysLeft < 0) {
            return Optional.empty();
        }
        return thresholds.stream().filter(t -> daysLeft <= t).min(Integer::compare);
    }

    /** BR-EQP-003 / TC-EQP-008 */
    public static boolean overridePeriodOk(LocalDate from, LocalDate to) {
        return !to.isBefore(from) && ChronoUnit.DAYS.between(from, to) + 1 <= OVERRIDE_MAX_DAYS;
    }
}
