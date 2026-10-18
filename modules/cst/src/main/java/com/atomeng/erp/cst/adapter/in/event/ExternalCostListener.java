package com.atomeng.erp.cst.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.EqpEquipmentReturned;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FcdExpensePosted;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FcdExpenseReversed;
import com.atomeng.erp.cst.application.ExternalCostService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** FCD(12)·EQP(04) 구독 스텁 — 상대 모듈 미구현, 계약은 cst::api CstSubscriptionContracts */
@Component
public class ExternalCostListener {

    private final ExternalCostService service;
    private final ObjectMapper om;

    public ExternalCostListener(ExternalCostService service, ObjectMapper om) {
        this.service = service;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'fcd.expense.posted'")
    @IdempotentListener(consumer = "cst.fcd-expense-poster")
    public void onExpensePosted(EventEnvelope env) {
        service.onExpensePosted(om.convertValue(env.payload(), FcdExpensePosted.class));
    }

    @EventListener(condition = "#env.eventType == 'fcd.expense.reversed'")
    @IdempotentListener(consumer = "cst.fcd-expense-reverser")
    public void onExpenseReversed(EventEnvelope env) {
        service.onExpenseReversed(om.convertValue(env.payload(), FcdExpenseReversed.class));
    }

    @EventListener(condition = "#env.eventType == 'eqp.equipment.returned'")
    @IdempotentListener(consumer = "cst.eqp-usage")
    public void onEquipmentReturned(EventEnvelope env) {
        service.onEquipmentReturned(om.convertValue(env.payload(), EqpEquipmentReturned.class));
    }
}
