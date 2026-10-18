package com.atomeng.common.core.error;

import java.util.regex.Pattern;

/** 오류 코드 체계 {MOD}-{HTTP}-{nnn} (설계 9.3). 예: CORE-422-009. */
public record ErrorCode(String module, int httpStatus, int seq) {

    private static final Pattern FORMAT = Pattern.compile("^([A-Z]{2,4})-([1-5]\\d\\d)-(\\d{3})$");

    public static ErrorCode parse(String code) {
        var m = FORMAT.matcher(code == null ? "" : code);
        if (!m.matches()) {
            throw new IllegalArgumentException("잘못된 오류 코드 형식: " + code + " (기대: MOD-HTTP-nnn)");
        }
        return new ErrorCode(m.group(1), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
    }

    public static int httpStatusOf(String code) {
        return parse(code).httpStatus();
    }

    @Override
    public String toString() {
        return "%s-%d-%03d".formatted(module, httpStatus, seq);
    }
}
