package com.atomeng.erp.cst.adapter.in.event;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FldSurveyCompleted;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * FLD 조사 완료 구독 — {@code fld.survey.completed} → 그 프로젝트 WBS 중 milestone_event = 'fld.survey.completed' 인 노드에
 * 진척 100% (source EVENT) 1행(설계 08 진척 마일스톤 이벤트 반영). 해당 WBS 가 없으면 아무것도 하지 않는다.
 */
@Component
public class FldSurveyMilestoneListener {

    public static final String CONSUMER = "cst.fld-survey-milestone";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public FldSurveyMilestoneListener(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'fld.survey.completed'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        FldSurveyCompleted e = om.convertValue(env.payload(), FldSurveyCompleted.class);
        for (UUID wbs : jdbc.queryForList("SELECT id FROM cst.wbs_node WHERE project_id = ? AND milestone_event = ?",
                UUID.class, e.projectId(), FldSurveyCompleted.TYPE)) {
            jdbc.update("""
                    INSERT INTO cst.progress_record(id, project_id, wbs_id, as_of, pct_complete, source, source_event,
                                                    created_by)
                    VALUES (?, ?, ?, ?, 100, 'EVENT', ?, ?)""", UuidV7.next(), e.projectId(), wbs,
                    Date.valueOf(e.completedOn()), FldSurveyCompleted.TYPE + ":" + e.roundId(), CurrentActor.SYSTEM);
        }
    }
}
