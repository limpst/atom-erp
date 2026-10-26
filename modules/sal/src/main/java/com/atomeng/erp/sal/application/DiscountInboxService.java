package com.atomeng.erp.sal.application;

import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 웹 "내 할 일" — 내가 결정해야 할 할인 승인 대기 견적(PENDING_APPROVAL), 읽기 전용.
 * 실패하는 쪽으로 닫힘: 견적 작성자(created_by) · 마지막 수정 · 발행자(updated_by)가 호출자면 제외(보안 검토 SAL-06 권장안 —
 * 다중 역할 SALES+EXEC 본인 승인 금지). 직원 ID 가 없는 호출자는 빈 목록.
 */
@Service
public class DiscountInboxService {

    private final SalJdbc sal;

    public DiscountInboxService(SalJdbc sal) {
        this.sal = sal;
    }

    public List<Map<String, Object>> pendingApprovals() {
        UUID me = AtomAuthentication.currentPrincipal().map(AtomPrincipal::employeeId).orElse(null);
        if (me == null) {
            return List.of();
        }
        return sal.jdbc().queryForList("""
                SELECT e.id AS "estimateId", e.estimate_no AS "estimateNo", e.version_no AS "versionNo",
                       e.opportunity_id AS "opportunityId", o.opp_no AS "oppNo", o.title AS "opportunityTitle",
                       e.discount_rate AS "discountRate", e.discount_limit AS "discountLimit", e.subtotal,
                       e.offer_amount AS "offerAmount", e.created_by AS "createdBy", e.updated_at AS "requestedAt",
                       e.version
                  FROM sal.estimate e JOIN sal.opportunity o ON o.id = e.opportunity_id
                 WHERE e.status = 'PENDING_APPROVAL' AND e.deleted_at IS NULL AND o.deleted_at IS NULL
                   AND e.created_by <> ? AND e.updated_by <> ?
                 ORDER BY e.updated_at""", me, me);
    }
}
