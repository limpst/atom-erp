package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.qar.api.QarEvents;
import com.atomeng.erp.rpt.api.RptSubscriptionContracts.QarReviewDecided;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * QAR → RPT 이벤트 계약: QAR 의 qar.review.approved/rejected payload 는 RPT 구독 계약 {@link QarReviewDecided} 의
 * 모든 필드를 같은 이름 · 같은 값으로 포함한다(QAR 은 설계 07 B6 필드를 덧붙일 수 있음 — RPT 는 모르는 필드 무시).
 */
class QarRptContractTest {

    static final ObjectMapper OM = JsonMapper.builder().findAndAddModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    @Test
    @DisplayName("qar.review.approved · rejected payload ⊇ RptSubscriptionContracts.QarReviewDecided(reviewId · revisionId · decidedBy · comment)")
    void approvedAndRejectedCarryRptContract() {
        UUID review = UUID.randomUUID();
        UUID rev = UUID.randomUUID();
        UUID pm = UUID.randomUUID();
        List<String> contract = Arrays.stream(QarReviewDecided.class.getRecordComponents()).map(c -> c.getName()).toList();
        var approved = new QarEvents.ReviewApproved(review, rev, pm, "승인", 1, pm, List.of(UUID.randomUUID()),
                "a".repeat(64));
        var rejected = new QarEvents.ReviewRejected(review, rev, pm, "반려 요약", 2, "QA_REVIEW", "반려 요약",
                Map.of("MAJOR", 1));
        for (Object e : List.of(approved, rejected)) {
            JsonNode payload = OM.valueToTree(e);
            contract.forEach(f -> assertThat(payload.has(f)).as("필드 %s", f).isTrue());
            QarReviewDecided d = OM.convertValue(payload, QarReviewDecided.class);
            assertThat(d.reviewId()).isEqualTo(review);
            assertThat(d.revisionId()).isEqualTo(rev);
            assertThat(d.decidedBy()).isEqualTo(pm);
            assertThat(d.comment()).isNotBlank();
        }
        assertThat(approved.eventType()).isEqualTo(QarReviewDecided.APPROVED);
        assertThat(rejected.eventType()).isEqualTo(QarReviewDecided.REJECTED);
    }

    @Test
    @DisplayName("QAR 발행 이벤트 이름 규칙({mod}.{aggregate}.{과거형}) 통과")
    void eventNames() {
        for (String t : List.of(QarEvents.ReviewSubmitted.TYPE, QarEvents.ReviewAssigned.TYPE, QarEvents.ReviewRejected.TYPE,
                QarEvents.ReviewApproved.TYPE, QarEvents.ReviewEscalated.TYPE, QarEvents.AiFindingDecided.TYPE)) {
            assertThat(EventNames.requireValid(t)).isEqualTo(t);
        }
    }
}
