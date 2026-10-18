package com.atomeng.erp.rpt.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.rpt.api.RptSubscriptionContracts.QarReviewDecided;
import com.atomeng.erp.rpt.application.PublishService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * RPT 구독 (설계 06 B6): {@code qar.review.approved} / {@code qar.review.rejected}(스텁 계약 — QAR 미구현).
 * core.processed_event 로 이벤트 단위 멱등.
 */
@Component
public class QarReviewListener {

    public static final String CONSUMER = "rpt.review-decision";

    private final PublishService publish;
    private final ObjectMapper om;

    public QarReviewListener(PublishService publish, ObjectMapper om) {
        this.publish = publish;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'qar.review.approved'")
    @IdempotentListener(consumer = CONSUMER)
    public void onApproved(EventEnvelope env) {
        publish.onReviewDecided(om.convertValue(env.payload(), QarReviewDecided.class), true);
    }

    @EventListener(condition = "#env.eventType == 'qar.review.rejected'")
    @IdempotentListener(consumer = CONSUMER)
    public void onRejected(EventEnvelope env) {
        publish.onReviewDecided(om.convertValue(env.payload(), QarReviewDecided.class), false);
    }
}
