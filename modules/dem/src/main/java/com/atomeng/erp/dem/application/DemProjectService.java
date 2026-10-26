package com.atomeng.erp.dem.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.atomeng.erp.dem.api.DemEvents;
import com.atomeng.erp.dem.domain.DemStates.ProjectStatus;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEM 프로젝트 · 업무 패키지 (FR-DEM-001 수동 경로, A6.1 상태 전이, BR-DEM-001, 설계 v0.3 해체 완료 · 종료 이벤트).
 * <ul>
 *   <li>DEM 프로젝트 = core.project 1:1 확장(dem.project) + 해체 대상 fac.building</li>
 *   <li>IN_PROGRESS(착공) 전이: 감리 패키지가 있으면 오늘 유효한 책임감리자 + 감리자 공백 없음(BR-DEM-001)</li>
 *   <li>해체 완료 신고 → COMPLETING + {@code dem.demolition.completed}(buildingId — FAC 멸실 처리)</li>
 *   <li>종료 → CLOSED + {@code dem.project.closed}</li>
 * </ul>
 */
@Service
public class DemProjectService {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 감리 패키지(BR-DEM-001 대상) */
    public static final Set<String> SUPERVISION_PACKAGES = Set.of("DEM_SUPERVISION", "STR_SUPERVISION");

    public record PackageSpec(String packageType, UUID facilityId, String locale, String designCode, String currency,
                              BigDecimal fxRateSnapshot, LocalDate plannedStart, LocalDate plannedEnd) {}

    public record Create(UUID projectId, UUID buildingId, List<PackageSpec> packages) {}

    public record Submission(String authorityName, String submissionType, Instant submittedAt, String receiptNo,
                             String status, List<UUID> bundleAttachmentIds) {}

    private final DemJdbc dem;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;

    private final DemAccess access;

    public DemProjectService(DemJdbc dem, OutboxPublisher outbox, AuditLog audit, CurrentActor actor, Clock clock,
                             DemAccess access) {
        this.access = access;
        this.dem = dem;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    @Transactional
    public Map<String, Object> create(Create c) {
        UUID by = actor.actorIdOrSystem();
        if (c.packages() == null || c.packages().isEmpty()) {
            throw new DomainException(DemErrors.INVALID, "업무 패키지가 1개 이상 필요합니다");
        }
        Integer projects = dem.jdbc().queryForObject("SELECT count(*) FROM core.project WHERE id = ?", Integer.class,
                c.projectId());
        if (projects == null || projects == 0) {
            throw new DomainException(DemErrors.NOT_FOUND, "프로젝트 없음: " + c.projectId());
        }
        access.requireProject("dem:project:create", c.projectId(), null);   // DEM-02: PM 은 투입 프로젝트만
        if (c.buildingId() != null) {
            requireBuilding(c.buildingId());
        }
        int inserted = dem.jdbc().update("""
                INSERT INTO dem.project(project_id, building_id, created_by, updated_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id) DO NOTHING""", c.projectId(), c.buildingId(), by, by);
        if (inserted == 0) {
            throw new DomainException(DemErrors.INVALID, "이미 DEM 프로젝트로 등록된 프로젝트입니다: " + c.projectId())
                    .with("reason", "DEM_PROJECT_EXISTS");
        }
        for (PackageSpec p : c.packages()) {
            UUID facility = p.facilityId() != null ? p.facilityId() : c.buildingId();
            if (facility != null && p.facilityId() != null) {
                requireBuilding(facility);
            }
            UUID id = UuidV7.next();
            dem.jdbc().update("""
                    INSERT INTO dem.work_package(id, project_id, facility_id, package_type, locale, design_code, currency,
                                                 fx_rate_snapshot, planned_start, planned_end, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, c.projectId(), facility, p.packageType(),
                    p.locale() == null ? "ko" : p.locale(), p.designCode(), p.currency() == null ? "KRW" : p.currency(),
                    p.fxRateSnapshot(), DemJdbc.d(p.plannedStart()), DemJdbc.d(p.plannedEnd()), by, by);
            outbox.publish(new DemEvents.WorkPackageCreated(id, c.projectId(), p.packageType(), facility));
        }
        audit.append("DEM", AuditLog.Action.CREATE, "dem-project", c.projectId(), null,
                Map.of("buildingId", String.valueOf(c.buildingId()), "packages",
                        c.packages().stream().map(PackageSpec::packageType).toList()));
        return view(c.projectId());
    }

    private void requireBuilding(UUID buildingId) {
        List<String> st = dem.jdbc().queryForList("SELECT status FROM fac.building WHERE id = ? AND deleted_at IS NULL",
                String.class, buildingId);
        if (st.isEmpty()) {
            throw new DomainException(DemErrors.NOT_FOUND, "시설물 없음: " + buildingId);
        }
    }

    public Map<String, Object> view(UUID projectId) {
        Map<String, Object> p = dem.one("DEM 프로젝트", """
                SELECT project_id, building_id, status, completed_on, completion_submission_id, closed_at, version
                  FROM dem.project WHERE project_id = ?""", projectId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("projectId", p.get("project_id"));
        m.put("buildingId", p.get("building_id"));
        m.put("status", p.get("status"));
        m.put("completedOn", DemJdbc.ld(p.get("completed_on")));
        m.put("completionSubmissionId", p.get("completion_submission_id"));
        m.put("closedAt", DemJdbc.inst(p.get("closed_at")));
        m.put("version", p.get("version"));
        List<Map<String, Object>> pkgs = new ArrayList<>();
        for (var w : dem.jdbc().queryForList("""
                SELECT id, package_type, facility_id, status, locale, currency, supervisor_gap, version
                  FROM dem.work_package WHERE project_id = ? AND deleted_at IS NULL ORDER BY package_type""", projectId)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", w.get("id"));
            x.put("packageType", w.get("package_type"));
            x.put("facilityId", w.get("facility_id"));
            x.put("status", w.get("status"));
            x.put("locale", w.get("locale"));
            x.put("currency", w.get("currency"));
            x.put("supervisorGap", w.get("supervisor_gap"));
            pkgs.add(x);
        }
        m.put("workPackages", pkgs);
        return m;
    }

    private Map<String, Object> lock(UUID projectId) {
        return dem.one("DEM 프로젝트", "SELECT status, building_id, version FROM dem.project WHERE project_id = ? FOR UPDATE",
                projectId);
    }

    /** A6.1 일반 전이(PLANNING · REVIEWING · PERMIT_PENDING · IN_PROGRESS · SUSPENDED). 완료 · 종료는 전용 명령 */
    @Transactional
    public Map<String, Object> transition(UUID projectId, String target, Long expectedVersion) {
        ProjectStatus to = parse(target);
        if (to == ProjectStatus.COMPLETING || to == ProjectStatus.CLOSED || to == ProjectStatus.DRAFT) {
            throw new DomainException(DemErrors.INVALID, to + " 전이는 전용 명령(:complete-demolition · :close)으로만 합니다");
        }
        var p = lock(projectId);
        if (expectedVersion != null) {
            IfMatch.check(expectedVersion, ((Number) p.get("version")).longValue());
        }
        ProjectStatus from = ProjectStatus.valueOf((String) p.get("status"));
        from.assertCanMoveTo(to);
        if (to == ProjectStatus.IN_PROGRESS) {
            assertSupervisorsReady(projectId, today());
        }
        update(projectId, to, null, null);
        audit.append("DEM", AuditLog.Action.UPDATE, "dem-project", projectId, Map.of("status", from.name()),
                Map.of("status", to.name()));
        return view(projectId);
    }

    /** BR-DEM-001: 감리 패키지마다 기준일 유효 책임감리자(ACTIVE) 1명 + 감리자 공백 플래그 없음 */
    void assertSupervisorsReady(UUID projectId, LocalDate on) {
        var missing = dem.jdbc().queryForList("""
                SELECT w.id, w.package_type, w.supervisor_gap FROM dem.work_package w
                 WHERE w.project_id = ? AND w.deleted_at IS NULL AND w.package_type IN ('DEM_SUPERVISION','STR_SUPERVISION')
                   AND (w.supervisor_gap OR NOT EXISTS (
                        SELECT 1 FROM dem.supervisor_assignment a
                         WHERE a.work_package_id = w.id AND a.role = 'CHIEF' AND a.status = 'ACTIVE' AND a.deleted_at IS NULL
                           AND a.valid_from <= ? AND (a.valid_to IS NULL OR a.valid_to >= ?)))""",
                projectId, DemJdbc.d(on), DemJdbc.d(on));
        if (!missing.isEmpty()) {
            throw new DomainException(DemErrors.SUPERVISOR_REQUIRED,
                    "감리 패키지에 유효한 책임감리자가 없어 착공(IN_PROGRESS)할 수 없습니다(BR-DEM-001)")
                    .with("reason", "DEM_SUPERVISOR_REQUIRED")
                    .with("workPackageIds", missing.stream().map(r -> r.get("id")).toList());
        }
    }

    /** 해체 완료 신고 → COMPLETING + dem.demolition.completed (설계 v0.3, 구독자 FAC) */
    @Transactional
    public Map<String, Object> completeDemolition(UUID projectId, LocalDate completedOn, UUID submissionId) {
        var p = lock(projectId);
        ProjectStatus from = ProjectStatus.valueOf((String) p.get("status"));
        from.assertCanMoveTo(ProjectStatus.COMPLETING);
        UUID buildingId = (UUID) p.get("building_id");
        if (buildingId == null) {
            throw new DomainException(DemErrors.INVALID, "해체 대상 건축물(buildingId)이 연결되지 않은 프로젝트입니다")
                    .with("reason", "DEM_BUILDING_REQUIRED");
        }
        LocalDate on = completedOn == null ? today() : completedOn;
        if (on.isAfter(today())) {
            throw new DomainException(DemErrors.INVALID, "해체 완료일은 미래일 수 없습니다: " + on);
        }
        if (submissionId != null) {
            Integer n = dem.jdbc().queryForObject("SELECT count(*) FROM dem.submission WHERE id = ? AND project_id = ?",
                    Integer.class, submissionId, projectId);
            if (n == null || n == 0) {
                throw new DomainException(DemErrors.NOT_FOUND, "이 프로젝트의 제출 기록 없음: " + submissionId);
            }
        }
        update(projectId, ProjectStatus.COMPLETING, on, submissionId);
        UUID wp = primaryPackage(projectId);
        outbox.publish(new DemEvents.DemolitionCompleted(projectId, wp, buildingId, on, submissionId));
        audit.append("DEM", AuditLog.Action.UPDATE, "dem-project", projectId, Map.of("status", from.name()),
                Map.of("status", "COMPLETING", "completedOn", on.toString(), "buildingId", buildingId.toString()));
        return view(projectId);
    }

    /** 이벤트의 workPackageId: 해체 감리 패키지 우선, 없으면 첫 해체 패키지, 없으면 첫 패키지 */
    private UUID primaryPackage(UUID projectId) {
        List<UUID> ids = dem.jdbc().queryForList("""
                SELECT id FROM dem.work_package WHERE project_id = ? AND deleted_at IS NULL
                 ORDER BY CASE package_type WHEN 'DEM_SUPERVISION' THEN 0 WHEN 'DEM_PLAN' THEN 1 WHEN 'DEM_SAFETY' THEN 2
                          WHEN 'DEM_TEMP' THEN 3 ELSE 9 END, id LIMIT 1""", UUID.class, projectId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** 완료보고서 발행 · 제출 → CLOSED + dem.project.closed */
    @Transactional
    public Map<String, Object> close(UUID projectId) {
        var p = lock(projectId);
        ProjectStatus from = ProjectStatus.valueOf((String) p.get("status"));
        from.assertCanMoveTo(ProjectStatus.CLOSED);
        update(projectId, ProjectStatus.CLOSED, null, null);
        dem.jdbc().update("UPDATE dem.project SET closed_at = ? WHERE project_id = ?", Timestamp.from(clock.instant()),
                projectId);
        LocalDate on = today();
        outbox.publish(new DemEvents.ProjectClosed(projectId, (UUID) p.get("building_id"), on));
        audit.append("DEM", AuditLog.Action.UPDATE, "dem-project", projectId, Map.of("status", from.name()),
                Map.of("status", "CLOSED"));
        return view(projectId);
    }

    private void update(UUID projectId, ProjectStatus to, LocalDate completedOn, UUID submissionId) {
        dem.jdbc().update("""
                UPDATE dem.project SET status = ?, completed_on = coalesce(?, completed_on),
                       completion_submission_id = coalesce(?, completion_submission_id),
                       updated_at = now(), updated_by = ?, version = version + 1
                 WHERE project_id = ?""", to.name(), DemJdbc.d(completedOn), submissionId, actor.actorIdOrSystem(),
                projectId);
    }

    private static ProjectStatus parse(String s) {
        try {
            return ProjectStatus.valueOf(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(DemErrors.INVALID, "알 수 없는 상태: " + s);
        }
    }

    /** 지자체 제출 기록(FR-DEM-011) — 같은 프로젝트 · 제출 유형 안에서 차수 자동 증가 */
    @Transactional
    public Map<String, Object> submit(UUID projectId, Submission s) {
        lock(projectId);
        UUID by = actor.actorIdOrSystem();
        Integer seq = dem.jdbc().queryForObject("""
                SELECT coalesce(max(seq), 0) + 1 FROM dem.submission WHERE project_id = ? AND submission_type = ?""",
                Integer.class, projectId, s.submissionType());
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.submission(id, project_id, authority_name, submission_type, seq, submitted_at, receipt_no,
                                           status, bundle_attachment_ids, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::uuid[], ?)""", id, projectId, s.authorityName(), s.submissionType(),
                seq, Timestamp.from(s.submittedAt() == null ? clock.instant() : s.submittedAt()), s.receiptNo(),
                s.status() == null ? "SUBMITTED" : s.status(), DemJdbc.uuidArray(s.bundleAttachmentIds()), by);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("projectId", projectId);
        m.put("submissionType", s.submissionType());
        m.put("seq", seq);
        m.put("status", s.status() == null ? "SUBMITTED" : s.status());
        return m;
    }
}
