package com.atomeng.erp.sal.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.sal.api.SalSubscriptionContracts.PtlServiceRequestSubmitted;
import com.atomeng.erp.sal.application.OpportunityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * SAL 구독 (설계 01 B6): {@code fac.inspection.due-approaching}(fac::api) → 점검 도래 리드(BR-SAL-007, TC-SAL-020),
 * {@code ptl.service-request.submitted}(스텁 계약 — PTL 미구현) → 포털 리드. core.processed_event 로 이벤트 단위 멱등,
 * 업무 키 단위 중복은 부분 유니크 인덱스(ON CONFLICT DO NOTHING)로 한 번 더 막는다.
 */
@Component
public class SalEventListener {

    public static final String DUE_CONSUMER = "sal.due-soon-lead";
    public static final String PTL_CONSUMER = "sal.portal-lead";

    private final OpportunityService opportunities;
    private final ObjectMapper om;

    public SalEventListener(OpportunityService opportunities, ObjectMapper om) {
        this.opportunities = opportunities;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'fac.inspection.due-approaching'")
    @IdempotentListener(consumer = DUE_CONSUMER)
    public void onDueApproaching(EventEnvelope env) {
        opportunities.onDueApproaching(om.convertValue(env.payload(), FacEvents.InspectionDueApproaching.class));
    }

    @EventListener(condition = "#env.eventType == 'ptl.service-request.submitted'")
    @IdempotentListener(consumer = PTL_CONSUMER)
    public void onServiceRequest(EventEnvelope env) {
        opportunities.onServiceRequest(om.convertValue(env.payload(), PtlServiceRequestSubmitted.class));
    }
}
