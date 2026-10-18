package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.api.CstEvents;
import com.atomeng.erp.cst.domain.shared.PeriodYm;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회계기간 (BR-CST-012, FR-CST-018 일부). 귀속월 결정: 요청 월이 CLOSED 면 다음 OPEN(또는 REOPENED) 월.
 * 마감 체크리스트(이번 단계): HRQ 월 마감 수신 + 인·일 대사 MATCHED. 경비·입금 항목은 Step 7·10 이후.
 */
@Service
public class PeriodService {

    public record CloseResult(String periodYm, boolean closed, List<String> blockers) {}

    private final CstJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;

    public PeriodService(CstJdbc jdbc, OutboxPublisher outbox, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
    }

    /** 귀속월: requested 부터 CLOSED 가 아닌 첫 월 (월 파티션·기간 행 보장 포함) */
    @Transactional(propagation = Propagation.MANDATORY)
    public PeriodYm attribution(PeriodYm requested) {
        PeriodYm p = requested;
        for (int guard = 0; guard < 120; guard++) {
            jdbc.ensurePartition(p.value());
            if (!"CLOSED".equals(jdbc.periodStatus(p.value()).orElse("OPEN"))) {
                return p;
            }
            p = p.next();
        }
        throw new DomainException(CstErrors.PERIOD_STATE, "열린 회계월을 찾을 수 없습니다: " + requested);
    }

    @Transactional
    public CloseResult close(String ym) {
        PeriodYm p = CstErrors.period(ym);
        jdbc.ensurePartition(p.value());
        String status = jdbc.lockPeriod(p.value()).orElse("OPEN");
        if ("CLOSED".equals(status)) {
            throw new DomainException(CstErrors.PERIOD_STATE, ym + " 은 이미 마감되었습니다");
        }
        List<String> blockers = new ArrayList<>();
        var period = jdbc.period(p.value()).orElseThrow();
        if (period.hrqClosedAt() == null) {
            blockers.add("HRQ_MONTH_NOT_CLOSED");
        }
        String recon = jdbc.latestReconciliation(p.value()).map(r -> (String) r.get("status")).orElse(null);
        if (!"MATCHED".equals(recon)) {
            blockers.add(recon == null ? "MANDAY_NOT_RECONCILED" : "MANDAY_RECONCILIATION_MISMATCH");
        }
        if (!blockers.isEmpty()) {
            return new CloseResult(p.value(), false, List.copyOf(blockers));
        }
        jdbc.closePeriod(p.value(), actor.actorIdOrSystem());
        jdbc.ensurePartition(p.next().value());
        outbox.publish(new CstEvents.PeriodClosed(p.value()));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("periodYm", p.value());
        after.put("status", "CLOSED");
        audit.append("CST", AuditLog.Action.APPROVE, "AccountingPeriod", null, Map.of("status", status), after);
        return new CloseResult(p.value(), true, List.of());
    }

    @Transactional
    public void reopen(String ym, String reason) {
        PeriodYm p = CstErrors.period(ym);
        if (reason == null || reason.isBlank()) {
            throw new DomainException(CstErrors.INVALID, "재오픈 사유 필수");
        }
        String status = jdbc.lockPeriod(p.value()).orElse("OPEN");
        if (!"CLOSED".equals(status)) {
            throw new DomainException(CstErrors.PERIOD_STATE, ym + " 은 마감 상태가 아닙니다(" + status + ")");
        }
        jdbc.reopenPeriod(p.value(), reason, actor.actorIdOrSystem());
        audit.append("CST", AuditLog.Action.UPDATE, "AccountingPeriod", null, Map.of("status", "CLOSED"),
                Map.of("periodYm", p.value(), "status", "REOPENED", "reason", reason));
    }
}
