package com.atomeng.erp.cst.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.events.ProcessedEvents;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.LedgerRow;
import com.atomeng.erp.cst.api.CstEvents;
import com.atomeng.erp.cst.application.CostLedgerService.LaborPosting;
import com.atomeng.erp.cst.domain.shared.PeriodYm;
import com.atomeng.erp.hrq.api.HrqEvents;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * HRQ 타임시트 → 인건비 원장 (FR-CST-004, BR-CST-015, BR-HRQ-013·014).
 * <ul>
 *   <li>{@code hrq.timesheet.confirmed}: 인건비 원가 전기의 <b>유일한</b> 원천 — 라인(entryId)마다 TIMESHEET 1행</li>
 *   <li>{@code hrq.timesheet.corrected}: 직전 리비전 행 REVERSAL + 새 리비전 TIMESHEET 재전기, 한 트랜잭션,
 *       업무 멱등키 (timesheetKey, newRevision). 원 행이 아직 없으면 CST-409-003 예외 → 롤백 → outbox 재시도(보류 큐)</li>
 * </ul>
 * 호출자(이벤트 리스너)의 트랜잭션 = outbox Relay 의 이벤트 1건 트랜잭션.
 */
@Service
public class LaborPostingService {

    private static final Logger log = LoggerFactory.getLogger(LaborPostingService.class);
    /** 업무 멱등키 가드(core.processed_event 재사용, event_id = UUID(nameUUIDFromBytes(key))) */
    public static final String CORRECTION_KEY_CONSUMER = "cst.timesheet-corrected:key";

    public enum CorrectionOutcome { APPLIED, DUPLICATE }

    private final CostLedgerService ledger;
    private final CstJdbc jdbc;
    private final ProcessedEvents processed;
    private final OutboxPublisher outbox;

    public LaborPostingService(CostLedgerService ledger, CstJdbc jdbc, ProcessedEvents processed,
                               OutboxPublisher outbox) {
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.processed = processed;
        this.outbox = outbox;
    }

    static PeriodYm requested(String postingPeriod, java.time.LocalDate workDate) {
        return PeriodYm.isValid(postingPeriod) ? PeriodYm.parse(postingPeriod) : PeriodYm.of(workDate);
    }

    /** @return 새로 기록한 행 수 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onConfirmed(HrqEvents.TimesheetConfirmed e) {
        PeriodYm req = requested(e.postingPeriod(), e.date());
        int posted = 0;
        for (HrqEvents.TimesheetLine line : e.lines()) {
            Optional<LedgerRow> r = ledger.postLabor(new LaborPosting(line.entryId(), line.projectId(), line.wbsCode(),
                    e.employeeId(), e.date(), line.workType(), line.hours(), req,
                    line.timesheetKey() + ":" + line.revision()));
            posted += r.isPresent() ? 1 : 0;
        }
        return posted;
    }

    public static String correctionKey(HrqEvents.TimesheetCorrected e) {
        return e.timesheetKey() + ":" + e.newRevision();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CorrectionOutcome onCorrected(HrqEvents.TimesheetCorrected e) {
        String key = correctionKey(e);
        if (e.idempotencyKey() != null && !e.idempotencyKey().equals(key)) {
            log.warn("hrq.timesheet.corrected idempotencyKey 불일치(payload={}, 계산={}) — 계산값 사용", e.idempotencyKey(), key);
        }
        LedgerRow original = jdbc.findPosted("TIMESHEET", e.previousEntryId()).orElseThrow(() -> new DomainException(
                CstErrors.ORIGINAL_NOT_POSTED, "[" + CstErrors.ORIGINAL_NOT_POSTED + "] 원 원장 행 미도착(순서 역전) — 재시도 대기: previousEntryId=" + e.previousEntryId()));
        if (!processed.markProcessed(CORRECTION_KEY_CONSUMER,
                UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)))) {
            return CorrectionOutcome.DUPLICATE;
        }
        if (e.previousHours() != null && original.hours() != null && original.hours().compareTo(e.previousHours()) != 0) {
            log.warn("정정 previousHours({}) 와 원장 시간({}) 불일치 — 원장 값으로 역분개 key={}", e.previousHours(),
                    original.hours(), key);
        }
        PeriodYm req = requested(e.postingPeriod(), e.workDate());
        Optional<LedgerRow> rev = ledger.reverse(original, req, key);
        Optional<LedgerRow> repost = ledger.postLabor(new LaborPosting(e.newEntryId(), e.projectId(), e.wbsCode(),
                e.employeeId(), e.workDate(), e.workType(), e.newHours(), req, key));
        String period = repost.map(LedgerRow::periodYm).or(() -> rev.map(LedgerRow::periodYm)).orElse(req.value());
        outbox.publish(new CstEvents.LedgerCorrected(e.projectId(), e.timesheetKey(), e.newRevision(), period,
                rev.map(LedgerRow::id).orElse(null), repost.map(LedgerRow::id).orElse(null)));
        return CorrectionOutcome.APPLIED;
    }
}
