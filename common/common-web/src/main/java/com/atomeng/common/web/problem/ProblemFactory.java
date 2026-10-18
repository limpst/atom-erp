package com.atomeng.common.web.problem;

import com.atomeng.common.core.error.ErrorCode;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/**
 * RFC 7807 문제 응답 생성기 (설계 9.2). 모든 오류 응답은 code(CORE-xxx-nnn)·traceId 를 가진다.
 */
public class ProblemFactory {

    public static final String TYPE_BASE = "https://erp.atom-eng.co.kr/problems/";

    private final MessageSource messages;

    public ProblemFactory(MessageSource messages) {
        this.messages = messages;
    }

    public ProblemDetail create(String code, String detail, String instance, Locale locale) {
        return create(code, detail, instance, locale, List.of());
    }

    public ProblemDetail create(String code, String detail, String instance, Locale locale,
                                List<Map<String, Object>> errors) {
        int status = ErrorCode.httpStatusOf(code);
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatusCode.valueOf(status));
        decorate(pd, code, detail, instance, locale);
        if (errors != null && !errors.isEmpty()) {
            pd.setProperty("errors", errors);
        }
        return pd;
    }

    /** 이미 만들어진(프레임워크) ProblemDetail 에 공통 필드를 채운다. */
    public ProblemDetail decorate(ProblemDetail pd, String code, String detail, String instance, Locale locale) {
        Locale loc = locale == null ? Locale.KOREAN : locale;
        pd.setType(URI.create(TYPE_BASE + code.toLowerCase(Locale.ROOT)));
        pd.setTitle(messages.getMessage(code + ".title", null, pd.getTitle() == null ? code : pd.getTitle(), loc));
        String resolvedDetail = detail != null ? detail : messages.getMessage(code, null, null, loc);
        if (resolvedDetail != null) {
            pd.setDetail(resolvedDetail);
        }
        if (instance != null) {
            pd.setInstance(URI.create(instance));
        }
        pd.setProperty("code", code);
        pd.setProperty("traceId", MDC.get("traceId"));
        return pd;
    }
}
