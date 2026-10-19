package com.atomeng.erp.cst.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * sal.contract.signed (설계 08 B6 · 01 B6, 01-IMPL Step 9 이벤트 스키마) — 실행예산 Rev.0 초안 자동 생성(FR-CST-001, TC-CST-01).
     * SAL 모듈(01) 발행 레코드(sal::api SalEvents.ContractSigned)와 필드가 같아야 한다 — 계약 테스트 SalCstContractTest.
     * CST 는 sal 모듈에 의존하지 않는다(이벤트 계약만).
     */
    public record SalContractSigned(UUID contractId, UUID projectId, String projectNo, UUID clientId,
                                    List<UUID> facilityIds, UUID estimateId, BigDecimal amount, String currency,
                                    BigDecimal jointShare, LocalDate signedDate, LocalDate startDate, LocalDate endDate,
                                    List<EstimateLine> estimateLines) {
        public static final String TYPE = "sal.contract.signed";

        /** 견적 산출내역 1줄 — LABOR(workItem × grade 인·일) / EXPENSE(경비 항목) */
        public record EstimateLine(String lineType, String workItem, String grade, BigDecimal manDays,
                                   BigDecimal unitPrice, BigDecimal quantity, BigDecimal amount) {}
    }

    /**
     * fld.survey.completed (설계 05 v0.2 B6.2) — 조사 회차의 모든 구역 잠금 → 진척 마일스톤 "현장조사 완료".
     * 생산자 fld::api FldEvents.SurveyCompleted 와 필드가 같아야 한다(FldContractTest). CST 는 fld 모듈에 의존하지 않는다.
     */
    public record FldSurveyCompleted(UUID roundId, UUID projectId, UUID buildingId, int roundNo, String surveyType,
                                     int zoneCount, int defectCount, long lastSeq, LocalDate completedOn) {
        public static final String TYPE = "fld.survey.completed";
    }
}
