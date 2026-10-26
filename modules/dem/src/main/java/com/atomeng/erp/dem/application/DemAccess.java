package com.atomeng.erp.dem.application;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.ProjectScopeResolver;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
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
 * 보안 검토 2026-10-19 DEM-02: V0047 로 PM · ENG 의 DEM 세부 권한이 PROJECT 범위가 되어, 업무 패키지 · 도서 · 리비전 · 의견 ·
 * 감리자 지정 · 증거 사진 경로도 여기서 프로젝트를 찾아 검사한다({@link #requirePackage} 등).
 */
@Component
public class DemAccess {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final PermissionRegistry registry;
    private final ObjectProvider<ProjectScopeResolver> scopes;
    private final Clock clock;

    private final DemJdbc dem;

    public DemAccess(PermissionRegistry registry, ObjectProvider<ProjectScopeResolver> scopes, Clock clock, DemJdbc dem) {
        this.registry = registry;
        this.scopes = scopes;
        this.clock = clock;
        this.dem = dem;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    /** 업무 패키지 → 프로젝트(없으면 404) */
    public UUID projectOfPackage(UUID wpId) {
        return dem.jdbc().queryForList("SELECT project_id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL",
                UUID.class, wpId).stream().findFirst()
                .orElseThrow(() -> new DomainException(DemErrors.NOT_FOUND, "업무 패키지 없음: " + wpId));
    }

    /** 오늘 기준 업무 패키지 활성 감리자인가 */
    public boolean isActiveSupervisor(UUID wpId, UUID employeeId) {
        LocalDate on = today();
        Integer n = dem.jdbc().queryForObject("""
                SELECT count(*) FROM dem.supervisor_assignment
                 WHERE work_package_id = ? AND employee_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)""", Integer.class,
                wpId, employeeId, DemJdbc.d(on), DemJdbc.d(on));
        return n != null && n > 0;
    }

    /**
     * 업무 패키지 범위 검사 → 프로젝트 id. allowSupervisor 면 PROJECT 범위에서 투입 인원이 아니어도 그 패키지 활성 감리자는 통과
     * (현장 감리자의 증거 사진 · 감리자 목록 조회).
     */
    public UUID requirePackage(String permission, UUID wpId, boolean allowSupervisor) {
        UUID projectId = projectOfPackage(wpId);
        requireProject(permission, projectId, allowSupervisor ? emp -> isActiveSupervisor(wpId, emp) : null);
        return projectId;
    }

    /** 도서 → 업무 패키지 범위 검사 */
    public UUID requireDocument(String permission, UUID documentId) {
        UUID wp = dem.jdbc().queryForList("SELECT work_package_id FROM dem.document WHERE id = ?", UUID.class, documentId)
                .stream().findFirst().orElseThrow(() -> new DomainException(DemErrors.NOT_FOUND, "도서 없음: " + documentId));
        return requirePackage(permission, wp, false);
    }

    /** 리비전 → 도서 → 업무 패키지 범위 검사 */
    public UUID requireRevision(String permission, UUID revisionId) {
        UUID wp = dem.jdbc().queryForList("""
                SELECT d.work_package_id FROM dem.document_revision r JOIN dem.document d ON d.id = r.document_id
                 WHERE r.id = ?""", UUID.class, revisionId).stream().findFirst()
                .orElseThrow(() -> new DomainException(DemErrors.NOT_FOUND, "리비전 없음: " + revisionId));
        return requirePackage(permission, wp, false);
    }

    /** 검토의견 → 리비전 범위 검사 */
    public UUID requireComment(String permission, UUID commentId) {
        UUID rev = dem.jdbc().queryForList("SELECT revision_id FROM dem.review_comment WHERE id = ?", UUID.class,
                commentId).stream().findFirst()
                .orElseThrow(() -> new DomainException(DemErrors.NOT_FOUND, "검토의견 없음: " + commentId));
        return requireRevision(permission, rev);
    }

    /** 호출자가 이 권한을 쓸 수 있는 프로젝트인가(예외 없이) — 목록 필터 */
    public boolean allows(String permission, UUID projectId) {
        try {
            requireProject(permission, projectId, null);
            return true;
        } catch (DomainException e) {
            return false;
        }
    }

    /** 호출자가 이 권한을 (범위 무관하게) 가졌는가 — 검토자 판정(DEM-03 d) */
    public boolean hasPermission(String permission) {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        return p != null && registry.scopeFor(p.roles(), permission).isPresent();
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
