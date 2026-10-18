package com.atomeng.erp.rpt.api;

import java.util.UUID;

/**
 * RPT 구독 계약 — RPT 가 읽는 최소 필드를 RPT 쪽에 고정한다(설계 06 B6 구독). 생산자는 QAR 모듈(07)
 * {@code QarEvents.ReviewApproved/ReviewRejected} — 설계 07 B6 필드를 더 갖지만 이 4개 필드는 항상 포함(QarRptContractTest).
 * {@code qar.review.approved} → IN_REVIEW → APPROVED, {@code qar.review.rejected} → COMPOSING.
 */
public final class RptSubscriptionContracts {

    private RptSubscriptionContracts() {}

    public record QarReviewDecided(UUID reviewId, UUID revisionId, UUID decidedBy, String comment) {
        public static final String APPROVED = "qar.review.approved";
        public static final String REJECTED = "qar.review.rejected";
    }
}
