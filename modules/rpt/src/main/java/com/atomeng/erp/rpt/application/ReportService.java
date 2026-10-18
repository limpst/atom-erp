package com.atomeng.erp.rpt.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.application.RevisionSupport.Ctx;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.ReportType;
import com.atomeng.erp.rpt.domain.RptCodes;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보고서 생성 · 조회 · 정정본(새 리비전) (FR-RPT-001 · 014 · 016, BR-RPT-008 · 010).
 * 템플릿 버전은 생성 시점에 유효한 DEPLOYED 버전으로 고정되고(BR-RPT-010, TC-RPT-010), 진행 중 보고서는 바뀌지 않는다.
 */
@Service
public class ReportService {

    public record CreateReport(UUID projectId, UUID facilityId, String reportType, String title, LocalDate inspectionDate,
                               String locale, String facilityClass, LocalDate dueDate, UUID templateVersionId) {}

    private final RevisionSupport sup;
    private final RptJdbc rpt;
    private final RptAccess access;
    private final RptProperties props;
    private final AuditLog audit;

    public ReportService(RevisionSupport sup, RptAccess access, RptProperties props, AuditLog audit) {
        this.sup = sup;
        this.rpt = sup.jdbc();
        this.access = access;
        this.props = props;
        this.audit = audit;
    }

    @Transactional
    public Map<String, Object> create(CreateReport c) {
        access.requireProject("rpt:report:create", c.projectId());
        ReportType type;
        try {
            type = ReportType.valueOf(c.reportType());
        } catch (IllegalArgumentException e) {
            throw new DomainException(RptCodes.INVALID, "보고서 유형 오류: " + c.reportType());
        }
        String locale = c.locale() == null ? "ko" : c.locale();
        UUID tv = c.templateVersionId() != null ? requireDeployed(c.templateVersionId())
                : effectiveTemplate(type, locale, sup.today());
        UUID id = UuidV7.next();
        UUID revId = UuidV7.next();
        UUID by = sup.actorId();
        try {
            rpt.jdbc().update("""
                    INSERT INTO rpt.report(id, project_id, facility_id, report_type, template_version_id, facility_class,
                                           locale, title, inspection_date, status, current_rev, due_date, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 1, ?, ?, ?)""",
                    id, c.projectId(), c.facilityId(), type.name(), tv,
                    c.facilityClass() == null ? props.facilityClass() : c.facilityClass(), locale, c.title(),
                    RptJdbc.d(c.inspectionDate()), RptJdbc.d(c.dueDate()), by, by);
        } catch (DataIntegrityViolationException e) {
            throw new DomainException(RptCodes.INVALID, "프로젝트가 없거나 입력이 제약을 어깁니다: " + c.projectId());
        }
        rpt.jdbc().update("INSERT INTO rpt.report_revision(id, report_id, rev_no, status, created_by, updated_by) "
                + "VALUES (?, ?, 1, 'DRAFT', ?, ?)", revId, id, by, by);
        audit.append("RPT", AuditLog.Action.CREATE, "Report", id, null,
                Map.of("projectId", c.projectId(), "type", type.name(), "templateVersionId", tv));
        return report(id);
    }

    private UUID requireDeployed(UUID templateVersionId) {
        List<Map<String, Object>> rows = rpt.jdbc().queryForList(
                "SELECT id FROM rpt.template_version WHERE id = ? AND status = 'DEPLOYED'", templateVersionId);
        if (rows.isEmpty()) {
            throw new DomainException(RptCodes.TEMPLATE_NOT_FOUND, "DEPLOYED 템플릿 버전이 아님: " + templateVersionId);
        }
        return templateVersionId;
    }

    /** 오늘 유효한 DEPLOYED 템플릿(유형 전용 우선, 없으면 RT_COMMON) */
    UUID effectiveTemplate(ReportType type, String locale, LocalDate today) {
        List<Map<String, Object>> rows = rpt.jdbc().queryForList("""
                SELECT tv.id FROM rpt.template_version tv JOIN rpt.template t ON t.id = tv.template_id
                 WHERE t.report_type IN (?, 'RT_COMMON') AND t.locale = ? AND tv.status = 'DEPLOYED'
                   AND tv.deleted_at IS NULL AND tv.effective_from <= ? AND (tv.effective_to IS NULL OR tv.effective_to > ?)
                 ORDER BY (t.report_type = ?) DESC, tv.effective_from DESC LIMIT 1""",
                type.name(), locale, RptJdbc.d(today), RptJdbc.d(today), type.name());
        if (rows.isEmpty()) {
            throw new DomainException(RptCodes.TEMPLATE_NOT_FOUND, "오늘 유효한 템플릿 버전이 없습니다: " + type);
        }
        return RptJdbc.uuid(rows.get(0), "id");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID reportId) {
        Map<String, Object> r = rpt.get("report", reportId);
        access.requireProject("rpt:report:read", RptJdbc.uuid(r, "project_id"));
        return report(reportId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID projectId) {
        access.requireProject("rpt:report:read", projectId);
        return rpt.jdbc().queryForList("SELECT id FROM rpt.report WHERE project_id = ? AND deleted_at IS NULL "
                + "ORDER BY created_at", projectId).stream().map(m -> report(RptJdbc.uuid(m, "id"))).toList();
    }

    Map<String, Object> report(UUID id) {
        Map<String, Object> r = rpt.get("report", id);
        Map<String, Object> tv = rpt.get("template_version", RptJdbc.uuid(r, "template_version_id"));
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("projectId", r.get("project_id"));
        v.put("facilityId", r.get("facility_id"));
        v.put("reportType", r.get("report_type"));
        v.put("title", r.get("title"));
        v.put("locale", r.get("locale"));
        v.put("facilityClass", r.get("facility_class"));
        v.put("inspectionDate", RptJdbc.localDate(r.get("inspection_date")));
        v.put("status", r.get("status"));
        v.put("currentRev", r.get("current_rev"));
        v.put("templateVersion", Map.of("id", tv.get("id"), "semver", tv.get("semver"),
                "effectiveFrom", RptJdbc.localDate(tv.get("effective_from")).toString()));
        v.put("version", RptJdbc.version(r));
        v.put("revisions", rpt.jdbc().queryForList("""
                SELECT id, rev_no AS "revNo", status, version, published_at AS "publishedAt", pdf_sha256 AS "pdfSha256",
                       superseded_by AS "supersededBy", delivered_at AS "deliveredAt"
                  FROM rpt.report_revision WHERE report_id = ? ORDER BY rev_no""", id));
        Map<String, Object> cur = rpt.jdbc().queryForMap(
                "SELECT id FROM rpt.report_revision WHERE report_id = ? AND rev_no = ?", id, r.get("current_rev"));
        v.put("currentRevisionId", cur.get("id"));
        return v;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> revision(UUID revId) {
        Ctx c = sup.read(revId, "rpt:report:read");
        return revisionView(c.revId());
    }

    Map<String, Object> revisionView(UUID revId) {
        Map<String, Object> r = rpt.get("report_revision", revId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", revId);
        v.put("reportId", r.get("report_id"));
        v.put("revNo", r.get("rev_no"));
        v.put("status", r.get("status"));
        v.put("snapshotId", r.get("snapshot_id"));
        v.put("changeNote", r.get("change_note"));
        v.put("contentHash", RptJdbc.str(r, "content_hash"));
        v.put("pdfSha256", RptJdbc.str(r, "pdf_sha256"));
        v.put("hwpxSha256", RptJdbc.str(r, "hwpx_sha256"));
        v.put("pdfObjectKey", r.get("pdf_object_key"));
        v.put("pageCount", r.get("page_count"));
        v.put("publishedAt", RptJdbc.instant(r.get("published_at")));
        v.put("deliveredAt", RptJdbc.instant(r.get("delivered_at")));
        v.put("supersededBy", r.get("superseded_by"));
        v.put("version", RptJdbc.version(r));
        List<Map<String, Object>> g = rpt.jdbc().queryForList("""
                SELECT computed_grade AS "computedGrade", final_grade AS "finalGrade", weighted_score AS "weightedScore"
                  FROM rpt.grade_result WHERE revision_id = ?""", revId);
        v.put("grade", g.isEmpty() ? null : g.get(0));
        return v;
    }

    /**
     * 정정본(Rev.N+1) 생성 (BR-RPT-008) — 현재 리비전이 PUBLISHED · DELIVERED 일 때만. reuseSnapshot 이면 이전 스냅샷을
     * 그대로 가리켜 SNAPSHOT_FROZEN 으로 시작(재동결도 가능). 이전 발행본은 새 리비전 발행 때 SUPERSEDED.
     */
    @Transactional
    public Map<String, Object> createCorrection(UUID reportId, String changeNote, boolean reuseSnapshot) {
        Map<String, Object> report = rpt.lock("report", reportId);
        access.requireProject("rpt:report:create", RptJdbc.uuid(report, "project_id"));
        Map<String, Object> cur = rpt.jdbc().queryForMap(
                "SELECT * FROM rpt.report_revision WHERE report_id = ? AND rev_no = ? FOR UPDATE",
                reportId, report.get("current_rev"));
        ReportStatus st = ReportStatus.valueOf(RptJdbc.str(cur, "status"));
        if (st != ReportStatus.PUBLISHED && st != ReportStatus.DELIVERED) {
            throw new DomainException(RptCodes.CORRECTION_NOT_ALLOWED,
                    "정정본은 발행 · 납품된 현재 리비전에서만 만들 수 있습니다(현재 " + st + ")");
        }
        int next = RptJdbc.integer(cur, "rev_no") + 1;
        UUID snap = reuseSnapshot ? RptJdbc.uuid(cur, "snapshot_id") : null;
        UUID revId = UuidV7.next();
        UUID by = sup.actorId();
        rpt.jdbc().update("""
                INSERT INTO rpt.report_revision(id, report_id, rev_no, snapshot_id, status, change_note, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", revId, reportId, next, snap,
                snap == null ? "DRAFT" : "SNAPSHOT_FROZEN", changeNote, by, by);
        rpt.jdbc().update("UPDATE rpt.report SET current_rev = ?, version = version + 1, updated_at = now(), "
                + "updated_by = ? WHERE id = ?", next, by, reportId);
        sup.syncReport(reportId);
        audit.append("RPT", AuditLog.Action.CREATE, "ReportRevision", revId, null,
                Map.of("reportId", reportId, "revNo", next, "changeNote", changeNote));
        return revisionView(revId);
    }
}
