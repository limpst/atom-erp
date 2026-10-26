package com.atomeng.erp.eqp.application;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.Role;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.ProjectScopeResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * EQP 행 수준 범위 검사 (보안 검토 EQP-01 · EQP-03 · EQP-04). 예약 · 반출 요청 본문의 projectId 는
 * {@code @RequirePermission(projectIdParam)} 로 읽을 수 없어 컨트롤러는 {@code scopeCheckedInService = true} 이고, 서비스가 여기서
 * RequirePermissionAspect 와 같은 규칙(ALL 통과 · PROJECT = 기준일 투입 인원 · 그 밖 거부)으로 확인한다.
 * 인증 주체가 없으면(스케줄러 · 이벤트 · 서비스 직접 호출) 검사하지 않는다 — 권한 검사는 웹 경계에서.
 */
@Component
public class EqpAccess {

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public EqpAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    /** 현재 웹 요청 주체(없으면 시스템 실행) */
    public Optional<AtomPrincipal> principal() {
        return AtomAuthentication.currentPrincipal();
    }

    /** permission 의 범위가 ALL 이거나, PROJECT 이면 호출자가 기준일에 projectId 에 투입되어 있어야 한다 */
    public void requireProject(String permission, UUID projectId) {
        AtomPrincipal p = principal().orElse(null);
        if (p == null) {
            return;
        }
        if (!allowed(p, permission, projectId)) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE,
                    "프로젝트 범위 밖 접근(" + permission + ") — 해당 프로젝트에 투입된 인원만 가능합니다");
        }
    }

    /** {@link #requireProject} 와 같은 판정을 예외 없이 */
    public boolean allowed(AtomPrincipal p, String permission, UUID projectId) {
        PermissionScope scope = registry.scopeFor(p.roles(), permission).orElse(null);
        if (scope == null) {
            return false;
        }
        return switch (scope) {
            case ALL -> true;
            case PROJECT -> projectId != null && p.employeeId() != null && member(p.employeeId(), projectId);
            default -> false;
        };
    }

    public boolean member(UUID employeeId, UUID projectId) {
        ProjectScopeResolver r = scopes.getIfAvailable();
        return r != null && r.isMember(employeeId, projectId, LocalDate.now(clock.withZone(EquipmentService.SEOUL)));
    }

    public boolean hasRole(Role role) {
        return principal().map(p -> p.has(role)).orElse(false);
    }
}
