package com.atomeng.erp.cst.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 구독 계약(스텁) — 상대 모듈(FCD 12, EQP 04)이 아직 없어 CST 쪽에 payload 를 고정한다(FAC ReportPublishedContract 와 같은 방식).
 * 상대 모듈 구현 시 그쪽 api 로 옮기고 계약 테스트로 호환을 확인한다.
 */
public final class CstSubscriptionContracts {

    private CstSubscriptionContracts() {}

    /** fcd.expense.posted (설계 08 B6 v0.4) — EXPENSE 원장 행 전기. 멱등키 (lineId, eventType) */
    public record FcdExpensePosted(UUID expenseReportId, UUID lineId, UUID projectId, String wbsCode,
                                   String costElement, BigDecimal amountKrw, BigDecimal vatKrw, LocalDate txnDate,
                                   String cardTxnKey, UUID employeeId) {
        public static final String TYPE = "fcd.expense.posted";
    }

    /** fcd.expense.reversed (v0.4) — 전기된 EXPENSE 행 역분개(BR-CST-003). reversedOn 이 없으면 수신일 */
    public record FcdExpenseReversed(UUID expenseReportId, UUID lineId, String reason, LocalDate reversedOn) {
        public static final String TYPE = "fcd.expense.reversed";
    }

    /**
     * eqp.equipment.returned (설계 08 B6: equipmentId, projectId, days) — 장비 내부사용료 EQUIP_USE 전기(FR-CST-007, P2).
     * 스텁 확장: rentalId(멱등 source_id), equipmentCode(EQUIP_DAILY 요율 키), returnedOn(요율 기준일).
     */
    public record EqpEquipmentReturned(UUID rentalId, UUID equipmentId, String equipmentCode, UUID projectId,
                                       int days, LocalDate returnedOn) {
        public static final String TYPE = "eqp.equipment.returned";
    }
}
