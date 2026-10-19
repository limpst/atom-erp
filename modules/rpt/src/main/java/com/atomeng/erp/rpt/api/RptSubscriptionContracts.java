package com.atomeng.erp.rpt.api;

import java.time.LocalDate;
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

    /**
     * fld.survey.completed (설계 05 v0.2 B6.2) — 조사 회차의 모든 구역 잠금 → 보고서 작성 가능 신호 · 스냅샷 원천 워터마크.
     * 생산자 fld::api FldEvents.SurveyCompleted 와 필드가 같아야 한다(FldContractTest).
     */
    public record FldSurveyCompleted(UUID roundId, UUID projectId, UUID buildingId, int roundNo, String surveyType,
                                     int zoneCount, int defectCount, long lastSeq, LocalDate completedOn) {
        public static final String TYPE = "fld.survey.completed";
    }
}
