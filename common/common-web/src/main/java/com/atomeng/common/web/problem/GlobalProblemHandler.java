package com.atomeng.common.web.problem;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 전역 예외 → RFC 7807 application/problem+json (설계 9.1·9.2, 00-IMPL Step 5).
 * 응답 100%가 code 필드를 가진다(프레임워크 오류는 CORE-{status}-000).
 */
@RestControllerAdvice
public class GlobalProblemHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalProblemHandler.class);

    private final ProblemFactory problems;

    public GlobalProblemHandler(ProblemFactory problems) {
        this.problems = problems;
    }

    @ExceptionHandler(DomainException.class)
    ResponseEntity<ProblemDetail> domain(DomainException ex, HttpServletRequest req, Locale locale) {
        var pd = problems.create(ex.code(), ex.getMessage(), req.getRequestURI(), locale, ex.fieldErrors());
        ex.properties().forEach(pd::setProperty);
        return respond(pd);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ProblemDetail> optimistic(HttpServletRequest req, Locale locale) {
        var pd = problems.create(CoreErrors.VERSION_MISMATCH,
                "다른 사용자가 먼저 수정했습니다. 새로고침 후 다시 시도하세요.", req.getRequestURI(), locale);
        return respond(pd);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ProblemDetail> integrity(DataIntegrityViolationException ex, HttpServletRequest req, Locale locale) {
        log.info("data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        var pd = problems.create(CoreErrors.DATA_CONFLICT, "데이터 제약 조건 위반(중복 또는 참조 무결성)",
                req.getRequestURI(), locale);
        return respond(pd);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest req, Locale locale) {
        log.error("unhandled exception", ex);
        var pd = problems.create(CoreErrors.INTERNAL_ERROR, "서버 내부 오류", req.getRequestURI(), locale);
        return respond(pd);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<Map<String, Object>> errors = ex.getFieldErrors().stream().map(fe -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", fe.getField());
            m.put("reason", fe.getCode());
            m.put("message", fe.getDefaultMessage());
            return m;
        }).toList();
        var pd = problems.create(CoreErrors.VALIDATION_FAILED, "입력값 검증 실패", path(request), request.getLocale(), errors);
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    /** 스프링 MVC 표준 예외(400/404/405/415 …)에도 code·traceId 를 채운다. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> res = super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (res != null && res.getBody() instanceof ProblemDetail pd && pd.getProperties() == null) {
            String code = "CORE-%d-000".formatted(statusCode.value());
            problems.decorate(pd, code, pd.getDetail(), path(request), request.getLocale());
            HttpHeaders h = new HttpHeaders();
            h.putAll(res.getHeaders());
            h.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return new ResponseEntity<>(pd, h, res.getStatusCode());
        }
        return res;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail pd) {
        return ResponseEntity.status(pd.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    private static String path(WebRequest request) {
        return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : null;
    }
}
