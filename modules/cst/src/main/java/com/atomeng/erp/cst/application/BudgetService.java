package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.cst.domain.budget.BudgetApproval;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 실행예산 조회 · 결재 · 수익성 (FR-CST-001 · 012 일부). 전체 업무 흐름 시험에서 드러난 연결 누락:
 * <ul>
 *   <li>계약 서명 → Rev.0 DRAFT 는 생기지만 결재 API 가 없어 Baseline(APPROVED)이 생기지 않음 → EVM 404 · 수익성 읽기 모델 제외</li>
 *   <li>수익성 읽기 모델(mv_project_profitability)은 갱신 배치가 없어 HTTP 로 볼 수 없음 → 원장 기준 실시간 조회 제공</li>
 * </ul>
 * 결재 화면 · 다단계 결재(SUBMITTED → REVIEWED) · cst.budget.approved 이벤트는 후속(08-IMPL Step 2 · 5).
 */
@Service
public class BudgetService {

    public static final String BUDGET_STATE = "CST-409-005";

    private final JdbcTemplate jdbc;
    private final AuditLog audit;
    private final CurrentActor actor;

    public BudgetService(JdbcTemplate jdbc, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> revisions(UUID projectId) {
        return jdbc.queryForList("SELECT id FROM cst.budget_revision WHERE project_id = ? AND deleted_at IS NULL "
                + "ORDER BY rev_no, created_at", UUID.class, projectId).stream().map(this::view).toList();
    }

    private Map<String, Object> view(UUID revisionId) {
        Map<String, Object> r = jdbc.queryForMap("""
                SELECT id, project_id, rev_no, status, bac_krw, contract_amount_krw, expected_margin_pct, approved_by,
                       approved_at, created_by, submitted_by, submitted_at, version
                  FROM cst.budget_revision WHERE id = ?""", revisionId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("projectId", r.get("project_id"));
        m.put("revNo", r.get("rev_no"));
        m.put("status", r.get("status"));
        m.put("bacKrw", r.get("bac_krw"));
        m.put("contractAmountKrw", r.get("contract_amount_krw"));
        m.put("expectedMarginPct", r.get("expected_margin_pct"));
        m.put("approvedBy", r.get("approved_by"));
        m.put("approvedAt", r.get("approved_at"));
        m.put("createdBy", r.get("created_by"));
        m.put("submittedBy", r.get("submitted_by"));
        m.put("submittedAt", r.get("submitted_at"));
        m.put("version", r.get("version"));
        m.put("wbs", jdbc.queryForList("""
                SELECT w.id, w.code, w.name, w.weight_pct AS "weightPct", w.milestone_event AS "milestoneEvent",
                       coalesce(sum(l.amount_krw), 0) AS "budgetKrw"
                  FROM cst.wbs_node w LEFT JOIN cst.budget_line l ON l.wbs_id = w.id AND l.revision_id = ?
                 WHERE w.project_id = ? GROUP BY w.id ORDER BY w.sort_order, w.code""", revisionId, r.get("project_id")));
        return m;
    }

    /**
     * 실행예산 상신(If-Match) — DRAFT → SUBMITTED, 상신자 기록(CST-01). cst:budget:update(PM 은 투입 프로젝트, ADMIN · FIN 전체).
     * 라인 · BAC 가 비어 있으면 422. 상신자는 같은 판을 결재할 수 없다.
     */
    @Transactional
    public Map<String, Object> submit(UUID projectId, UUID revisionId, long expectedVersion) {
        Map<String, Object> r = lockRevision(projectId, revisionId, expectedVersion);
        if (!"DRAFT".equals(r.get("status"))) {
            throw new DomainException(BUDGET_STATE, "초안(DRAFT)만 상신할 수 있습니다: " + r.get("status"));
        }
        Integer lines = jdbc.queryForObject("SELECT count(*) FROM cst.budget_line WHERE revision_id = ?", Integer.class,
                revisionId);
        BudgetApproval.Rejection rej = BudgetApproval.check("SUBMITTED", lines == null ? 0 : lines,
                (BigDecimal) r.get("bac_krw"));
        if (rej != null) {
            throw new DomainException(CstErrors.INVALID, "실행예산 라인 · BAC 가 비어 있어 상신할 수 없습니다(" + rej + ")");
        }
        UUID by = actor.actorIdOrSystem();
        jdbc.update("""
                UPDATE cst.budget_revision SET status = 'SUBMITTED', submitted_by = ?, submitted_at = now(),
                       updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""", by, by, revisionId);
        audit.append("CST", AuditLog.Action.UPDATE, "budget_revision", revisionId, Map.of("status", "DRAFT"),
                Map.of("status", "SUBMITTED", "projectId", projectId.toString()));
        return view(revisionId);
    }

    private Map<String, Object> lockRevision(UUID projectId, UUID revisionId, long expectedVersion) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT project_id, status, version, bac_krw, created_by, updated_by, submitted_by FROM cst.budget_revision
                 WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", revisionId);
        if (rows.isEmpty() || !projectId.equals(rows.get(0).get("project_id"))) {
            throw new DomainException(CstErrors.NOT_FOUND, "프로젝트의 실행예산 판이 아닙니다: " + revisionId);
        }
        Map<String, Object> r = rows.get(0);
        if (((Number) r.get("version")).longValue() != expectedVersion) {
            throw new DomainException(CoreErrors.VERSION_MISMATCH, "실행예산이 그새 바뀌었습니다(If-Match 불일치)");
        }
        return r;
    }

    /**
     * 실행예산 결재(EXEC · ADMIN · FIN, If-Match) → APPROVED(Baseline 1개, 이전 APPROVED 는 SUPERSEDED) + 감사.
     * CST-01: SUBMITTED 만(DRAFT → 409 CST-409-005), 결재자 ≠ 작성 · 마지막 수정 · 상신자(403 CST-403-002).
     */
    @Transactional
    public Map<String, Object> approve(UUID projectId, UUID revisionId, long expectedVersion) {
        Map<String, Object> r = lockRevision(projectId, revisionId, expectedVersion);
        Integer lines = jdbc.queryForObject("SELECT count(*) FROM cst.budget_line WHERE revision_id = ?", Integer.class,
                revisionId);
        BudgetApproval.Rejection rej = BudgetApproval.check((String) r.get("status"), lines == null ? 0 : lines,
                (BigDecimal) r.get("bac_krw"));
        if (rej == BudgetApproval.Rejection.NOT_APPROVABLE_STATUS) {
            throw new DomainException(BUDGET_STATE, "결재할 수 없는 상태입니다: " + r.get("status"));
        }
        if (rej != null) {
            throw new DomainException(CstErrors.INVALID, "실행예산 라인 · BAC 가 비어 있어 결재할 수 없습니다(" + rej + ")");
        }
        UUID by = actor.actorIdOrSystem();
        for (String col : List.of("created_by", "updated_by", "submitted_by")) {
            if (by.equals(r.get(col))) {
                throw new DomainException(CstErrors.BUDGET_SAME_APPROVER,
                        "실행예산을 작성 · 수정 · 상신한 사람은 같은 판을 결재할 수 없습니다(maker-checker)")
                        .with("reason", "CST_BUDGET_SOD").with("field", col);
            }
        }
        jdbc.update("""
                UPDATE cst.budget_revision SET status = 'SUPERSEDED', updated_at = now(), updated_by = ?, version = version + 1
                 WHERE project_id = ? AND status = 'APPROVED' AND id <> ?""", by, projectId, revisionId);
        jdbc.update("""
                UPDATE cst.budget_revision SET status = 'APPROVED', approved_by = ?, approved_at = now(), updated_at = now(),
                       updated_by = ?, version = version + 1 WHERE id = ?""", by, by, revisionId);
        audit.append("CST", AuditLog.Action.APPROVE, "budget_revision", revisionId, Map.of("status", r.get("status")),
                Map.of("status", "APPROVED", "projectId", projectId.toString(), "bacKrw", r.get("bac_krw").toString()));
        return view(revisionId);
    }

    /**
     * 프로젝트 수익성(원장 기준 실시간) — 계약금액 · BAC(Baseline) · 실적원가(AC) · 원가요소별 · 원천별 · 인·일 · 이익.
     * realCost=false(PM 등 실단가 열람 권한 없음)면 표준단가 기준 금액으로 계산한다(TC-CST-11 마스킹 원칙).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> profitability(UUID projectId, boolean realCost) {
        Map<String, Object> p = jdbc.queryForList("""
                SELECT p.id, p.project_no, br.contract_amount_krw, br.bac_krw, br.rev_no
                  FROM core.project p
                  LEFT JOIN LATERAL (SELECT r.contract_amount_krw, r.bac_krw, r.rev_no FROM cst.budget_revision r
                                      WHERE r.project_id = p.id AND r.status IN ('APPROVED','CLOSED') AND r.deleted_at IS NULL
                                      ORDER BY r.rev_no DESC LIMIT 1) br ON true
                 WHERE p.id = ? AND p.deleted_at IS NULL""", projectId).stream().findFirst()
                .orElseThrow(() -> new DomainException(CstErrors.NOT_FOUND, "프로젝트 없음: " + projectId));
        String col = realCost ? "amount_krw" : "amount_std_krw";
        Map<String, Object> byElement = new LinkedHashMap<>();
        jdbc.query("SELECT cost_element::text AS k, sum(" + col + ") AS v FROM cst.cost_ledger_entry WHERE project_id = ? "
                + "GROUP BY 1 ORDER BY 1", rs -> {
                    byElement.put(rs.getString("k"), rs.getBigDecimal("v"));
                }, projectId);
        Map<String, Object> bySource = new LinkedHashMap<>();
        jdbc.query("SELECT source_type AS k, sum(" + col + ") AS v FROM cst.cost_ledger_entry WHERE project_id = ? "
                + "GROUP BY 1 ORDER BY 1", rs -> {
                    bySource.put(rs.getString("k"), rs.getBigDecimal("v"));
                }, projectId);
        Map<String, Object> tot = jdbc.queryForMap("""
                SELECT coalesce(sum(%s), 0) AS ac, coalesce(sum(qty) FILTER (WHERE cost_element = 'LABOR'), 0) AS mandays
                  FROM cst.cost_ledger_entry WHERE project_id = ?""".formatted(col), projectId);
        BigDecimal contract = (BigDecimal) p.get("contract_amount_krw");
        BigDecimal ac = (BigDecimal) tot.get("ac");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("projectId", projectId);
        out.put("projectNo", p.get("project_no"));
        out.put("basis", realCost ? "REAL" : "STANDARD");
        out.put("budgetRevNo", p.get("rev_no"));
        out.put("contractAmountKrw", contract);
        out.put("bacKrw", p.get("bac_krw"));
        out.put("acKrw", ac);
        out.put("byElement", byElement);
        out.put("bySource", bySource);
        out.put("mandays", tot.get("mandays"));
        out.put("marginKrw", contract == null ? null : contract.subtract(ac));
        out.put("marginPct", contract == null || contract.signum() == 0 ? null
                : contract.subtract(ac).multiply(BigDecimal.valueOf(100)).divide(contract, 2, RoundingMode.HALF_UP));
        return out;
    }
}
