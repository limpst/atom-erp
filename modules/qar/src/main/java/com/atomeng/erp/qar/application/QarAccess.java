package com.atomeng.erp.qar.application;

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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 프로젝트 범위 권한 (NFR-QAR-05 ABAC). QAR API 경로에는 projectId 가 없어 {@code @RequirePermission} 으로 역할을 본 뒤
 * 서비스가 검토 → 프로젝트를 찾아 범위를 확인한다(RptAccess 와 같은 규칙: PROJECT = 투입 인원, ALL = 통과).
 */
@Component
public class QarAccess {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public QarAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    public void requireProject(String permission, UUID projectId) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p == null) {
            return;   // 이벤트 · 스케줄러(시스템 실행)
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

    public Set<Role> roles() {
        return AtomAuthentication.currentPrincipal().map(AtomPrincipal::roles).orElse(Set.of());
    }

    public boolean has(Role r) {
        return roles().contains(r);
    }
}
