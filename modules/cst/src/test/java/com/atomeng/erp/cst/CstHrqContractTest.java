package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.EventNames;
import com.atomeng.erp.cst.adapter.in.event.ExternalCostListener;
import com.atomeng.erp.cst.adapter.in.event.HrqTimesheetListener;
import com.atomeng.erp.cst.api.CstEvents;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.EqpEquipmentReturned;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FcdExpensePosted;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FcdExpenseReversed;
import com.atomeng.erp.cst.application.ExternalCostService;
import com.atomeng.erp.cst.application.LaborPostingService;
import com.atomeng.erp.cst.application.ReconciliationService;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;

/**
 * HRQ(생산자) ↔ CST(소비자) 이벤트 계약 테스트 (TC-HRQ-010 전제, TC-CST-13·14 전제).
 * 생산자 레코드(hrq::api HrqEvents)를 outbox 와 같은 방식(ObjectMapper.valueToTree)으로 payload 화한 뒤
 * ① 설계 03/08 B6 의 필수 필드가 모두 있는지 ② CST 리스너가 같은 값으로 역직렬화하는지 ③ 리스너 조건이 정본 이벤트명과 같은지 확인한다.
 * 합성 데이터만 사용.
 */
class CstHrqContractTest {

    /** Spring Boot 기본 ObjectMapper 와 같은 설정(JSR-310, 날짜 문자열, 미지 속성 무시) */
    static final ObjectMapper OM = JsonMapper.builder().findAndAddModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    static final UUID EMP = UUID.fromString("0192f0a1-0000-7000-8000-000000000001");
    static final UUID PRJ = UUID.fromString("0192f0a1-0000-7000-8000-000000000002");
    static final UUID KEY = UUID.fromString("0192f0a1-0000-7000-8000-000000000003");
    static final UUID CORRECTOR = UUID.fromString("0192f0a1-0000-7000-8000-000000000005");

    /** outbox payload(valueToTree)는 BigDecimal 뒤 0을 정규화한다(70000 → 7E+4) — 금액은 값(compareTo)으로 비교 */
    static <T> void sameValue(T actual, T expected) {
        assertThat(actual).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
    }

    static List<String> fields(JsonNode n) {
        List<String> out = new ArrayList<>();
        Iterator<String> it = n.fieldNames();
        it.forEachRemaining(out::add);
        return out;
    }

    static EventEnvelope envelope(String type, JsonNode payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, OffsetDateTime.now(), "X", UUID.randomUUID(), null, null,
                payload);
    }

    static HrqEvents.TimesheetConfirmed confirmed() {
        return new HrqEvents.TimesheetConfirmed(EMP, LocalDate.of(2026, 9, 15), "202609", List.of(
                new HrqEvents.TimesheetLine(KEY, KEY, 1, PRJ, "10-FIELD", "FIELD", new BigDecimal("7.75"))));
    }

    static HrqEvents.TimesheetCorrected corrected() {
        UUID newId = UUID.fromString("0192f0a1-0000-7000-8000-000000000004");
        return new HrqEvents.TimesheetCorrected(KEY, KEY, newId, 1, 2, EMP, LocalDate.of(2026, 9, 15), "202610", PRJ,
                null, "FIELD", new BigDecimal("8"), new BigDecimal("6"), new BigDecimal("-2"), "합성 정정 사유",
                CORRECTOR, true, KEY + ":2");
    }

    static HrqEvents.MandayClosed closed() {
        return new HrqEvents.MandayClosed("202609", new BigDecimal("1.75"), List.of(
                new HrqEvents.MandayRow(PRJ, EMP, "SENIOR", "FIELD", new BigDecimal("1.75"))));
    }

    @Test
    @DisplayName("계약: hrq.timesheet.confirmed payload 필수 필드(설계 B6) + BigDecimal·LocalDate 형식 + 왕복 동일")
    void confirmedContract() {
        var e = confirmed();
        JsonNode p = OM.valueToTree(e);
        assertThat(fields(p)).contains("employeeId", "date", "postingPeriod", "lines");
        assertThat(fields(p.get("lines").get(0))).contains("entryId", "timesheetKey", "revision", "projectId", "wbsCode",
                "workType", "hours");
        assertThat(p.get("date").asText()).isEqualTo("2026-09-15");
        assertThat(p.get("postingPeriod").asText()).matches("^[0-9]{4}(0[1-9]|1[0-2])$");
        assertThat(p.get("lines").get(0).get("hours").decimalValue()).isEqualByComparingTo("7.75");
        assertThat(OM.convertValue(p, HrqEvents.TimesheetConfirmed.class)).isEqualTo(e);
    }

    @Test
    @DisplayName("계약: hrq.timesheet.corrected 필수 필드(BR-CST-015·BR-HRQ-014) + 멱등키 = timesheetKey:newRevision")
    void correctedContract() {
        var e = corrected();
        JsonNode p = OM.valueToTree(e);
        assertThat(fields(p)).contains("timesheetKey", "previousEntryId", "newEntryId", "previousRevision", "newRevision",
                "employeeId", "workDate", "postingPeriod", "projectId", "wbsCode", "workType", "previousHours", "newHours",
                "deltaHours", "reason", "correctedBy", "adjustment", "idempotencyKey");
        assertThat(LaborPostingService.correctionKey(e)).isEqualTo(p.get("idempotencyKey").asText());
        assertThat(p.get("newHours").decimalValue().subtract(p.get("previousHours").decimalValue()))
                .isEqualByComparingTo(p.get("deltaHours").decimalValue());
        assertThat(OM.convertValue(p, HrqEvents.TimesheetCorrected.class)).isEqualTo(e);
    }

    @Test
    @DisplayName("계약: hrq.manday.closed(대사 전용) rows(projectId, employeeId, grade, workType, mandays) 왕복")
    void mandayClosedContract() {
        var e = closed();
        JsonNode p = OM.valueToTree(e);
        assertThat(fields(p)).contains("yearMonth", "totalMandays", "rows");
        assertThat(fields(p.get("rows").get(0))).contains("projectId", "employeeId", "grade", "workType", "mandays");
        assertThat(OM.convertValue(p, HrqEvents.MandayClosed.class)).isEqualTo(e);
        JsonNode g = OM.valueToTree(new HrqEvents.GradeChanged(EMP, "SENIOR", "INTER", LocalDate.of(2026, 9, 16)));
        assertThat(fields(g)).contains("employeeId", "newGrade", "effectiveDate");
    }

    @Test
    @DisplayName("계약: CST 리스너가 HRQ 정본 이벤트명으로만 구독하고, payload 를 생산자 레코드와 같은 값으로 넘긴다")
    void listenerBindsCanonicalNamesAndPayloads() throws Exception {
        Map<String, String> expected = Map.of(
                "onConfirmed", HrqEvents.TimesheetConfirmed.TYPE,
                "onCorrected", HrqEvents.TimesheetCorrected.TYPE,
                "onMandayClosed", HrqEvents.MandayClosed.TYPE);
        for (var en : expected.entrySet()) {
            Method m = HrqTimesheetListener.class.getMethod(en.getKey(), EventEnvelope.class);
            assertThat(m.getAnnotation(EventListener.class).condition())
                    .isEqualTo("#env.eventType == '" + en.getValue() + "'");
        }
        LaborPostingService labor = mock(LaborPostingService.class);
        ReconciliationService recon = mock(ReconciliationService.class);
        var listener = new HrqTimesheetListener(labor, recon, OM);
        listener.onConfirmed(envelope(HrqEvents.TimesheetConfirmed.TYPE, OM.valueToTree(confirmed())));
        verify(labor).onConfirmed(eq(confirmed()));
        listener.onCorrected(envelope(HrqEvents.TimesheetCorrected.TYPE, OM.valueToTree(corrected())));
        verify(labor).onCorrected(eq(corrected()));
        var env = envelope(HrqEvents.MandayClosed.TYPE, OM.valueToTree(closed()));
        listener.onMandayClosed(env);
        verify(recon).onMandayClosed(eq(env.eventId()), eq(closed()));
        // BR-HRQ-013: 월 마감 이벤트는 인건비 전기 경로를 타지 않는다
        verifyNoMoreInteractions(labor);
    }

    @Test
    @DisplayName("계약(스텁): fcd.expense.posted/reversed·eqp.equipment.returned 왕복 + 리스너 바인딩, CST 발행 이벤트명 규칙")
    void stubContracts() throws Exception {
        var posted = new FcdExpensePosted(UUID.randomUUID(), UUID.randomUUID(), PRJ, "10-FIELD", "LODGING",
                new BigDecimal("70000"), new BigDecimal("7000"), LocalDate.of(2026, 10, 14), "SYN-CARD-0001", EMP);
        var reversed = new FcdExpenseReversed(posted.expenseReportId(), posted.lineId(), "합성 취소", null);
        var returned = new EqpEquipmentReturned(UUID.randomUUID(), UUID.randomUUID(), "EQ-SYN-01", PRJ, 3,
                LocalDate.of(2026, 10, 10));
        JsonNode pp = OM.valueToTree(posted);
        assertThat(fields(pp)).contains("expenseReportId", "lineId", "projectId", "wbsCode", "costElement", "amountKrw",
                "vatKrw", "txnDate", "cardTxnKey");
        sameValue(OM.convertValue(pp, FcdExpensePosted.class), posted);
        assertThat(OM.convertValue(pp, FcdExpensePosted.class).amountKrw().setScale(0)).isEqualTo(new BigDecimal("70000"));
        assertThat(OM.convertValue(OM.valueToTree(reversed), FcdExpenseReversed.class)).isEqualTo(reversed);
        assertThat(OM.convertValue(OM.valueToTree(returned), EqpEquipmentReturned.class)).isEqualTo(returned);

        ExternalCostService svc = mock(ExternalCostService.class);
        var l = new ExternalCostListener(svc, OM);
        l.onExpensePosted(envelope(FcdExpensePosted.TYPE, pp));
        var cap = org.mockito.ArgumentCaptor.forClass(FcdExpensePosted.class);
        verify(svc).onExpensePosted(cap.capture());
        sameValue(cap.getValue(), posted);
        l.onEquipmentReturned(envelope(EqpEquipmentReturned.TYPE, OM.valueToTree(returned)));
        verify(svc).onEquipmentReturned(any());
        assertThat(ExternalCostListener.class.getMethod("onExpenseReversed", EventEnvelope.class)
                .getAnnotation(EventListener.class).condition()).contains(FcdExpenseReversed.TYPE);

        JsonNode synced = OM.valueToTree(new CstEvents.VoucherSynced(posted.expenseReportId(), List.of(posted.lineId()),
                "DZ-STUB-202610-0000", "202610", "SYNCED", "POSTED"));
        assertThat(fields(synced)).contains("expenseReportId", "lineIds", "douzoneDocNo", "periodYm", "status");
        for (String t : List.of(CstEvents.VoucherSynced.TYPE, CstEvents.PeriodClosed.TYPE, CstEvents.LedgerCorrected.TYPE,
                FcdExpensePosted.TYPE, FcdExpenseReversed.TYPE, EqpEquipmentReturned.TYPE)) {
            assertThat(EventNames.requireValid(t)).isEqualTo(t);
        }
    }
}
