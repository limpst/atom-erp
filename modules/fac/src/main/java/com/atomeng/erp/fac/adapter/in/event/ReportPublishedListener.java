package com.atomeng.erp.fac.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fac.api.ReportPublishedContract;
import com.atomeng.erp.fac.application.GradeRecordingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * RPT 보고서 발행 이벤트 구독 (스텁 계약 — RPT 모듈 미구현). 정본명 rpt.report.published(설계 02 v0.3 B6),
 * 조정 요청명 rpt.revision.published 둘 다 수신. core.processed_event(consumer=fac.grade-recorder)로 멱등(TC-FAC-005).
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

    @EventListener(condition = "#env.eventType == 'rpt.report.published' or #env.eventType == 'rpt.revision.published'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        grades.record(om.convertValue(env.payload(), ReportPublishedContract.class));
    }
}
