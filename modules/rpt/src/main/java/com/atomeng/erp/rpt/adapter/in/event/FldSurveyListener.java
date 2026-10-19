package com.atomeng.erp.rpt.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.rpt.api.RptSubscriptionContracts.FldSurveyCompleted;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * RPT 구독 (설계 05 v0.2 B6.2): {@code fld.survey.completed} → rpt.field_survey_completion(보고서 작성 가능 신호).
 * core.processed_event 로 이벤트 단위 멱등, 같은 회차 재수신은 무시(ON CONFLICT).
 */
@Component
public class FldSurveyListener {

    public static final String CONSUMER = "rpt.fld-survey";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public FldSurveyListener(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'fld.survey.completed'")
    @IdempotentListener(consumer = CONSUMER)
    public void onCompleted(EventEnvelope env) {
        FldSurveyCompleted e = om.convertValue(env.payload(), FldSurveyCompleted.class);
        jdbc.update("""
                INSERT INTO rpt.field_survey_completion(round_id, project_id, building_id, round_no, survey_type, zone_count,
                                                        defect_count, last_seq, completed_on)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (round_id) DO NOTHING""", e.roundId(), e.projectId(),
                e.buildingId(), e.roundNo(), e.surveyType(), e.zoneCount(), e.defectCount(), e.lastSeq(),
                Date.valueOf(e.completedOn()));
    }
}
