package com.atomeng.erp.qar.api;

import com.atomeng.common.events.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * QAR 발행 이벤트 (설계 07 B6). payload = 레코드 컴포넌트, 모두 전이 트랜잭션 안에서 core.outbox_event 에 기록된다(NFR-QAR-04).
 * approved · rejected 는 RPT 구독 계약 {@code RptSubscriptionContracts.QarReviewDecided}(reviewId · revisionId · decidedBy ·
 * comment)를 그대로 포함하고 설계 B6 필드를 덧붙인다(계약 테스트 QarRptContractTest).
 */
public final class QarEvents {

    private QarEvents() {}

    /** qar.review.submitted — 제출(라운드 고정): RPT 편집 잠금 확인 · CST 검토 공수 */
    public record ReviewSubmitted(UUID reviewId, UUID reportId, int roundNo, UUID revisionId, String pdfSha256,
                                  Map<String, String> ruleVersions) implements DomainEvent {
        public static final String TYPE = "qar.review.submitted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Review"; }
        @Override public UUID aggregateId() { return reviewId; }
    }

    /** qar.review.assigned — 검토자 배정(알림) */
    public record ReviewAssigned(UUID reviewId, UUID roundId, String step, UUID assigneeId, String assignedBy,
                                 Instant dueAt) implements DomainEvent {
        public static final String TYPE = "qar.review.assigned";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Review"; }
        @Override public UUID aggregateId() { return reviewId; }
    }

    /** qar.review.rejected — RPT: IN_REVIEW → COMPOSING(편집 잠금 해제). comment = 반려 요약 */
    public record ReviewRejected(UUID reviewId, UUID revisionId, UUID decidedBy, String comment, int roundNo,
                                 String step, String rejectSummary, Map<String, Integer> openCounts)
            implements DomainEvent {
        public static final String TYPE = "qar.review.rejected";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Review"; }
        @Override public UUID aggregateId() { return reviewId; }
    }

    /** qar.review.approved — RPT: IN_REVIEW → APPROVED(서명 · 발행 가능). waivers = 승인된 WARNING 예외 */
    public record ReviewApproved(UUID reviewId, UUID revisionId, UUID decidedBy, String comment, int roundNo,
                                 UUID approverId, List<UUID> waivers, String pdfSha256) implements DomainEvent {
        public static final String TYPE = "qar.review.approved";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Review"; }
        @Override public UUID aggregateId() { return reviewId; }
    }

    /** qar.review.escalated — 라운드 3회 초과 반려(BR-QAR-008, reason=ROUND_LIMIT) */
    public record ReviewEscalated(UUID reviewId, String reason, int roundNo) implements DomainEvent {
        public static final String TYPE = "qar.review.escalated";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Review"; }
        @Override public UUID aggregateId() { return reviewId; }
    }

    /** qar.ai-finding.decided — AI 제안 채택/기각(AIP 평가셋 · 정밀도) */
    public record AiFindingDecided(UUID findingId, UUID roundId, String decision, String reason, String category,
                                   String model, String promptHash) implements DomainEvent {
        public static final String TYPE = "qar.ai-finding.decided";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AiFinding"; }
        @Override public UUID aggregateId() { return findingId; }
    }
}
