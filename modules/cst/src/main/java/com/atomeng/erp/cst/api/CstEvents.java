package com.atomeng.erp.cst.api;

import com.atomeng.common.events.DomainEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** CST 발행 이벤트 (설계 08 B6 정본 이름). payload = 레코드 컴포넌트. */
public final class CstEvents {

    private CstEvents() {}

    /**
     * cst.voucher.synced (v0.4) — FCD 경비가 CST 원장에 전기되고 더존 전표로 동기화된 결과. 소비: FCD(정산 상태 갱신).
     * status = SYNCED | FAILED, kind = POSTED | REVERSED
     */
    public record VoucherSynced(UUID expenseReportId, List<UUID> lineIds, String douzoneDocNo, String periodYm,
                                String status, String kind) implements DomainEvent {
        public static final String TYPE = "cst.voucher.synced";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ExpenseReport"; }
        @Override public UUID aggregateId() { return expenseReportId; }
    }

    /** cst.period.closed — 소비: HRQ(타임시트 잠금) */
    public record PeriodClosed(String periodYm) implements DomainEvent {
        public static final String TYPE = "cst.period.closed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AccountingPeriod"; }
        @Override public UUID aggregateId() {
            return UUID.nameUUIDFromBytes(("cst-period:" + periodYm).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** cst.ledger.corrected (설계 B5.4 선택) — 타임시트 정정 역분개+재전기 완료, EVM 재계산 트리거 */
    public record LedgerCorrected(UUID projectId, UUID timesheetKey, int newRevision, String periodYm,
                                  UUID reversalEntryId, UUID repostEntryId) implements DomainEvent {
        public static final String TYPE = "cst.ledger.corrected";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Project"; }
        @Override public UUID aggregateId() { return projectId; }
    }
    // ------------------------------------------------------------------ 청구 · 수금 (docs/CST_BILLING_COLLECTION_DESIGN.md §11)

    /** cst.invoice.issued — 청구 발행. 소비: PTL(고객 포털 청구 표시) · SAL(계약 기성 진행). 입금자 표기 · 계좌 별칭은 싣지 않는다 */
    public record InvoiceIssued(UUID invoiceId, String invoiceNo, UUID projectId, UUID clientId, LocalDate issueDate,
                                BigDecimal supplyKrw, BigDecimal vatKrw, BigDecimal totalKrw) implements DomainEvent {
        public static final String TYPE = "cst.invoice.issued";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Invoice"; }
        @Override public UUID aggregateId() { return invoiceId; }
    }

    /** cst.receipt.allocated — 배분 확정(양수) · 역분개(음수). 소비: 내 할 일(대기 건 닫기) · 감사 */
    public record ReceiptAllocated(UUID allocationId, UUID invoiceId, UUID receiptId, BigDecimal amountKrw,
                                   UUID reversalOf, UUID confirmedBy) implements DomainEvent {
        public static final String TYPE = "cst.receipt.allocated";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Invoice"; }
        @Override public UUID aggregateId() { return invoiceId; }
    }

    /** cst.invoice.settled — 순 배분 합이 청구 합계가 된 순간(배분 1건당 최대 1회). 소비: PTL · 수익성 MV */
    public record InvoiceSettled(UUID invoiceId, String invoiceNo, UUID projectId, UUID clientId, BigDecimal totalKrw,
                                 LocalDate settledOn, UUID allocationId) implements DomainEvent {
        public static final String TYPE = "cst.invoice.settled";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Invoice"; }
        @Override public UUID aggregateId() { return invoiceId; }
    }

    /** cst.invoice.reopened — 전액 입금이던 청구가 역분개로 다시 미수가 됨. 소비: PTL · 독촉 배치 */
    public record InvoiceReopened(UUID invoiceId, String invoiceNo, BigDecimal openKrw, UUID allocationId) implements DomainEvent {
        public static final String TYPE = "cst.invoice.reopened";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Invoice"; }
        @Override public UUID aggregateId() { return invoiceId; }
    }

    /** cst.invoice.voided — 청구 취소. 소비: PTL · SAL */
    public record InvoiceVoided(UUID invoiceId, String invoiceNo, String reason) implements DomainEvent {
        public static final String TYPE = "cst.invoice.voided";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Invoice"; }
        @Override public UUID aggregateId() { return invoiceId; }
    }
}
