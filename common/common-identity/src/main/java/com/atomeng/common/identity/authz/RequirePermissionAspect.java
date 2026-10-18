package com.atomeng.common.identity.authz;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.PermissionScope;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;

/**
 * {@link RequirePermission} 검사 (00-IMPL Step 4).
 * <ul>
 *   <li>인증 없음 → 401 CORE-401-001</li>
 *   <li>역할에 권한 없음 → 403 CORE-403-001</li>
 *   <li>scope=PROJECT 인데 투입되지 않은 프로젝트 → 403 CORE-403-002 (TC-CORE-001)</li>
 *   <li>scope=CLIENT 인데 다른 고객사 프로젝트 → 403 CORE-403-002</li>
 * </ul>
 * 메서드 실행 전에 거부하므로 업무 로직·감사 로그는 실행되지 않는다.
 */
@Aspect
@Order(10)
public class RequirePermissionAspect {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public RequirePermissionAspect(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes,
                                   Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    @Before("@annotation(rp)")
    public void check(JoinPoint jp, RequirePermission rp) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal()
                .orElseThrow(() -> new DomainException(CoreErrors.UNAUTHENTICATED, "인증이 필요합니다"));
        PermissionScope scope = registry.scopeFor(p.roles(), rp.value())
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED,
                        "권한 없음: " + rp.value()));
        if (rp.projectIdParam().isEmpty() || scope == PermissionScope.ALL || scope == PermissionScope.SELF) {
            return;
        }
        UUID projectId = extractUuid(jp, rp.projectIdParam());
        ProjectScopeResolver resolver = scopes.getIfAvailable();
        if (resolver == null) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 판정기가 없습니다");
        }
        boolean allowed = switch (scope) {
            case PROJECT -> p.employeeId() != null
                    && resolver.isMember(p.employeeId(), projectId, LocalDate.now(clock.withZone(SEOUL)));
            case CLIENT -> p.clientOrgId() != null
                    && resolver.clientOf(projectId).map(p.clientOrgId()::equals).orElse(false);
            default -> true;
        };
        if (!allowed) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 밖 접근");
        }
    }

    private static UUID extractUuid(JoinPoint jp, String name) {
        var sig = (MethodSignature) jp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = jp.getArgs();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name) && args[i] instanceof UUID u) {
                return u;
            }
        }
        throw new IllegalStateException("@RequirePermission projectIdParam '" + name + "' (UUID) 을 찾을 수 없음: "
                + sig.toShortString());
    }
}
