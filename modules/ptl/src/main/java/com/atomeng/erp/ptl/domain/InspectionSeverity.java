package com.atomeng.erp.ptl.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 차기 법정점검 표시 심각도 (설계 09 B7.2): 경과 OVERDUE, D-30 이하 RED, D-90 이하 YELLOW, 그 외 GREEN.
 * 도래일은 FAC 점검주기 엔진 값만 쓴다(PTL 재계산 없음 — ⚠️ 법령 최신본 확인 필요).
 */
public enum InspectionSeverity {
    OVERDUE, RED, YELLOW, GREEN;

    public static InspectionSeverity of(LocalDate dueDate, LocalDate today) {
        long d = dDay(dueDate, today);
        if (d < 0) {
            return OVERDUE;
        }
        if (d <= 30) {
            return RED;
        }
        return d <= 90 ? YELLOW : GREEN;
    }

    /** 남은 일수(오늘 = 0, 지난 날 = 음수) */
    public static long dDay(LocalDate dueDate, LocalDate today) {
        return ChronoUnit.DAYS.between(today, dueDate);
    }
}
