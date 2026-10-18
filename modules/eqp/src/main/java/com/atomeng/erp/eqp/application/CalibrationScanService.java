package com.atomeng.erp.eqp.application;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import com.atomeng.erp.eqp.api.EqpEvents;
import com.atomeng.erp.eqp.domain.EqpRules;
import com.atomeng.erp.eqp.domain.EquipmentStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 일일 검교정 스캔 (FR-EQP-004, 설계 B6 v0.2, 04-IMPL Step 6 CalibrationDueJob).
 * <ul>
 *   <li>{@code eqp.calibration.due-soon}: 현재 유효 교정의 남은 일수 ≤ 기준(60·30·7·0) — 남은 일수 이하인 가장 작은 기준 1회.
 *       멱등키 CAL_DUE_SOON:{equipmentId}:{nextDueOn}:{threshold} (배치 재실행 · 다중 Pod 에도 1회)</li>
 *   <li>{@code eqp.calibration.expired}: 자기 교정(전역 Override 포함)이 무효가 된 장비 — expiredOn = 마지막 유효 next_due_on + 1.
 *       멱등키 CAL_EXPIRED:{equipmentId}:{expiredOn}. 같은 트랜잭션에서 AVAILABLE · RESERVED → CAL_DUE 전이(이벤트와 별개로 매번 보정)</li>
 * </ul>
 * notification_log INSERT … ON CONFLICT DO NOTHING 이 성공한 경우에만 발행한다 → 두 스캔이 겹쳐도 이벤트 1건.
 */
@Service
public class CalibrationScanService {

    public record ScanResult(LocalDate date, int dueSoonPublished, int expiredPublished, int movedToCalDue) {}

    private final EqpJdbc eqp;
    private final CalibrationValidityService validity;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final List<Integer> thresholds;

    public CalibrationScanService(EqpJdbc eqp, CalibrationValidityService validity, OutboxPublisher outbox,
                                  CurrentActor actor,
                                  @Value("${atom.eqp.calibration-scan.thresholds:60,30,7,0}") List<Integer> thresholds) {
        this.eqp = eqp;
        this.validity = validity;
        this.outbox = outbox;
        this.actor = actor;
        this.thresholds = List.copyOf(thresholds);
    }

    @Transactional
    public ScanResult scan(LocalDate today) {
        UUID by = actor.actorIdOrSystem();
        int maxT = thresholds.stream().max(Integer::compare).orElse(0);
        int dueSoon = 0;
        int expired = 0;
        int moved = 0;
        // 교정 대상 · 사용 중(폐기 · 분실 제외) · 교정 이력 1건 이상인 장비
        List<UUID> ids = eqp.jdbc().queryForList("""
                SELECT e.id FROM eqp.equipment e JOIN eqp.equipment_type t ON t.id = e.type_id
                 WHERE t.calibration_required AND e.status NOT IN ('DISPOSED','LOST')
                   AND EXISTS (SELECT 1 FROM eqp.calibration c WHERE c.equipment_id = e.id AND c.result <> 'FAIL')
                 ORDER BY e.code""", UUID.class);
        for (UUID id : ids) {
            EquipmentRow e = eqp.lock(id);
            ValidityResult v = validity.own(e, today, null, true);
            if (v.valid()) {
                if (!"CALIBRATION".equals(v.basis())) {
                    continue;   // 전역 Override 로 유효 — 만료 알림은 Override 종료 뒤
                }
                long daysLeft = ChronoUnit.DAYS.between(today, v.validUntil());
                if (daysLeft > maxT) {
                    continue;
                }
                Optional<Integer> t = EqpRules.threshold(daysLeft, thresholds);
                if (t.isPresent() && claim("CAL_DUE_SOON:%s:%s:%d".formatted(id, v.validUntil(), t.get()), "CAL_DUE_SOON",
                        id, v.validUntil(), t.get())) {
                    UUID ev = outbox.publish(new EqpEvents.CalibrationDueSoon(id, e.code(), v.validUntil(),
                            (int) daysLeft, t.get()));
                    stamp("CAL_DUE_SOON:%s:%s:%d".formatted(id, v.validUntil(), t.get()), ev);
                    dueSoon++;
                }
                continue;
            }
            if (!"EQP_CAL_EXPIRED".equals(v.reason())) {
                continue;   // FAIL 이후 등 — 만료가 아닌 무효는 calibration.recorded(FAIL) 경로가 처리
            }
            Optional<LocalDate> lastDue = validity.lastValidDue(id, today);
            if (lastDue.isEmpty()) {
                continue;   // 아직 유효 기간 시작 전(미래 교정만 있음)
            }
            if (e.status() == EquipmentStatus.AVAILABLE || e.status() == EquipmentStatus.RESERVED) {
                eqp.transition(e, EquipmentStatus.CAL_DUE, "CAL_EXPIRED", null, by);
                moved++;
            }
            LocalDate expiredOn = lastDue.get().plusDays(1);
            String key = "CAL_EXPIRED:%s:%s".formatted(id, expiredOn);
            if (claim(key, "CAL_EXPIRED", id, expiredOn, null)) {
                List<UUID> checkouts = eqp.jdbc().queryForList("""
                        SELECT id FROM eqp.checkout WHERE equipment_id = ? AND in_at IS NULL AND NOT sync_conflict
                         ORDER BY out_at""", UUID.class, id);
                List<UUID> reservations = eqp.jdbc().queryForList("""
                        SELECT id FROM eqp.reservation WHERE equipment_id = ?
                           AND status IN ('REQUESTED','CONFIRMED','IN_USE','OVERDUE') AND upper(period) > ?
                         ORDER BY lower(period)""", UUID.class, id, EqpJdbc.d(today));
                UUID ev = outbox.publish(new EqpEvents.CalibrationExpired(id, e.code(), expiredOn, checkouts,
                        reservations));
                stamp(key, ev);
                expired++;
            }
        }
        return new ScanResult(today, dueSoon, expired, moved);
    }

    private boolean claim(String key, String kind, UUID equipmentId, LocalDate refDate, Integer threshold) {
        return eqp.jdbc().update("""
                INSERT INTO eqp.notification_log(idempotency_key, kind, equipment_id, ref_date, threshold_days)
                VALUES (?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING""",
                key, kind, equipmentId, EqpJdbc.d(refDate), threshold) == 1;
    }

    private void stamp(String key, UUID eventId) {
        eqp.jdbc().update("UPDATE eqp.notification_log SET event_id = ? WHERE idempotency_key = ?", eventId, key);
    }
}
