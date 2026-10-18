package com.atomeng.erp.fac.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fac.api.ReportPublishedContract;
import com.atomeng.erp.fac.application.GradeRecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * RPT 보고서 발행 이벤트 rpt.report.published 구독 (설계 02 v0.3 B6 정본, 스텁 계약 — RPT 모듈 미구현).
 * core.processed_event(consumer=fac.grade-recorder)로 멱등(TC-FAC-005).
 */
@Component
public class ReportPublishedListener {

    public static final String CONSUMER = "fac.grade-recorder";

    private final GradeRecordingService grades;
    private final ObjectMapper om;

    public ReportPublishedListener(GradeRecordingService grades, ObjectMapper om) {
        this.grades = grades;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'rpt.report.published'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        grades.record(om.convertValue(env.payload(), ReportPublishedContract.class));
    }
}
