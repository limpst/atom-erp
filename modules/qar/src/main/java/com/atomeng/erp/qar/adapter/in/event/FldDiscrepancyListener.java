package com.atomeng.erp.qar.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.qar.api.QarSubscriptionContracts.FldAiDiscrepancy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * QAR 구독: {@code fld.ai-suggestion.discrepancy} → qar.fld_ai_discrepancy(검토 샘플링 · 강조 대상, A8-8).
 * core.processed_event 이벤트 단위 멱등 + 제안 id UNIQUE.
 */
@Component
public class FldDiscrepancyListener {

    public static final String CONSUMER = "qar.fld-ai-discrepancy";

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public FldDiscrepancyListener(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'fld.ai-suggestion.discrepancy'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        FldAiDiscrepancy e = om.convertValue(env.payload(), FldAiDiscrepancy.class);
        jdbc.update("""
                INSERT INTO qar.fld_ai_discrepancy(suggestion_id, defect_id, round_id, project_id, defect_code,
                                                   human_width_mm, ai_width_mm, ratio)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (suggestion_id) DO NOTHING""", e.suggestionId(),
                e.defectId(), e.roundId(), e.projectId(), e.defectCode(), e.humanWidthMm(), e.aiWidthMm(), e.ratio());
    }
}
