package com.atomeng.erp.fcd.api;

import com.atomeng.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * FCD 발행 이벤트 (설계 12 B6). payload = 레코드 컴포넌트.
 * {@code fcd.expense.posted}/{@code reversed} 의 필드는 08 CST 구독 계약(cst::api CstSubscriptionContracts)과
 * 같아야 한다 — 계약 테스트(apps FcdCstContractTest)가 JSON 왕복으로 확인한다.
 */
public final class FcdEvents {

    private FcdEvents() {}

    /** fcd.expense.posted — 정산 라인 1건 = 이벤트 1건(CST 멱등키 lineId). amountKrw = 원가(공제 시 공급가, 불공제 시 합계) */
    public record ExpensePosted(UUID expenseReportId, UUID lineId, UUID projectId, String wbsCode, String costElement,
                                BigDecimal amountKrw, BigDecimal vatKrw, LocalDate txnDate, String cardTxnKey,
                                UUID employeeId) implements DomainEvent {
        public static final String TYPE = "fcd.expense.posted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ExpenseReport"; }
        @Override public UUID aggregateId() { return expenseReportId; }
    }

    /** fcd.expense.reversed — 전기된 라인 역분개(부당 판정 · 오류, BR-FCD-016) */
    public record ExpenseReversed(UUID expenseReportId, UUID lineId, String reason, LocalDate reversedOn)
            implements DomainEvent {
        public static final String TYPE = "fcd.expense.reversed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ExpenseReport"; }
        @Override public UUID aggregateId() { return expenseReportId; }
    }

    /** fcd.import.committed — 배치 확정(건수) */
    public record ImportCommitted(UUID batchId, String channel, Map<String, Integer> counts, int txnTouched)
            implements DomainEvent {
        public static final String TYPE = "fcd.import.committed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ImportBatch"; }
        @Override public UUID aggregateId() { return batchId; }
    }

    /** fcd.import.rollback-completed — 배치 롤백(설계 이름 fcd.import.rolled-back 은 이벤트 이름 규칙상 과거형 끝단어가 'back' 이라 변경) */
    public record ImportRolledBack(UUID batchId, List<UUID> removedTxnIds, List<UUID> keptTxnIds, String reason)
            implements DomainEvent {
        public static final String TYPE = "fcd.import.rollback-completed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ImportBatch"; }
        @Override public UUID aggregateId() { return batchId; }
    }

    /** fcd.risk.scored — 등급이 바뀐 경우에만 외부 공개(설계 B6) */
    public record RiskScored(UUID txnId, int score, String grade, String prevGrade, List<String> topReasons,
                             String rulesetVersion) implements DomainEvent {
        public static final String TYPE = "fcd.risk.scored";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Transaction"; }
        @Override public UUID aggregateId() { return txnId; }
    }

    /** fcd.case.opened — 판정 전 표현은 "확인이 필요한 사용"(F12) */
    public record CaseOpened(UUID caseId, String caseNo, List<UUID> txnIds, String grade, int score, UUID subjectEmployeeId,
                             boolean auditSample) implements DomainEvent {
        public static final String TYPE = "fcd.case.opened";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Case"; }
        @Override public UUID aggregateId() { return caseId; }
    }

    /** fcd.case.resolved — 판정(사람) 결과 · 피드백 라벨 */
    public record CaseResolved(UUID caseId, String caseNo, String label, List<UUID> txnIds) implements DomainEvent {
        public static final String TYPE = "fcd.case.resolved";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Case"; }
        @Override public UUID aggregateId() { return caseId; }
    }

    /** fcd.card.deactivation-flagged — 퇴사 등으로 정지 검토 표시(자동 정지 없음, BR-FCD-014) */
    public record CardDeactivationFlagged(UUID cardId, UUID holderEmployeeId, LocalDate resignedOn, String reason)
            implements DomainEvent {
        public static final String TYPE = "fcd.card.deactivation-flagged";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Card"; }
        @Override public UUID aggregateId() { return cardId; }
    }
}
