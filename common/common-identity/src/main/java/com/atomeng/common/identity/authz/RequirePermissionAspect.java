package com.atomeng.common.identity.authz;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.authz.ScopeResolver.ScopeTarget;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.annotation.Order;

/**
 * {@link RequirePermission} 검사 (00-IMPL Step 4, 보안 검토 CMN-05 로 실패하는 쪽으로 닫음).
 * <ul>
 *   <li>인증 없음 → 401 CORE-401-001</li>
 *   <li>역할에 권한 없음 → 403 CORE-403-001</li>
 *   <li>scope=ALL → 통과</li>
 *   <li>scope=PROJECT/CLIENT/SELF:
 *     <ul>
 *       <li>{@code projectIdParam} → 투입(PROJECT) · 발주 고객(CLIENT) 확인. SELF 는 프로젝트로 판정할 수 없어 거부</li>
 *       <li>{@code scopeResolver} → 자원을 판정 대상으로 해석해 PROJECT = 투입(프로젝트 없으면 소유자 본인),
 *           CLIENT = 소유 고객사, SELF = 소유 직원 본인. 자원이 없으면 404 CORE-404-001</li>
 *       <li>{@code scopeCheckedInService=true} → 역할만 보고 통과(서비스가 행을 읽은 뒤 검사)</li>
 *       <li><b>그 밖에는 403 CORE-403-002</b> — 예전에는 조용히 ALL 로 넓어졌다(CMN-05)</li>
 *     </ul>
 *   </li>
 * </ul>
 * 메서드 실행 전에 거부하므로 업무 로직·감사 로그는 실행되지 않는다.
 */
@Aspect
@Order(10)
public class RequirePermissionAspect {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final BeanFactory beans;
    private final Clock clock;

    public RequirePermissionAspect(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes,
                                   BeanFactory beans, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.beans = beans;
        this.clock = clock;
    }

    @Before("@annotation(rp)")
    public void check(JoinPoint jp, RequirePermission rp) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal()
                .orElseThrow(() -> new DomainException(CoreErrors.UNAUTHENTICATED, "인증이 필요합니다"));
        PermissionScope scope = registry.scopeFor(p.roles(), rp.value())
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED,
                        "권한 없음: " + rp.value()));
        if (scope == PermissionScope.ALL) {
            return;
        }
        boolean allowed;
        if (!rp.projectIdParam().isEmpty()) {
            allowed = scope != PermissionScope.SELF
                    && allowed(p, scope, ScopeTarget.project(extractUuid(jp, rp.projectIdParam())));
        } else if (rp.scopeResolver() != ScopeResolver.None.class) {
            Object key = extractArg(jp, rp.resourceParam());
            ScopeTarget target = beans.getBean(rp.scopeResolver()).resolve(key)
                    .orElseThrow(() -> new DomainException(CoreErrors.NOT_FOUND, "대상을 찾을 수 없습니다"));
            allowed = allowed(p, scope, target);
        } else if (rp.scopeCheckedInService()) {
            return;
        } else {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE,
                    "범위(" + scope + ")를 판정할 수 없는 엔드포인트입니다: " + rp.value());
        }
        if (!allowed) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 밖 접근");
        }
    }

    private boolean allowed(AtomPrincipal p, PermissionScope scope, ScopeTarget t) {
        return switch (scope) {
            case PROJECT -> {
                if (p.employeeId() == null) {
                    yield false;
                }
                if (t.projectId() == null) {
                    yield t.ownerEmployeeId() != null && t.ownerEmployeeId().equals(p.employeeId());
                }
                ProjectScopeResolver r = resolver();
                yield r.isMember(p.employeeId(), t.projectId(), LocalDate.now(clock.withZone(SEOUL)));
            }
            case CLIENT -> {
                if (p.clientOrgId() == null) {
                    yield false;
                }
                UUID client = t.clientOrgId() != null ? t.clientOrgId()
                        : t.projectId() == null ? null : resolver().clientOf(t.projectId()).orElse(null);
                yield p.clientOrgId().equals(client);
            }
            case SELF -> p.employeeId() != null && Objects.equals(t.ownerEmployeeId(), p.employeeId());
            case ALL -> true;
        };
    }

    private ProjectScopeResolver resolver() {
        ProjectScopeResolver r = scopes.getIfAvailable();
        if (r == null) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 판정기가 없습니다");
        }
        return r;
    }

    private static UUID extractUuid(JoinPoint jp, String name) {
        if (extractArg(jp, name) instanceof UUID u) {
            return u;
        }
        throw new IllegalStateException("@RequirePermission projectIdParam '" + name + "' (UUID) 을 찾을 수 없음: "
                + jp.getSignature().toShortString());
    }

    private static Object extractArg(JoinPoint jp, String name) {
        var sig = (MethodSignature) jp.getSignature();
        String[] names = sig.getParameterNames();
        Object[] args = jp.getArgs();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(name)) {
                if (args[i] == null) {
                    throw new DomainException(CoreErrors.VALIDATION_FAILED, name + " 가 필요합니다");
                }
                return args[i];
            }
        }
        throw new IllegalStateException("@RequirePermission 파라미터 '" + name + "' 을 찾을 수 없음: "
                + sig.toShortString());
    }
}
