package com.atomeng.erp.cst.api;

import com.atomeng.common.events.DomainEvent;
import java.nio.charset.StandardCharsets;
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
}
