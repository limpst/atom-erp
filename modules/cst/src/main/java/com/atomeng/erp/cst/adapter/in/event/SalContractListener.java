package com.atomeng.erp.cst.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.SalContractSigned;
import com.atomeng.erp.cst.application.BudgetDraftService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * SAL(01) 계약 체결 구독 — {@code sal.contract.signed} → 실행예산 Rev.0 초안(FR-CST-001, TC-CST-01).
 * 계약은 cst::api CstSubscriptionContracts.SalContractSigned (SAL 발행 레코드와 JSON 계약 테스트). CST 는 sal 모듈에 의존하지 않는다.
 */
@Component
public class SalContractListener {

    public static final String CONSUMER = "cst.budget-drafter";

    private final BudgetDraftService budgets;
    private final ObjectMapper om;

    public SalContractListener(BudgetDraftService budgets, ObjectMapper om) {
        this.budgets = budgets;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'sal.contract.signed'")
    @IdempotentListener(consumer = CONSUMER)
    public void onContractSigned(EventEnvelope env) {
        budgets.draftFromContract(om.convertValue(env.payload(), SalContractSigned.class));
    }
}
