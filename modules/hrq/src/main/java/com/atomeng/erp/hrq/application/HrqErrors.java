package com.atomeng.erp.hrq.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** HRQ 오류 코드·공통 도우미 */
public final class HrqErrors {
    private HrqErrors() {}

    public static final String NOT_FOUND = "HRQ-404-001";
    /** 마감 월 수정·정정 (BR-HRQ-009·014, TC-HRQ-007·012) */
    public static final String MONTH_CLOSED = "HRQ-409-001";
    public static final String INVALID_STATE = "HRQ-409-002";
    public static final String MONTH_ALREADY_CLOSED = "HRQ-409-003";
    public static final String UNAPPROVED_EXIST = "HRQ-409-004";
    public static final String INVALID = "HRQ-422-001";
    /** HRQ-01: 타임시트 승인은 그 프로젝트의 책임기술자(RESP)만 — EXEC · ADMIN 은 사유와 함께 대리 승인 */
    public static final String NOT_PROJECT_RESPONSIBLE = "HRQ-403-001";
    /** HRQ-01 · HRQ-02 · HRQ-06: 본인 타임시트 승인 · 본인 자격 승인/반려 · 본인 등급 변경 금지(역할과 무관) */
    public static final String SELF_APPROVAL = "HRQ-403-002";
    /** HRQ-01: 대리 승인(EXEC · ADMIN) 사유 필수(5자 이상) */
    public static final String DELEGATION_REASON_REQUIRED = "HRQ-422-002";
    /** HRQ-03: 근무일에 투입(core.project_member)되지 않은 프로젝트 */
    public static final String NOT_ASSIGNED = "HRQ-422-003";
    /** HRQ-03: 하루 합계 24시간 초과(프로젝트 합산) */
    public static final String DAY_LIMIT = "HRQ-422-004";

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    static LocalDate today(Clock clock) {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    static String ym(LocalDate d) {
        return YearMonth.from(d).format(YM);
    }

    static YearMonth parseYm(String ym) {
        if (ym == null || !ym.matches("^\\d{4}(0[1-9]|1[0-2])$")) {
            throw new com.atomeng.common.core.error.DomainException(INVALID, "연월은 YYYYMM 형식: " + ym);
        }
        return YearMonth.parse(ym, YM);
    }

    static String ym(YearMonth m) {
        return m.format(YM);
    }
}
