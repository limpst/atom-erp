package com.atomeng.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 정상 반환 후 감사 로그 기록 (00-IMPL Step 6). 예:
 * {@code @Audited(module = "CORE", action = AuditLog.Action.VIEW_PII, targetType = "Employee", targetIdExpr = "#id")}
 *
 * <p>afterJson 은 반환값(ResponseEntity 면 body)을 @Pii 마스킹 직렬화한 값. 권한 거부·예외 시에는 기록하지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    String module();

    AuditLog.Action action();

    String targetType();

    /** SpEL — 메서드 파라미터(#이름)와 #result(본문) 사용 가능. UUID 또는 UUID 문자열로 평가돼야 한다. */
    String targetIdExpr() default "#result.id";

    /** false 면 afterJson 을 남기지 않는다(대용량 응답 등) */
    boolean recordResult() default true;
}
