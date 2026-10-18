package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.cst.api.CstSubscriptionContracts;
import com.atomeng.erp.sal.api.SalEvents;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SAL ↔ CST 이벤트 계약 테스트 (01-IMPL Step 9 "sal.contract.signed 스키마를 CST 컨슈머와 공유").
 * CST 구독 계약(cst::api CstSubscriptionContracts.SalContractSigned)에 실제 SAL 발행 레코드(sal::api SalEvents.ContractSigned)가
 * 이름 · 필드 · 값까지 맞는지 JSON 왕복으로 확인한다. 알 수 없는 필드는 실패(FAIL_ON_UNKNOWN_PROPERTIES).
 */
class SalCstContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("sal.contract.signed (SAL 발행) = CST 구독 계약 — 산출내역 라인 포함 양방향 왕복")
    void contractSignedToCst() throws Exception {
        var ev = new SalEvents.ContractSigned(UUID.randomUUID(), UUID.randomUUID(), "PRJ-2026-0007", UUID.randomUUID(),
                List.of(UUID.randomUUID()), UUID.randomUUID(), new BigDecimal("72800000"), "KRW", new BigDecimal("40.00"),
                LocalDate.of(2026, 10, 19), LocalDate.of(2026, 11, 1), LocalDate.of(2027, 2, 28),
                List.of(new SalEvents.EstimateLine("LABOR", "FIELD", "ADVANCED", new BigDecimal("7.50"),
                                new BigDecimal("336000"), null, new BigDecimal("2520000")),
                        new SalEvents.EstimateLine("EXPENSE", "TRAVEL", null, null, new BigDecimal("150000"),
                                new BigDecimal("12"), new BigDecimal("1800000"))));
        String json = OM.writeValueAsString(ev);
        var c = OM.readValue(json, CstSubscriptionContracts.SalContractSigned.class);
        assertThat(SalEvents.ContractSigned.TYPE).isEqualTo(CstSubscriptionContracts.SalContractSigned.TYPE);
        assertThat(OM.readValue(OM.writeValueAsString(c), SalEvents.ContractSigned.class)).isEqualTo(ev);
        assertThat(c.estimateLines()).hasSize(2);
        assertThat(c.estimateLines().get(0).manDays()).isEqualByComparingTo("7.50");
        assertThat(OM.readTree(json).fieldNames()).toIterable().containsExactlyInAnyOrder("contractId", "projectId",
                "projectNo", "clientId", "facilityIds", "estimateId", "amount", "currency", "jointShare", "signedDate",
                "startDate", "endDate", "estimateLines");
    }

    @Test
    @DisplayName("SAL 발행 이벤트명 5종 = 설계 01 B6 v0.3 이름 · {mod}.{aggregate}.{과거형} 규칙(ready-to-sign 은 정식 상태명)")
    void eventNames() {
        List<String> names = List.of(SalEvents.BidNoticeCollected.TYPE, SalEvents.OpportunityStageChanged.TYPE,
                SalEvents.EstimateIssued.TYPE, SalEvents.ContractReadyToSign.TYPE, SalEvents.ContractSigned.TYPE);
        names.forEach(EventNames::requireValid);
        assertThat(names).containsExactly("sal.bid-notice.collected", "sal.opportunity.stage-changed",
                "sal.estimate.issued", "sal.contract.ready-to-sign", "sal.contract.signed");
    }
}
