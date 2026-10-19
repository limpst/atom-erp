package com.atomeng.erp.fac.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fac.api.DemolitionCompletedContract;
import com.atomeng.erp.fac.application.DemolitionRecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * DEM 해체 완료 이벤트 dem.demolition.completed 구독 (설계 10 v0.3 B6). 계약 = fac::api {@link DemolitionCompletedContract}.
 * core.processed_event(consumer=fac.demolition-recorder)로 멱등.
 */
@Component
public class DemolitionCompletedListener {

    public static final String CONSUMER = "fac.demolition-recorder";

    private final DemolitionRecordingService demolitions;
    private final ObjectMapper om;

    public DemolitionCompletedListener(DemolitionRecordingService demolitions, ObjectMapper om) {
        this.demolitions = demolitions;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'dem.demolition.completed'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        demolitions.record(om.convertValue(env.payload(), DemolitionCompletedContract.class));
    }
}
