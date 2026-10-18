package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토 생성 · 조회 (FR-QAR-001 · 002). 보고서당 검토 1건(UNIQUE report_id) — 반려 재제출 · 정정본은 같은 검토의 새 라운드.
 * 워크플로우 정의는 보고서 유형으로 선택(applies_to.reportTypes, 없으면 WF_PSD_STANDARD).
 */
@Service
public class ReviewService {

    private final ReviewSupport sup;
    private final QarJdbc qar;
    private final AssignmentService assignments;

    public ReviewService(ReviewSupport sup, AssignmentService assignments) {
        this.sup = sup;
        this.qar = sup.jdbc();
        this.assignments = assignments;
    }

    @Transactional
    public Map<String, Object> create(UUID revisionId) {
        RevisionInfo info = sup.revision(revisionId);
        sup.access().requireProject("qar:author:create", info.projectId());
        List<UUID> existing = qar.jdbc().queryForList("SELECT id FROM qar.review WHERE report_id = ?", UUID.class,
                info.reportId());
        if (!existing.isEmpty()) {
            throw new DomainException(QarCodes.DUPLICATE_REVIEW, "이 보고서의 검토가 이미 있습니다 — 새 라운드로 진행하세요")
                    .with("reviewId", existing.get(0));
        }
        UUID wf = workflowFor(info.reportType());
        UUID id = UuidV7.next();
        UUID by = sup.actorId();
        qar.jdbc().update("""
                INSERT INTO qar.review(id, report_id, project_id, report_type, workflow_def_id, status, current_round,
                                       created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'SELF_CHECK', 1, ?, ?)""", id, info.reportId(), info.projectId(), info.reportType(),
                wf, by, by);
        insertRound(id, 1, info, "CREATE");
        sup.audit().append("QAR", AuditLog.Action.CREATE, "Review", id, null,
                Map.of("reportId", info.reportId(), "revisionId", revisionId, "status", "SELF_CHECK"));
        return view(id);
    }

    void insertRound(UUID reviewId, int roundNo, RevisionInfo info, String openedBy) {
        qar.jdbc().update("""
                INSERT INTO qar.review_round(id, review_id, round_no, revision_id, rev_no, opened_by_cmd)
                VALUES (?, ?, ?, ?, ?, ?)""", UuidV7.next(), reviewId, roundNo, info.revisionId(), info.revNo(), openedBy);
    }

    private UUID workflowFor(String reportType) {
        List<UUID> ids = qar.jdbc().queryForList("""
                SELECT id FROM qar.workflow_def
                 WHERE status = 'DEPLOYED' AND (applies_to -> 'reportTypes') @> jsonb_build_array(?::text) AND effective_from <= ?
                   AND (effective_to IS NULL OR effective_to > ?) ORDER BY version DESC LIMIT 1""", UUID.class,
                reportType, QarJdbc.d(sup.today()), QarJdbc.d(sup.today()));
        if (!ids.isEmpty()) {
            return ids.get(0);
        }
        return qar.jdbc().queryForObject("SELECT id FROM qar.workflow_def WHERE code = 'WF_PSD_STANDARD' "
                + "AND status = 'DEPLOYED' ORDER BY version DESC LIMIT 1", UUID.class);
    }

    /** 단계 SLA(시간) — 워크플로우 정의 */
    Integer slaHours(Map<String, Object> review, String step) {
        return qar.jdbc().queryForList("SELECT sla_hours FROM qar.workflow_step_def WHERE workflow_def_id = ? "
                + "AND step_code = ?", Integer.class, review.get("workflow_def_id"), step).stream().findFirst().orElse(null);
    }

    public UUID reviewIdOfRound(UUID roundId) {
        return QarJdbc.uuid(sup.round(roundId), "review_id");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID reviewId) {
        sup.reviewFor(reviewId, "qar:review:read", false);
        return view(reviewId);
    }

    Map<String, Object> view(UUID reviewId) {
        Map<String, Object> r = qar.get("review", reviewId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", reviewId);
        v.put("reportId", r.get("report_id"));
        v.put("projectId", r.get("project_id"));
        v.put("reportType", r.get("report_type"));
        v.put("workflow", qar.jdbc().queryForObject("SELECT code || ' v' || version FROM qar.workflow_def WHERE id = ?",
                String.class, r.get("workflow_def_id")));
        v.put("status", r.get("status"));
        v.put("currentRound", r.get("current_round"));
        v.put("approvedAt", QarJdbc.instant(r.get("approved_at")));
        v.put("approvedBy", r.get("approved_by"));
        v.put("version", QarJdbc.version(r));
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (Map<String, Object> x : qar.jdbc().queryForList(
                "SELECT * FROM qar.review_round WHERE review_id = ? ORDER BY round_no", reviewId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.get("id"));
            m.put("roundNo", x.get("round_no"));
            m.put("revisionId", x.get("revision_id"));
            m.put("revNo", x.get("rev_no"));
            m.put("openedBy", x.get("opened_by_cmd"));
            m.put("pdfSha256", QarJdbc.str(x, "pdf_sha256"));
            m.put("contentHash", QarJdbc.str(x, "content_hash"));
            m.put("ruleVersions", QarJdbc.tree(x.get("rule_versions")));
            m.put("submittedAt", QarJdbc.instant(x.get("submitted_at")));
            m.put("submittedBy", x.get("submitted_by"));
            m.put("aiStatus", x.get("ai_status"));
            m.put("aiSkipReason", x.get("ai_skip_reason"));
            m.put("aiBadge", "SKIPPED".equals(x.get("ai_status")) ? "AI 검토 미수행" : null);
            m.put("outcome", x.get("outcome"));
            m.put("rejectSummary", x.get("reject_summary"));
            m.put("closedAt", QarJdbc.instant(x.get("closed_at")));
            m.put("assignments", assignments.assignments(QarJdbc.uuid(x, "id")));
            rounds.add(m);
        }
        v.put("rounds", rounds);
        v.put("round", rounds.isEmpty() ? null : rounds.get(rounds.size() - 1));
        return v;
    }
}
