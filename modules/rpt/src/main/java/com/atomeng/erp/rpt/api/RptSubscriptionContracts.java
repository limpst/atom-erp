package com.atomeng.erp.rpt.api;

import java.util.UUID;

/**
 * RPT 구독 계약(스텁) — QAR 모듈 미구현이라 RPT 쪽에 payload 를 고정한다(설계 06 B6 구독).
 * {@code qar.review.approved} → IN_REVIEW → APPROVED, {@code qar.review.rejected} → COMPOSING.
 */
public final class RptSubscriptionContracts {

    private RptSubscriptionContracts() {}

    public record QarReviewDecided(UUID reviewId, UUID revisionId, UUID decidedBy, String comment) {
        public static final String APPROVED = "qar.review.approved";
        public static final String REJECTED = "qar.review.rejected";
    }
}
