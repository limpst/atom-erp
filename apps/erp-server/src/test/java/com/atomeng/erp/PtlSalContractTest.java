package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.ptl.api.PtlEvents;
import com.atomeng.erp.rpt.api.RptEvents;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.sal.api.SalSubscriptionContracts;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PTL ↔ SAL 이벤트 계약 테스트 (설계 09 v0.3 B6 ptl.service-request.submitted · 구독자 SAL). SAL 구독 계약
 * (sal::api SalSubscriptionContracts.PtlServiceRequestSubmitted — PTL 구현 전 SAL 쪽에 고정한 스텁)에 실제 PTL 발행 레코드
 * (ptl::api PtlEvents.ServiceRequestSubmitted)가 이름 · 필드 · 값까지 맞는지 JSON 왕복으로 확인한다. 알 수 없는 필드는 실패.
 * PTL 이 구독하는 RPT · FAC · SAL 이벤트 레코드도 같은 방식으로 역직렬화되는지 확인한다.
 */
class PtlSalContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("ptl.service-request.submitted (PTL 발행) = SAL 구독 계약 — 양방향 왕복 · 필드 집합 일치")
    void serviceRequestToSal() throws Exception {
        var ev = new PtlEvents.ServiceRequestSubmitted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "PRECISE_INSP", "[QUOTE] 합성 정밀점검 견적 요청");
        String json = OM.writeValueAsString(ev);
        var c = OM.readValue(json, SalSubscriptionContracts.PtlServiceRequestSubmitted.class);
        assertThat(PtlEvents.ServiceRequestSubmitted.TYPE).isEqualTo(SalSubscriptionContracts.PtlServiceRequestSubmitted.TYPE)
                .isEqualTo("ptl.service-request.submitted");
        assertThat(OM.readValue(OM.writeValueAsString(c), PtlEvents.ServiceRequestSubmitted.class)).isEqualTo(ev);
        assertThat(c.serviceRequestId()).isEqualTo(ev.serviceRequestId());
        assertThat(c.clientId()).isEqualTo(ev.clientId());
        assertThat(OM.readTree(json).properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder(
                "serviceRequestId", "clientId", "facilityId", "requestType", "message");
        EventNames.requireValid(PtlEvents.ServiceRequestSubmitted.TYPE);
    }

    @Test
    @DisplayName("PTL 구독: rpt.report.delivered · superseded, fac.grade.recorded · due-approaching · overdue, "
            + "sal.estimate.issued · contract.ready-to-sign payload 가 생산 모듈 api 레코드로 왕복")
    void subscribedEventsRoundTrip() throws Exception {
        List<Object> events = List.of(
                new RptEvents.ReportDelivered(UUID.randomUUID(), UUID.randomUUID(), 1, UUID.randomUUID(),
                        Instant.parse("2026-10-15T00:30:00Z"), null, UUID.randomUUID()),
                new RptEvents.ReportSuperseded(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, 2, "합성 정정"),
                new FacEvents.GradeRecorded(UUID.randomUUID(), "C", "B", "REGULAR", LocalDate.of(2026, 10, 1),
                        UUID.randomUUID()),
                new FacEvents.InspectionDueApproaching(UUID.randomUUID(), "REGULAR", LocalDate.of(2027, 1, 1), 90, "k"),
                new FacEvents.InspectionOverdue(UUID.randomUUID(), "REGULAR", LocalDate.of(2026, 9, 1)),
                new SalEvents.EstimateIssued(UUID.randomUUID(), UUID.randomUUID(), "EST-2026-0001", 2,
                        new BigDecimal("12000000"), new BigDecimal("1200000"), null),
                new SalEvents.ContractReadyToSign(UUID.randomUUID(), UUID.randomUUID(), null, new BigDecimal("13200000"),
                        LocalDate.of(2026, 11, 30), List.of("대표이사")));
        for (Object e : events) {
            assertThat(OM.readValue(OM.writeValueAsString(e), e.getClass())).isEqualTo(e);
        }
    }
}
