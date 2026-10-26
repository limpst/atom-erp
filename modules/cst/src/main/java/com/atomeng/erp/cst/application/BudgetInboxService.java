package com.atomeng.erp.cst.application;

import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.erp.cst.domain.budget.BudgetApproval;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 웹 "내 할 일" — 내가 결재할 수 있는 실행예산 판(읽기 전용). 실패하는 쪽으로 닫힘:
 * <ul>
 *   <li>상태는 도메인 판정 {@link BudgetApproval#APPROVABLE} 과 같은 집합(결재 규칙이 바뀌면 함께 바뀐다 — 보안 검토 CST-01)</li>
 *   <li>작성자(created_by) · 마지막 수정자(updated_by)가 호출자면 제외(maker-checker)</li>
 *   <li>라인이 없거나 BAC 0 인 판은 결재가 거부되므로 제외 · 직원 ID 없는 호출자는 빈 목록</li>
 * </ul>
 * 권한 cst:budget:approve 는 EXEC · ADMIN · FIN 모두 ALL 범위라 프로젝트 범위 거름이 필요 없다.
 */
@Service
public class BudgetInboxService {

    private final JdbcTemplate jdbc;

    public BudgetInboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> pendingApprovals() {
        UUID me = AtomAuthentication.currentPrincipal().map(AtomPrincipal::employeeId).orElse(null);
        if (me == null) {
            return List.of();
        }
        String statuses = BudgetApproval.APPROVABLE.stream().sorted().collect(Collectors.joining(",", "{", "}"));
        return jdbc.queryForList("""
                SELECT b.id AS "revisionId", b.project_id AS "projectId", b.rev_no AS "revNo", b.status,
                       b.bac_krw AS "bacKrw", b.contract_amount_krw AS "contractAmountKrw",
                       b.expected_margin_pct AS "expectedMarginPct", b.created_by AS "createdBy", b.submitted_by AS "submittedBy",
                       coalesce(b.updated_at, b.created_at) AS "requestedAt", b.version
                  FROM cst.budget_revision b
                 WHERE b.deleted_at IS NULL AND b.status = ANY(?::text[])
                   AND b.created_by <> ? AND (b.updated_by IS NULL OR b.updated_by <> ?)
                   AND (b.submitted_by IS NULL OR b.submitted_by <> ?)
                   AND b.bac_krw > 0 AND EXISTS (SELECT 1 FROM cst.budget_line l WHERE l.revision_id = b.id)
                 ORDER BY coalesce(b.updated_at, b.created_at)""", statuses, me, me, me);
    }
}
