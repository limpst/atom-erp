package com.atomeng.erp.hrq.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc.CredentialRow;
import com.atomeng.erp.hrq.api.HrqEvents;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 자격 등록·승인·만료 스캔 (03-IMPL Step 4·5, FR-HRQ-004·006, BR-HRQ-005, A6.1, TC-HRQ-006) */
@Service
public class CredentialService {

    private static final Logger log = LoggerFactory.getLogger(CredentialService.class);

    public record ScanResult(int notified, int expiring, int expired) {}

    private final HrqJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;
    private final List<Integer> thresholds;

    public CredentialService(HrqJdbc jdbc, OutboxPublisher outbox, AuditLog audit, CurrentActor actor, Clock clock,
                             @Value("${atom.hrq.expiry.thresholds:90,60,30,7}") List<Integer> thresholds) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
        this.thresholds = thresholds.stream().sorted().toList(); // 7, 30, 60, 90
    }

    @Transactional
    public UUID register(UUID empId, String typeCode, String certNo, LocalDate acquiredOn, LocalDate expiresOn) {
        jdbc.employee(empId).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "기술자 없음: " + empId));
        if (expiresOn != null && !expiresOn.isAfter(acquiredOn)) {
            throw new DomainException(HrqErrors.INVALID, "만료일은 취득일 이후");
        }
        return jdbc.insertCredential(empId, typeCode, certNo, acquiredOn, expiresOn, actor.actorIdOrSystem()); // PENDING
    }

    /** PENDING → VALID(D-90 이내면 EXPIRING), 같은 종목 이전 레코드 SUPERSEDED, 감사 */
    @Transactional
    public CredentialRow approve(UUID id) {
        CredentialRow c = jdbc.lockCredential(id).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "자격 없음"));
        if (!"PENDING".equals(c.status())) {
            throw new DomainException(HrqErrors.INVALID_STATE, "PENDING 만 승인 가능(현재 " + c.status() + ")");
        }
        EmployeeService.requireNotSelf(c.employeeId(), actor.actorIdOrSystem(), "본인 자격은 승인할 수 없습니다(HRQ-06)");
        LocalDate today = HrqErrors.today(clock);
        String next = c.expiresOn() == null ? "VALID"
                : c.expiresOn().isBefore(today) ? "EXPIRED"
                : ChronoUnit.DAYS.between(today, c.expiresOn()) <= thresholds.get(thresholds.size() - 1) ? "EXPIRING"
                : "VALID";
        UUID by = actor.actorIdOrSystem();
        jdbc.setCredentialStatus(id, next, by, null, true);
        jdbc.supersedeOthers(c.employeeId(), c.typeCode(), id, by);
        audit.append("HRQ", AuditLog.Action.APPROVE, "Credential", id, Map.of("status", "PENDING"),
                Map.of("status", next, "typeCode", c.typeCode()));
        return jdbc.lockCredential(id).orElseThrow();
    }

    @Transactional
    public void reject(UUID id, String reason) {
        CredentialRow c = jdbc.lockCredential(id).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "자격 없음"));
        if (!"PENDING".equals(c.status())) {
            throw new DomainException(HrqErrors.INVALID_STATE, "PENDING 만 반려 가능");
        }
        EmployeeService.requireNotSelf(c.employeeId(), actor.actorIdOrSystem(), "본인 자격은 반려할 수 없습니다(HRQ-06)");
        if (reason == null || reason.isBlank()) {
            throw new DomainException(HrqErrors.INVALID, "반려 사유 필수");
        }
        jdbc.setCredentialStatus(id, "REJECTED", actor.actorIdOrSystem(), reason, false);
        audit.append("HRQ", AuditLog.Action.UPDATE, "Credential", id, Map.of("status", "PENDING"),
                Map.of("status", "REJECTED", "reason", reason));
    }

    /**
     * 매일 06:00 만료 스캔. VALID→EXPIRING(D-90), →EXPIRED(+hrq.credential.expired 1회),
     * D-90/60/30/7 알림은 notification_log 멱등키 {credentialId}:{threshold} — 남은 일수 이하의 가장 작은
     * threshold 하나만(배치가 하루 빠져도 보충 발송). 같은 날 2회 실행해도 알림 1회(TC-HRQ-006).
     */
    @Transactional
    public ScanResult scan() {
        LocalDate today = HrqErrors.today(clock);
        int maxT = thresholds.get(thresholds.size() - 1);
        int expiring = jdbc.markExpiring(today, maxT);
        var expired = jdbc.markExpired(today);
        expired.forEach(c -> outbox.publish(new HrqEvents.CredentialExpired(c.employeeId(), c.id(), c.typeCode(),
                c.expiresOn())));
        int notified = 0;
        for (CredentialRow c : jdbc.expiringWithin(today, maxT)) {
            long daysLeft = ChronoUnit.DAYS.between(today, c.expiresOn());
            Integer t = thresholds.stream().filter(x -> daysLeft <= x).findFirst().orElse(null);
            if (t != null && jdbc.insertNotificationIfAbsent(c.id() + ":" + t, c.id(), t)) {
                // 알림 채널(메일·메신저·FCM)은 Step 5 후속 — 지금은 멱등 기록 + 로그
                log.info("자격 만료 알림 D-{} credential={} employee={}", t, c.id(), c.employeeId());
                notified++;
            }
        }
        return new ScanResult(notified, expiring, expired.size());
    }
}
