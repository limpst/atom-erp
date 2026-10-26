package com.atomeng.common.identity.authz;

import com.atomeng.common.web.idempotency.IdempotencyFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.annotation.Order;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 보안 검토 CMN-09 — 멱등 재반환 전 권한 재확인. {@link IdempotencyFilter} 가 저장된 2xx 응답을 돌려주기 전에 요청을
 * "재반환 확인" 표식과 함께 다시 디스패치하면:
 * <ul>
 *   <li>{@code @RequirePermission} 이 붙은 핸들러: 인자 바인딩 → {@link RequirePermissionAspect}(@Order 10)가 현재 주체로
 *       역할 · 권한 · 범위(projectIdParam/scopeResolver)를 다시 검사 → 통과하면 이 aspect(@Order 15, 검사 안쪽 · 감사
 *       @Order 20 바깥)가 <b>업무 메서드를 실행하지 않고</b> 통과 표식만 남긴다. 거부되면 403/404 가 그대로 응답된다.
 *       {@code scopeCheckedInService} 엔드포인트는 서비스 안 행 범위 검사를 다시 돌릴 수 없으므로 역할 → 권한 재확인까지만</li>
 *   <li>{@code @RequirePermission} 이 없는 핸들러(인증만 필요): 보안 필터 체인이 이미 현재 인증을 확인했으므로 인터셉터가
 *       핸들러 실행 없이 통과 표식</li>
 * </ul>
 */
@Aspect
@Order(15)
public class IdempotentReplayAuthorization implements IdempotencyFilter.ReplayAuthorization, WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (!IdempotencyFilter.isReplayPending(req)) {
                    return true;
                }
                if (handler instanceof HandlerMethod hm
                        && AnnotatedElementUtils.hasAnnotation(hm.getMethod(), RequirePermission.class)) {
                    return true;   // 권한 aspect 가 재검사 → 아래 around 가 실행을 막는다
                }
                if (handler instanceof HandlerMethod) {
                    IdempotencyFilter.markAuthorized(req);
                    return false;   // 인증만 필요한 핸들러 — 실행하지 않음
                }
                return true;        // 핸들러 없음(404 등) — 정상 처리, 재반환 안 함
            }
        });
    }

    @Around("@annotation(rp)")
    public Object shortCircuitReplay(ProceedingJoinPoint pjp, RequirePermission rp) throws Throwable {
        HttpServletRequest req = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra
                ? sra.getRequest() : null;
        if (req == null || !IdempotencyFilter.isReplayPending(req)) {
            return pjp.proceed();
        }
        IdempotencyFilter.markAuthorized(req);   // RequirePermissionAspect(@Before, 바깥) 검사를 통과했다
        Class<?> rt = ((MethodSignature) pjp.getSignature()).getReturnType();
        if (!rt.isPrimitive() || rt == void.class) {
            return null;
        }
        return rt == boolean.class ? Boolean.FALSE : rt == char.class ? Character.valueOf('\0')
                : rt == long.class ? Long.valueOf(0) : rt == double.class ? Double.valueOf(0)
                : rt == float.class ? Float.valueOf(0) : rt == short.class ? Short.valueOf((short) 0)
                : rt == byte.class ? Byte.valueOf((byte) 0) : Integer.valueOf(0);
    }
}
