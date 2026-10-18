package com.atomeng.erp.cst.domain.shared;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.regex.Pattern;

/** 회계 귀속월 YYYYMM (cst.cost_ledger_entry.period_ym char(6), hrq posting_period 와 같은 형식) */
public record PeriodYm(YearMonth month) implements Comparable<PeriodYm> {

    private static final Pattern FMT = Pattern.compile("^[0-9]{4}(0[1-9]|1[0-2])$");
    private static final DateTimeFormatter YM = DateTimeFormatter.ofPattern("yyyyMM");

    public PeriodYm {
        Objects.requireNonNull(month, "month");
    }

    public static PeriodYm parse(String ym) {
        if (ym == null || !FMT.matcher(ym).matches()) {
            throw new IllegalArgumentException("회계월은 YYYYMM 형식: " + ym);
        }
        return new PeriodYm(YearMonth.parse(ym, YM));
    }

    public static boolean isValid(String ym) {
        return ym != null && FMT.matcher(ym).matches();
    }

    public static PeriodYm of(LocalDate d) {
        return new PeriodYm(YearMonth.from(d));
    }

    public PeriodYm next() {
        return new PeriodYm(month.plusMonths(1));
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public String value() {
        return month.format(YM);
    }

    @Override
    public int compareTo(PeriodYm o) {
        return month.compareTo(o.month);
    }

    @Override
    public String toString() {
        return value();
    }
}
