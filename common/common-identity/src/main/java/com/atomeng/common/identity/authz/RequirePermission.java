package com.atomeng.common.identity.authz;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 메서드 권한 선언 (설계 8.3-2). 예: {@code @RequirePermission(value = "core:project:read", projectIdParam = "id")}
 *
 * <p><b>실패하는 쪽으로 닫힘 (보안 검토 CMN-05).</b> 호출자의 범위가 ALL 이 아니면(PROJECT · CLIENT · SELF)
 * 아래 셋 중 하나로 범위를 판정할 수 있어야 한다. 하나도 없으면 aspect 가 403 CORE-403-002 로 거부한다.
 * <ol>
 *   <li>{@link #projectIdParam()} — 프로젝트 id 파라미터(UUID)</li>
 *   <li>{@link #scopeResolver()} + {@link #resourceParam()} — 자원 id → 프로젝트 · 소유 직원 · 고객사 해석기</li>
 *   <li>{@link #scopeCheckedInService()} = true — 서비스가 행을 읽은 뒤 직접 범위를 검사한다는 명시 표식</li>
 * </ol>
 * {@code PermissionScopeDeclarationTest} 가 non-ALL 권한을 쓰는 모든 컨트롤러 메서드가 셋 중 하나를 선언했는지 검사한다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequirePermission {

    /** {module}:{resource}:{action} */
    String value();

    /** scope=PROJECT/CLIENT 일 때 프로젝트 소속을 검사할 메서드 파라미터 이름(UUID). */
    String projectIdParam() default "";

    /** 자원 id 를 범위 판정 대상(프로젝트 · 소유자 · 고객사)으로 바꾸는 해석기 bean 타입. */
    Class<? extends ScopeResolver> scopeResolver() default ScopeResolver.None.class;

    /** {@link #scopeResolver()} 에 넘길 메서드 파라미터 이름. */
    String resourceParam() default "";

    /** true 면 aspect 는 역할만 보고, 행 수준 범위 검사는 서비스(…Access)가 한다. */
    boolean scopeCheckedInService() default false;
}
