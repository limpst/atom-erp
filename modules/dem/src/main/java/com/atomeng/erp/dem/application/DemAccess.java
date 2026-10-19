package com.atomeng.erp.dem.application;

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
 * 행 수준 범위(◐) 검사 — DEM 경로에는 projectId 가 없어 {@code @RequirePermission} 이 역할만 본다(보안 검토 2026-10-19).
 * 서비스가 일지 → 업무 패키지 → 프로젝트를 찾은 뒤 여기서 RequirePermissionAspect 와 같은 규칙으로 범위를 확인한다.
 * <ul>
 *   <li>ALL → 통과</li>
 *   <li>PROJECT → 기준일 투입 인원(core.project_member). 추가로 {@code alsoAllowed}(일지 작성자 · 지정 감리자)이면 통과</li>
 *   <li>CLIENT → 프로젝트 발주 고객사 = 토큰 고객사</li>
 *   <li>SELF · 그 외 → 거부</li>
 * </ul>
 */
@Component
public class DemAccess {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public DemAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    /**
     * @param alsoAllowed PROJECT 범위에서 투입 인원이 아니어도 허용할 직원 판정(예: 해당 패키지 감리자). null 가능.
     */
    public void requireProject(String permission, UUID projectId, java.util.function.Predicate<UUID> alsoAllowed) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p == null) {
            return;   // 시스템 실행(스케줄러 · 이벤트) — 권한 검사는 웹 경계에서
        }
        PermissionScope scope = registry.scopeFor(p.roles(), permission)
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED, "권한 없음: " + permission));
        ProjectScopeResolver r = scopes.getIfAvailable();
        boolean allowed = switch (scope) {
            case ALL -> true;
            case PROJECT -> p.employeeId() != null && ((r != null
                    && r.isMember(p.employeeId(), projectId, LocalDate.now(clock.withZone(SEOUL))))
                    || (alsoAllowed != null && alsoAllowed.test(p.employeeId())));
            case CLIENT -> p.clientOrgId() != null && r != null
                    && r.clientOf(projectId).map(p.clientOrgId()::equals).orElse(false);
            default -> false;
        };
        if (!allowed) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 밖 접근");
        }
    }
}
