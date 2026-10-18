package com.atomeng.common.identity.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 권한 선언 (설계 8.3-2). 예: {@code @RequirePermission(value = "core:project:read", projectIdParam = "id")}
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** {module}:{resource}:{action} */
    String value();

    /** scope=PROJECT 일 때 프로젝트 소속을 검사할 메서드 파라미터 이름(UUID). */
    String projectIdParam() default "";
}
