package com.atomeng.erp.aip.application;

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
 * 프로젝트 범위 권한(◐) — AIP 경로에는 projectId 가 없거나 본문에 있어 {@code @RequirePermission} 으로 역할을 본 뒤
 * 서비스가 Job · 제안 → 프로젝트를 찾아 범위를 확인한다(QAR · RPT 와 같은 규칙: PROJECT = 투입 인원, ALL = 통과).
 * 프로젝트 없는 Job(예: 지식 검색)은 PROJECT 범위 역할도 만들 수 있지만, 조회 · 취소 · 제안 결정은 요청자 본인만 한다.
 */
@Component
public class AipAccess {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    public AipAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
    }

    public void requireProject(String permission, UUID projectId) {
        requireProject(permission, projectId, null);
    }

    /**
     * 기존 Job · 제안 접근. 프로젝트 없는 Job 은 PROJECT 범위 역할이면 요청자 본인만(SEC-2026-10-19) — ALL 범위는 통과.
     *
     * @param ownerId Job 요청자(aip.job.requested_by). null 이면 생성 시점 검사(본인이 만드는 것)로 본다.
     */
    public void requireProject(String permission, UUID projectId, UUID ownerId) {
        if (!allows(permission, projectId, ownerId)) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "프로젝트 범위 밖 접근");
        }
    }

    /**
     * {@link #requireProject(String, UUID, UUID)} 와 같은 판정을 예외 없이 — 목록 조회에서 범위 밖 행을 거를 때 쓴다.
     * 권한 자체가 없으면 거부(403 CORE-403-001) 예외를 그대로 낸다.
     */
    public boolean allows(String permission, UUID projectId, UUID ownerId) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p == null) {
            return true;   // 시스템 실행(스케줄러 · 이벤트 · 모듈 간 동기 호출)
        }
        PermissionScope scope = registry.scopeFor(p.roles(), permission)
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED, "권한 없음: " + permission));
        return switch (scope) {
            case PROJECT -> {
                if (projectId == null) {
                    yield ownerId == null || ownerId.equals(p.actorId());
                }
                ProjectScopeResolver r = scopes.getIfAvailable();
                yield r != null && p.employeeId() != null
                        && r.isMember(p.employeeId(), projectId, LocalDate.now(clock.withZone(SEOUL)));
            }
            case CLIENT, SELF -> false;
            default -> true;
        };
    }
}
