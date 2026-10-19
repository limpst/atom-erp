package com.atomeng.erp.rpt.application;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.ProjectScopeResolver;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 범위 권한 (설계 06 NFR-RPT-06 ABAC, 8.2 ◐). 리비전 API 는 경로에 projectId 가 없어
 * {@code @RequirePermission} 으로 역할을 본 뒤, 서비스가 리비전 → 보고서 → 프로젝트를 찾아 여기서 범위를 확인한다
 * (RequirePermissionAspect 와 같은 규칙: PROJECT = 투입 인원, CLIENT = 발주처 소속, ALL/SELF = 통과).
 */
@Component
public class RptAccess {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public RptAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    /**
     * 호출자의 이 권한 범위가 CLIENT(포털 고객) 인가. SEC-RPT-02: 고객에게는 발행(PUBLISHED · DELIVERED · SUPERSEDED)
     * 리비전만 보여야 한다 — 초안 · 검토 중 렌더 · 등급 결정 근거 · 편집자 정보는 내부 자료.
     */
    public boolean isClientScope(String permission) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p == null) {
            return false;
        }
        return registry.scopeFor(p.roles(), permission).map(s -> s == PermissionScope.CLIENT).orElse(false);
    }

    public void requireProject(String permission, UUID projectId) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p == null) {
            return;   // 이벤트 리스너 · 배치(시스템 실행) — 권한 검사는 웹 경계에서
        }
        PermissionScope scope = registry.scopeFor(p.roles(), permission)
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED, "권한 없음: " + permission));
        boolean allowed = switch (scope) {
            case PROJECT -> {
                ProjectScopeResolver r = scopes.getIfAvailable();
                yield r != null && p.employeeId() != null
                        && r.isMember(p.employeeId(), projectId, LocalDate.now(clock.withZone(SEOUL)));
            }
            case CLIENT -> {
                ProjectScopeResolver r = scopes.getIfAvailable();
                yield r != null && p.clientOrgId() != null
                        && r.clientOf(projectId).map(p.clientOrgId()::equals).orElse(false);
            }
            default -> true;
        };
        if (!allowed) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 밖 접근");
        }
    }
}
