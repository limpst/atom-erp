package com.atomeng.erp.cst.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.cst.application.LaborPostingService;
import com.atomeng.erp.cst.application.ReconciliationService;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * HRQ 이벤트 구독 (설계 08 B6 · 03 B6). payload 는 hrq::api 레코드(HrqEvents)로 역직렬화 — 계약 테스트 CstHrqContractTest.
 * 멱등 1차: core.processed_event(consumer, eventId) — {@link IdempotentListener}. 업무 키 멱등은 서비스에서.
 */
@Component
public class HrqTimesheetListener {

    public static final String CONFIRMED_CONSUMER = "cst.labor-poster";
    public static final String CORRECTED_CONSUMER = "cst.labor-corrector";
    public static final String CLOSED_CONSUMER = "cst.manday-reconciler";

    private final LaborPostingService labor;
    private final ReconciliationService reconciliation;
    private final ObjectMapper om;

    public HrqTimesheetListener(LaborPostingService labor, ReconciliationService reconciliation, ObjectMapper om) {
        this.labor = labor;
        this.reconciliation = reconciliation;
        this.om = om;
    }

    /** hrq.timesheet.confirmed — 인건비 원가 전기의 유일한 원천(BR-HRQ-013) */
    @EventListener(condition = "#env.eventType == 'hrq.timesheet.confirmed'")
    @IdempotentListener(consumer = CONFIRMED_CONSUMER)
    public void onConfirmed(EventEnvelope env) {
        labor.onConfirmed(om.convertValue(env.payload(), HrqEvents.TimesheetConfirmed.class));
    }

    /** hrq.timesheet.corrected — 역분개 + 재전기 (BR-CST-015). 원 행 미도착 시 예외 → outbox 재시도 */
    @EventListener(condition = "#env.eventType == 'hrq.timesheet.corrected'")
    @IdempotentListener(consumer = CORRECTED_CONSUMER)
    public void onCorrected(EventEnvelope env) {
        labor.onCorrected(om.convertValue(env.payload(), HrqEvents.TimesheetCorrected.class));
    }

    /** hrq.manday.closed — 대사·잠금 확인 전용, 원장 기록 금지(BR-HRQ-013) */
    @EventListener(condition = "#env.eventType == 'hrq.manday.closed'")
    @IdempotentListener(consumer = CLOSED_CONSUMER)
    public void onMandayClosed(EventEnvelope env) {
        reconciliation.onMandayClosed(env.eventId(), om.convertValue(env.payload(), HrqEvents.MandayClosed.class));
    }
}
