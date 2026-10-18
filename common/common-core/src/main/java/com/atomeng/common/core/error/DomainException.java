package com.atomeng.common.core.error;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 업무 규칙 위반. code 의 HTTP 부분이 응답 상태가 된다(FLD-422-003 → 422). */
public class DomainException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final transient Object[] args;
    private final transient List<Map<String, Object>> fieldErrors;

    public DomainException(String code, String message, Object... args) {
        this(code, message, List.of(), args);
    }

    public DomainException(String code, String message, List<Map<String, Object>> fieldErrors, Object... args) {
        super(message);
        ErrorCode.parse(code); // 형식 검증
        this.code = code;
        this.args = args;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public String code() {
        return code;
    }

    public Object[] args() {
        return args.clone();
    }

    public int httpStatus() {
        return ErrorCode.httpStatusOf(code);
    }

    public List<Map<String, Object>> fieldErrors() {
        return fieldErrors;
    }

    /** problem type URI 접미사. 예: CORE-422-009 → core-422-009 */
    public String slug() {
        return code.toLowerCase(Locale.ROOT);
    }
}
