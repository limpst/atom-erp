package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.fac.api.ReportPublishedContract;
import com.atomeng.erp.rpt.api.RptEvents;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPT ↔ FAC 이벤트 계약 테스트 (06 v0.2 B6 · 02 v0.3 B6). FAC 구독 계약(fac::api ReportPublishedContract)에 실제 RPT 발행 레코드
 * (rpt::api RptEvents.ReportPublished)가 이름 · 필드 · 값까지 맞는지 JSON 왕복으로 확인한다. 알 수 없는 필드는 실패.
 */
class RptFacContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("rpt.report.published (RPT 발행) = FAC 구독 계약 — 양방향 왕복 · 필드 집합 일치")
    void publishedToFac() throws Exception {
        var ev = new RptEvents.ReportPublished(UUID.randomUUID(), UUID.randomUUID(), 2, UUID.randomUUID(), "RT_PRC",
                List.of(UUID.randomUUID(), UUID.randomUUID()), "C", "DETAILED", LocalDate.of(2026, 9, 30),
                UUID.randomUUID(), "a".repeat(64));
        String json = OM.writeValueAsString(ev);
        var c = OM.readValue(json, ReportPublishedContract.class);
        assertThat(RptEvents.ReportPublished.TYPE).isEqualTo(ReportPublishedContract.TYPE).isEqualTo("rpt.report.published");
        assertThat(OM.readValue(OM.writeValueAsString(c), RptEvents.ReportPublished.class)).isEqualTo(ev);
        assertThat(c.buildingIds()).hasSize(2);
        assertThat(c.performedOn()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(OM.readTree(json).properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "reportId", "revisionId", "revNo", "projectId", "reportType", "buildingIds", "grade", "inspectionType",
                "performedOn", "approvedBy", "pdfSha256");
    }

    @Test
    @DisplayName("RPT 발행 이벤트명 = 설계 06 v0.2 정식 세트(drafted · published · delivered · superseded) + snapshot.frozen, 이름 규칙 통과")
    void eventNames() {
        List<String> names = List.of(RptEvents.ReportDrafted.TYPE, RptEvents.ReportPublished.TYPE,
                RptEvents.ReportDelivered.TYPE, RptEvents.ReportSuperseded.TYPE, RptEvents.SnapshotFrozen.TYPE);
        names.forEach(EventNames::requireValid);
        assertThat(names).containsExactly("rpt.report.drafted", "rpt.report.published", "rpt.report.delivered",
                "rpt.report.superseded", "rpt.snapshot.frozen");
    }
}
