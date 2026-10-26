package com.atomeng.erp.dem.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 웹 화면용 DEM 목록 조회(읽기 전용) — 프로젝트 목록 · 패키지별 감리일지 · 도서 · 트랜스미털.
 * 단건 조회와 같은 권한 · 범위 규칙을 쓴다(감리일지 = dem:supervision-log:read 프로젝트 범위 + 패키지 감리자).
 */
@Service
public class DemQueryService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DemJdbc dem;
    private final DemProjectService projects;
    private final StructuralService structural;
    private final SupervisorService supervisors;
    private final DemAccess access;
    private final Clock clock;

    public DemQueryService(DemJdbc dem, DemProjectService projects, StructuralService structural,
                           SupervisorService supervisors, DemAccess access, Clock clock) {
        this.dem = dem;
        this.projects = projects;
        this.structural = structural;
        this.supervisors = supervisors;
        this.access = access;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> projects() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : dem.jdbc().queryForList("SELECT project_id FROM dem.project ORDER BY created_at DESC, project_id",
                UUID.class)) {
            if (access.allows("dem:project:read", id)) {   // DEM-02: PROJECT 범위는 투입 프로젝트만
                out.add(projects.view(id));
            }
        }
        return out;
    }

    private UUID projectOfPackage(UUID wpId) {
        return dem.jdbc().queryForList("SELECT project_id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL",
                UUID.class, wpId).stream().findFirst()
                .orElseThrow(() -> new DomainException(DemErrors.NOT_FOUND, "업무 패키지 없음: " + wpId));
    }

    /** 패키지의 감리일지 요약(최신 날짜 먼저). 잠금 여부 · 내용 해시 · 정정 건수 포함 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> dailyLogs(UUID wpId) {
        UUID projectId = projectOfPackage(wpId);
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        access.requireProject("dem:supervision-log:read", projectId,
                emp -> supervisors.isActiveSupervisor(wpId, emp, today));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : dem.jdbc().queryForList("""
                SELECT l.id, l.log_date, l.author_id, l.phase_code, l.progress_pct, l.workers_count, l.status,
                       l.late_sync, l.locked_at, l.content_sha256, l.version,
                       (SELECT count(*) FROM dem.log_amendment a WHERE a.daily_log_id = l.id) AS amendments,
                       (SELECT count(*) FROM dem.log_amendment a WHERE a.daily_log_id = l.id AND a.status = 'PENDING'
                           AND NOT EXISTS (SELECT 1 FROM dem.log_amendment_decision d
                                            WHERE d.amendment_id = a.id)) AS pending_amendments
                  FROM dem.daily_log l
                 WHERE l.work_package_id = ? AND l.deleted_at IS NULL
                 ORDER BY l.log_date DESC, l.author_id""", wpId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("workPackageId", wpId);
            m.put("logDate", DemJdbc.ld(r.get("log_date")).toString());
            m.put("authorId", r.get("author_id"));
            m.put("phaseCode", r.get("phase_code"));
            m.put("progressPct", r.get("progress_pct"));
            m.put("workersCount", r.get("workers_count"));
            m.put("status", r.get("status"));
            m.put("locked", "LOCKED".equals(r.get("status")));
            m.put("lateSync", r.get("late_sync"));
            var lockedAt = DemJdbc.inst(r.get("locked_at"));
            m.put("lockedAt", lockedAt == null ? null : lockedAt.toString());
            m.put("contentSha256", r.get("content_sha256"));
            m.put("amendments", ((Number) r.get("amendments")).intValue());
            m.put("pendingAmendments", ((Number) r.get("pending_amendments")).intValue());
            m.put("version", ((Number) r.get("version")).longValue());
            out.add(m);
        }
        return out;
    }

    /** 패키지의 도서(리비전 · 미결 의견 수 포함) — 도서번호 순 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> documents(UUID wpId) {
        access.requirePackage("dem:revision:read", wpId, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (UUID id : dem.jdbc().queryForList(
                "SELECT id FROM dem.document WHERE work_package_id = ? ORDER BY doc_no, id", UUID.class, wpId)) {
            out.add(structural.document(id));
        }
        return out;
    }

    /** 패키지의 트랜스미털(최신 먼저) + 포함 리비전 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> transmittals(UUID wpId) {
        access.requirePackage("dem:revision:read", wpId, false);
        List<Map<String, Object>> out = new ArrayList<>();
        for (var t : dem.jdbc().queryForList("""
                SELECT id, transmittal_no, purpose, recipient_name, issued_at, issued_by
                  FROM dem.transmittal WHERE work_package_id = ? ORDER BY issued_at DESC, transmittal_no DESC""", wpId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.get("id"));
            m.put("transmittalNo", t.get("transmittal_no"));
            m.put("purpose", t.get("purpose"));
            m.put("recipientName", t.get("recipient_name"));
            m.put("issuedAt", String.valueOf(DemJdbc.inst(t.get("issued_at"))));
            m.put("issuedBy", t.get("issued_by"));
            List<Map<String, Object>> revs = new ArrayList<>();
            for (var r : dem.jdbc().queryForList("""
                    SELECT r.id, d.doc_no, r.rev_code, r.issued_rev FROM dem.transmittal_item i
                      JOIN dem.document_revision r ON r.id = i.revision_id JOIN dem.document d ON d.id = r.document_id
                     WHERE i.transmittal_id = ? ORDER BY d.doc_no""", t.get("id"))) {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("revisionId", r.get("id"));
                x.put("docNo", r.get("doc_no"));
                x.put("revCode", r.get("rev_code"));
                x.put("issuedRev", r.get("issued_rev"));
                revs.add(x);
            }
            m.put("revisions", revs);
            out.add(m);
        }
        return out;
    }
}
