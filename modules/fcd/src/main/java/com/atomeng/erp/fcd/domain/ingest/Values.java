package com.atomeng.erp.fcd.domain.ingest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 셀 값 변환 — 금액 · 날짜 · 시간 (설계 B7.1 Values, Python {@code atomeng_common.ingest.values} 이식) */
public final class Values {

    private Values() {}

    public static final class ValueParseException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String code;

        public ValueParseException(String code, String raw) {
            super(code + ": '" + raw + "'");
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record Timed(LocalDateTime at, String precision) {}

    private static final Pattern AMT_STRIP = Pattern.compile("(?i)[\\s,원₩\\\\]|KRW");
    private static final Pattern AMT = Pattern.compile("\\d+(\\.\\d{1,4})?");

    /** '1,234,500원' '(12,000)' '12,000-' '₩ 9,900' '-5000' → BigDecimal */
    public static BigDecimal parseAmount(Object raw) {
        if (raw == null) {
            throw new ValueParseException("E-AMOUNT", "");
        }
        String s = AMT_STRIP.matcher(String.valueOf(raw)).replaceAll("");
        if (s.isEmpty()) {
            throw new ValueParseException("E-AMOUNT", String.valueOf(raw));
        }
        boolean neg = false;
        if (s.startsWith("(") && s.endsWith(")")) {
            neg = true;
            s = s.substring(1, s.length() - 1);
        }
        if (s.endsWith("-")) {
            neg = true;
            s = s.substring(0, s.length() - 1);
        }
        if (s.startsWith("-")) {
            neg = !neg;
            s = s.substring(1);
        }
        if (s.startsWith("+")) {
            s = s.substring(1);
        }
        if (!AMT.matcher(s).matches()) {
            throw new ValueParseException("E-AMOUNT", String.valueOf(raw));
        }
        BigDecimal v = new BigDecimal(s);
        return neg ? v.negate() : v;
    }

    /** Python strptime 형식 → java 패턴 */
    private static final List<String> DATE_FORMATS = List.of("%Y-%m-%d", "%Y.%m.%d", "%Y/%m/%d", "%Y%m%d", "%y.%m.%d",
            "%y-%m-%d");
    private static final LocalDate EXCEL_EPOCH = LocalDate.of(1899, 12, 30);

    /** strptime 은 월·일 1자리도 허용하므로 숫자 그룹으로 직접 해석 */
    static LocalDate strptimeDate(String s, String py) {
        String regex = Pattern.quote(py).replace("%Y", "\\E(\\d{4})\\Q").replace("%y", "\\E(\\d{2})\\Q")
                .replace("%m", "\\E(\\d{1,2})\\Q").replace("%d", "\\E(\\d{1,2})\\Q");
        Matcher m = Pattern.compile(regex).matcher(s);
        if (!m.matches()) {
            throw new DateTimeParseException("no match", s, 0);
        }
        int yi = py.indexOf("%Y") >= 0 ? py.indexOf("%Y") : py.indexOf("%y");
        int mi = py.indexOf("%m");
        int di = py.indexOf("%d");
        int[] order = {yi, mi, di};
        int[] rank = new int[3];
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                if (order[b] < order[a]) {
                    rank[a]++;
                }
            }
        }
        int y = Integer.parseInt(m.group(rank[0] + 1));
        if (py.contains("%y")) {
            y += y < 69 ? 2000 : 1900;   // Python %y 규칙
        }
        return LocalDate.of(y, Integer.parseInt(m.group(rank[1] + 1)), Integer.parseInt(m.group(rank[2] + 1)));
    }

    public static LocalDate parseDate(Object raw, String fmt) {
        if (raw instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (raw instanceof LocalDate d) {
            return d;
        }
        String s = raw == null ? "" : String.valueOf(raw).strip();
        if (s.isEmpty()) {
            throw new ValueParseException("E-DATE", s);
        }
        if ("EXCEL_SERIAL".equals(fmt) || s.matches("\\d{5}(\\.\\d+)?")) {
            try {
                return EXCEL_EPOCH.plusDays((long) Double.parseDouble(s));
            } catch (NumberFormatException e) {
                throw new ValueParseException("E-DATE", s);
            }
        }
        if (s.contains("T") && s.length() >= 10 && s.charAt(4) == '-') {
            s = s.substring(0, 10);
        }
        String head = s.split(" ", 2)[0];
        List<String> fmts = fmt != null ? List.of(fmt) : DATE_FORMATS;
        for (String f : fmts) {
            try {
                return strptimeDate(head, f);
            } catch (java.time.DateTimeException | IllegalStateException e) {
                // 다음 형식
            }
        }
        throw new ValueParseException("E-DATE", s);
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2}):?(\\d{2})(?::?(\\d{2}))?");

    /** (시각, 정밀도 SECOND | MINUTE | DATE) */
    public static Object[] parseTime(Object raw) {
        if (raw == null) {
            return new Object[] {null, "DATE"};
        }
        if (raw instanceof LocalDateTime dt) {
            LocalTime t = dt.toLocalTime();
            return new Object[] {t, t.getSecond() != 0 ? "SECOND" : "MINUTE"};
        }
        String s = String.valueOf(raw).strip();
        if (s.isEmpty()) {
            return new Object[] {null, "DATE"};
        }
        Matcher m = TIME.matcher(s);
        if (!m.matches()) {
            throw new ValueParseException("E-TIME", s);
        }
        int h = Integer.parseInt(m.group(1));
        int mi = Integer.parseInt(m.group(2));
        int se = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
        if (h > 23 || mi > 59 || se > 59) {
            throw new ValueParseException("E-TIME", s);
        }
        return new Object[] {LocalTime.of(h, mi, se), m.group(3) != null ? "SECOND" : "MINUTE"};
    }

    /** '2026-03-05 13:05' 결합형 또는 Excel datetime → (일시, 정밀도) */
    public static Timed parseDatetime(Object raw, String dateFmt) {
        if (raw instanceof LocalDateTime dt) {
            String prec = dt.getSecond() != 0 ? "SECOND" : ((dt.getHour() != 0 || dt.getMinute() != 0) ? "MINUTE" : "DATE");
            return new Timed(dt, prec);
        }
        String s = raw == null ? "" : String.valueOf(raw).strip().replace("T", " ");
        String[] parts = s.split(" ", 2);
        LocalDate d = parseDate(parts[0], dateFmt);
        if (parts.length == 1) {
            return new Timed(d.atStartOfDay(), "DATE");
        }
        Object[] t = parseTime(parts[1]);
        return new Timed(d.atTime(t[0] == null ? LocalTime.MIDNIGHT : (LocalTime) t[0]), (String) t[1]);
    }
}
