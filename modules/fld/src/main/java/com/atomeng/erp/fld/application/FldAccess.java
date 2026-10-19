package com.atomeng.erp.fld.application;

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
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 범위 권한 (설계 05 A2, 8.2 ◐). 동기화 · 구역 API 는 경로에 projectId 가 없어 {@code @RequirePermission} 으로 역할을 본 뒤
 * 서비스가 회차 → 프로젝트를 찾아 여기서 범위를 확인한다(RequirePermissionAspect 와 같은 규칙, RPT · QAR 과 같은 방식).
 */
@Component
@EnableConfigurationProperties(FldProperties.class)
public class FldAccess {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public FldAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    public void requireProject(String permission, UUID projectId) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p == null) {
            return;   // 시스템 실행(리스너 · 배치) — 권한 검사는 웹 경계에서
        }
        PermissionScope scope = registry.scopeFor(p.roles(), permission)
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED, "권한 없음: " + permission));
        boolean allowed = switch (scope) {
            case PROJECT -> {
                ProjectScopeResolver r = scopes.getIfAvailable();
                yield r != null && p.employeeId() != null
                        && r.isMember(p.employeeId(), projectId, LocalDate.now(clock.withZone(SEOUL)));
            }
            case CLIENT -> false;
            default -> true;
        };
        if (!allowed) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 밖 접근");
        }
    }

    /** 이 권한이 현재 사용자에게 있는가(범위 무관) */
    public boolean has(String permission) {
        return AtomAuthentication.currentPrincipal()
                .map(p -> registry.scopeFor(p.roles(), permission).isPresent()).orElse(true);
    }

    /** PM(책임기술자) · ADMIN — 잠긴 구역 수정(BR-FLD-009) · 전체 충돌 해결(A2) */
    public boolean isPm() {
        return AtomAuthentication.currentPrincipal()
                .map(p -> p.roles().contains(Role.PM) || p.roles().contains(Role.ADMIN)).orElse(true);
    }
}
