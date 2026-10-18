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
