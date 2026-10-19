package com.atomeng.erp.fld.application;

import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import com.atomeng.erp.fld.api.FldEvents;
import com.atomeng.erp.fld.domain.DefectState;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * A8-8 · 05-IMPL Step 14: AI 균열폭과 사람 확정 균열폭(장비 · 게이지 · 육안 — AI 방법 제외)의 비율이 기준(2배) 이상이면
 * {@code fld.ai-suggestion.discrepancy}(QAR 샘플링 검토)를 제안당 1회만 발행한다(ai_suggestion.discrepancy_at).
 * 호출자 트랜잭션 안에서 부른다(outbox MANDATORY).
 */
@Component
public class AiDiscrepancyCheck {

    private final FldJdbc fld;
    private final OutboxPublisher outbox;
    private final FldProperties props;
    private final Clock clock;

    public AiDiscrepancyCheck(FldJdbc fld, OutboxPublisher outbox, FldProperties props, Clock clock) {
        this.fld = fld;
        this.outbox = outbox;
        this.props = props;
        this.clock = clock;
    }

    /** @return 발행했으면 비율, 아니면 null */
    public BigDecimal check(DefectState state, UUID suggestionId, UUID projectId) {
        List<Map<String, Object>> rows = fld.jdbc().queryForList(
                "SELECT width_mm, discrepancy_at FROM fld.ai_suggestion WHERE id = ? FOR UPDATE", suggestionId);
        if (rows.isEmpty() || rows.get(0).get("discrepancy_at") != null || rows.get(0).get("width_mm") == null) {
            return null;
        }
        BigDecimal ai = (BigDecimal) rows.get(0).get("width_mm");
        if (!state.attrs().hasNonNull("width_mm")
                || "AI".equals(state.measureMeta().path("width_mm").path("method").asText(null))) {
            return null;
        }
        BigDecimal human = state.attrs().get("width_mm").decimalValue();
        if (human.signum() <= 0 || ai.signum() <= 0) {
            return null;
        }
        BigDecimal hi = human.max(ai);
        BigDecimal lo = human.min(ai);
        BigDecimal ratio = hi.divide(lo, 3, RoundingMode.HALF_UP);
        if (ratio.compareTo(props.ai().discrepancyRatio()) < 0) {
            return null;
        }
        fld.jdbc().update("UPDATE fld.ai_suggestion SET discrepancy_at = ? WHERE id = ?", Timestamp.from(clock.instant()),
                suggestionId);
        outbox.publish(new FldEvents.AiSuggestionDiscrepancy(suggestionId, state.id(), state.roundId(), projectId,
                state.code(), human, ai, ratio));
        return ratio;
    }
}
