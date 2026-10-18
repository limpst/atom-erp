package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.cst.api.CstEvents;
import com.atomeng.erp.cst.api.CstSubscriptionContracts;
import com.atomeng.erp.fcd.api.FcdEvents;
import com.atomeng.erp.fcd.api.FcdSubscriptionContracts;
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
 * FCD ↔ CST 이벤트 계약 테스트 — 두 모듈은 서로의 내부를 모르고(fcd 는 core::api · hrq::api 만), payload 만 맞춘다.
 * 알 수 없는 필드는 실패시켜(FAIL_ON_UNKNOWN_PROPERTIES) 이름 · 타입 어긋남을 잡는다.
 */
class FcdCstContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("fcd.expense.posted · reversed (FCD 발행) = CST 구독 계약(CstSubscriptionContracts) — 이름 · 필드 · 값 왕복 일치")
    void fcdToCst() throws Exception {
        var posted = new FcdEvents.ExpensePosted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "WBS-1", "TRAVEL",
                new BigDecimal("10909"), new BigDecimal("1091"), LocalDate.of(2026, 5, 5), "fcd:" + UUID.randomUUID(),
                UUID.randomUUID());
        var c = OM.readValue(OM.writeValueAsString(posted), CstSubscriptionContracts.FcdExpensePosted.class);
        assertThat(FcdEvents.ExpensePosted.TYPE).isEqualTo(CstSubscriptionContracts.FcdExpensePosted.TYPE);
        assertThat(List.of(c.expenseReportId(), c.lineId(), c.projectId(), c.wbsCode(), c.costElement(), c.amountKrw(),
                c.vatKrw(), c.txnDate(), c.cardTxnKey(), c.employeeId()))
                .isEqualTo(List.of(posted.expenseReportId(), posted.lineId(), posted.projectId(), posted.wbsCode(),
                        posted.costElement(), posted.amountKrw(), posted.vatKrw(), posted.txnDate(), posted.cardTxnKey(),
                        posted.employeeId()));
        var rev = new FcdEvents.ExpenseReversed(UUID.randomUUID(), UUID.randomUUID(), "부당 판정", LocalDate.of(2026, 5, 20));
        var r = OM.readValue(OM.writeValueAsString(rev), CstSubscriptionContracts.FcdExpenseReversed.class);
        assertThat(FcdEvents.ExpenseReversed.TYPE).isEqualTo(CstSubscriptionContracts.FcdExpenseReversed.TYPE);
        assertThat(List.of(r.expenseReportId(), r.lineId(), r.reason(), r.reversedOn()))
                .isEqualTo(List.of(rev.expenseReportId(), rev.lineId(), rev.reason(), rev.reversedOn()));
    }

    @Test
    @DisplayName("cst.voucher.synced (CST 발행) = FCD 구독 계약(FcdSubscriptionContracts.CstVoucherSynced)")
    void cstToFcd() throws Exception {
        var v = new CstEvents.VoucherSynced(UUID.randomUUID(), List.of(UUID.randomUUID()), "DZ-STUB-202605-0001", "202605",
                "SYNCED", "POSTED");
        var f = OM.readValue(OM.writeValueAsString(v), FcdSubscriptionContracts.CstVoucherSynced.class);
        assertThat(FcdSubscriptionContracts.CstVoucherSynced.TYPE).isEqualTo(CstEvents.VoucherSynced.TYPE);
        assertThat(List.of(f.expenseReportId(), f.lineIds(), f.douzoneDocNo(), f.periodYm(), f.status(), f.kind()))
                .isEqualTo(List.of(v.expenseReportId(), v.lineIds(), v.douzoneDocNo(), v.periodYm(), v.status(), v.kind()));
    }
}
