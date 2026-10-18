package com.atomeng.erp.cst.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.events.ProcessedEvents;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.LedgerRow;
import com.atomeng.erp.cst.api.CstEvents;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.EqpEquipmentReturned;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FcdExpensePosted;
import com.atomeng.erp.cst.api.CstSubscriptionContracts.FcdExpenseReversed;
import com.atomeng.erp.cst.application.CostLedgerService.CostPosting;
import com.atomeng.erp.cst.application.port.DouzoneVoucherPort;
import com.atomeng.erp.cst.domain.shared.PeriodYm;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 외부 모듈 원가 구독 스텁 처리 (설계 08 B6 v0.4):
 * <ul>
 *   <li>{@code fcd.expense.posted} → EXPENSE 원장 행(source_id = lineId), 멱등키 (lineId, eventType), 더존 전표(스텁) 후
 *       {@code cst.voucher.synced} 발행. 공급가(amountKrw)만 원가, 부가세(vatKrw)는 매입세액으로 원가 제외</li>
 *   <li>{@code fcd.expense.reversed} → 해당 EXPENSE 행 역분개(BR-CST-003). 원 행 미도착이면 재시도</li>
 *   <li>{@code eqp.equipment.returned} → EQUIP_USE 행 = 일수 × EQUIP_DAILY(장비코드, 반납일) (FR-CST-007, P2)</li>
 * </ul>
 */
@Service
public class ExternalCostService {

    private static final Logger log = LoggerFactory.getLogger(ExternalCostService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    public static final String FCD_KEY_CONSUMER = "cst.fcd-expense:key";

    private final CostLedgerService ledger;
    private final CstJdbc jdbc;
    private final RateService rates;
    private final DouzoneVoucherPort douzone;
    private final ProcessedEvents processed;
    private final OutboxPublisher outbox;
    private final Clock clock;

    public ExternalCostService(CostLedgerService ledger, CstJdbc jdbc, RateService rates, DouzoneVoucherPort douzone,
                               ProcessedEvents processed, OutboxPublisher outbox, Clock clock) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.rates = rates;
        this.douzone = douzone;
        this.processed = processed;
        this.outbox = outbox;
        this.clock = clock;
    }

    private boolean firstTime(String key) {
        return processed.markProcessed(FCD_KEY_CONSUMER, UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerRow> onExpensePosted(FcdExpensePosted e) {
        if (e.amountKrw() == null || e.amountKrw().signum() <= 0) {
            throw new DomainException(CstErrors.INVALID, "fcd.expense.posted amountKrw > 0 필요: line=" + e.lineId());
        }
        if (!firstTime(e.lineId() + ":" + FcdExpensePosted.TYPE)) {
            return Optional.empty();
        }
        Optional<LedgerRow> row = ledger.postCost(new CostPosting("EXPENSE", e.lineId(), e.projectId(), e.wbsCode(),
                e.costElement(), e.txnDate(), PeriodYm.of(e.txnDate()), e.amountKrw(), null, e.employeeId(),
                e.cardTxnKey(), List.of()));
        row.ifPresent(r -> voucher(e.expenseReportId(), e.lineId(), r, false));
        return row;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerRow> onExpenseReversed(FcdExpenseReversed e) {
        LedgerRow original = jdbc.findPosted("EXPENSE", e.lineId()).orElseThrow(() -> new DomainException(
                CstErrors.ORIGINAL_NOT_POSTED, "[" + CstErrors.ORIGINAL_NOT_POSTED + "] 원 경비 원장 행 미도착 — 재시도 대기: lineId=" + e.lineId()));
        if (!firstTime(e.lineId() + ":" + FcdExpenseReversed.TYPE)) {
            return Optional.empty();
        }
        LocalDate on = e.reversedOn() != null ? e.reversedOn() : LocalDate.now(clock.withZone(SEOUL));
        Optional<LedgerRow> rev = ledger.reverse(original, PeriodYm.of(on), "fcd:" + e.lineId() + ":reversed");
        rev.ifPresent(r -> voucher(e.expenseReportId(), e.lineId(), r, true));
        return rev;
    }

    private void voucher(UUID reportId, UUID lineId, LedgerRow row, boolean reversal) {
        String docNo = null;
        String status = "SYNCED";
        String error = null;
        try {
            docNo = douzone.syncExpenseVoucher(reportId, List.of(lineId), row.periodYm(), row.amountKrw(), reversal);
        } catch (RuntimeException ex) {
            status = "FAILED";
            error = String.valueOf(ex.getMessage());
            log.warn("더존 전표 동기화 실패 report={} line={}: {}", reportId, lineId, error);
        }
        String kind = reversal ? "REVERSED" : "POSTED";
        jdbc.insertVoucherSync(UuidV7.next(), reportId, List.of(lineId), kind, row.periodYm(), docNo, status, error);
        outbox.publish(new CstEvents.VoucherSynced(reportId, List.of(lineId), docNo, row.periodYm(), status, kind));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<LedgerRow> onEquipmentReturned(EqpEquipmentReturned e) {
        if (e.days() <= 0) {
            throw new DomainException(CstErrors.INVALID, "eqp.equipment.returned days > 0: rental=" + e.rentalId());
        }
        BigDecimal daily = rates.resolve("EQUIP_DAILY", e.equipmentCode(), e.returnedOn());
        BigDecimal amount = daily.multiply(BigDecimal.valueOf(e.days()));
        return ledger.postCost(new CostPosting("EQUIP", e.rentalId(), e.projectId(), null, "EQUIP_USE", e.returnedOn(),
                PeriodYm.of(e.returnedOn()), amount, BigDecimal.valueOf(e.days()), null,
                "eqp:" + e.equipmentCode(), List.of()));
    }
}
