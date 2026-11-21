package com.atomeng.erp.cst.application;

import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 웹 "내 할 일" — 내가 확정할 수 있는 입금 ↔ 청구 배분 제안(읽기 전용, docs/CST_BILLING_COLLECTION_DESIGN.md §10).
 * 실패하는 쪽으로 닫힘: PENDING 만 · 제안자 본인 제외(두 사람 규칙, DB ck_proposal_4eyes 와 같은 기준) · 직원 ID 없는 호출자는 빈 목록.
 * 권한 cst:allocation:confirm 은 EXEC · ADMIN 모두 ALL 범위라 프로젝트 범위 거름이 필요 없다.
 */
@Service
public class AllocationInboxService {

    private final JdbcTemplate jdbc;

    public AllocationInboxService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> pendingProposals() {
        UUID me = AtomAuthentication.currentPrincipal().map(AtomPrincipal::employeeId).orElse(null);
        if (me == null) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT p.id AS "proposalId", p.invoice_id AS "invoiceId", i.invoice_no AS "invoiceNo", i.project_id AS "projectId",
                       c.name AS "clientName", p.receipt_id AS "receiptId", r.value_date AS "valueDate", r.amount_krw AS "receiptKrw",
                       p.amount_krw AS "amountKrw", p.match_score AS "matchScore", p.proposed_by AS "proposedBy",
                       p.proposed_at AS "requestedAt"
                  FROM cst.allocation_proposal p
                  JOIN cst.invoice i ON i.id = p.invoice_id
                  JOIN core.client c ON c.id = i.client_id
                  JOIN cst.bank_receipt r ON r.id = p.receipt_id
                 WHERE p.status = 'PENDING' AND p.proposed_by <> ?
                 ORDER BY p.proposed_at""", me);
    }
}
