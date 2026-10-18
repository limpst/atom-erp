package com.atomeng.erp.eqp.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import com.atomeng.erp.eqp.api.EqpEvents;
import com.atomeng.erp.eqp.domain.EqpRules;
import com.atomeng.erp.eqp.domain.EquipmentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QR 반출 · 반납 (FR-EQP-007·008, BR-EQP-004·006, 04-IMPL Step 8 · 설계 B5.1).
 * <ul>
 *   <li>QR 서명 검증 → 장비 행 잠금(FOR UPDATE) → clientOpId 재전송이면 같은 결과(TC-EQP-004)</li>
 *   <li>반출일(outAt, KST) 기준 단일 판정기 → 무효면 409 + 대체 장비(TC-EQP-001), 앤빌값 → 422(TC-EQP-007)</li>
 *   <li>이미 활성 반출이 있으면(오프라인 두 기기) sync_conflict=true 로 기록 + 202 + {@code eqp.checkout.conflicted}(TC-EQP-003)</li>
 *   <li>활성 반출 유일성은 부분 유니크 인덱스 ux_active_checkout 이 최종 보장(NFR-EQP-05)</li>
 *   <li>반납: 사용 일수(KST 양 끝 포함) → {@code eqp.equipment.returned} 1회(CST 멱등키 rentalId = checkoutId)</li>
 * </ul>
 */
@Service
public class CheckoutService {

    public record Checkout(UUID clientOpId, String qrToken, UUID projectId, UUID reservationId, Instant outAt,
                           BigDecimal lat, BigDecimal lng, Map<String, Object> preCheck) {}

    public record Return(Instant inAt, BigDecimal lat, BigDecimal lng, Map<String, Object> postCheck) {}

    /** status: OK(200) · REPLAYED(200) · CONFLICT_RECORDED(202) */
    public record Result(String status, Map<String, Object> body) {}

    private final EqpJdbc eqp;
    private final EquipmentService equipment;
    private final CalibrationValidityService validity;
    private final AlternativeFinder alternatives;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final ObjectMapper om;
    private final Clock clock;

    public CheckoutService(EqpJdbc eqp, EquipmentService equipment, CalibrationValidityService validity,
                           AlternativeFinder alternatives, OutboxPublisher outbox, AuditLog audit, CurrentActor actor,
                           ObjectMapper om, Clock clock) {
        this.clock = clock;
        this.eqp = eqp;
        this.equipment = equipment;
        this.validity = validity;
        this.alternatives = alternatives;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.om = om;
    }

    @Transactional
    public Result checkout(Checkout c) {
        UUID by = actor.actorIdOrSystem();
        EquipmentRow scanned = equipment.verifyQr(c.qrToken());
        EquipmentRow e = eqp.lock(scanned.id());           // 같은 장비 반출 직렬화 → 먼저 도착한 건이 정상
        var existing = eqp.jdbc().queryForList("SELECT id, equipment_id FROM eqp.checkout WHERE client_op_id = ?",
                c.clientOpId());
        if (!existing.isEmpty()) {
            UUID prevEq = (UUID) existing.get(0).get("equipment_id");
            if (!prevEq.equals(e.id())) {
                throw new DomainException(EqpErrors.INVALID, "clientOpId 가 다른 장비 반출에 이미 쓰였습니다");
            }
            return new Result("REPLAYED", body((UUID) existing.get(0).get("id"), null));
        }
        Instant outAt = c.outAt() == null ? clock.instant() : c.outAt();
        LocalDate on = LocalDate.ofInstant(outAt, EquipmentService.SEOUL);
        ValidityResult v = validity.validate(e.id(), on, c.projectId());
        if (!v.valid()) {
            throw CalibrationValidityService.blocked(e.id(), v, alternatives.find(e.typeId(), on, on, e.id(),
                    c.projectId(), null));
        }
        if (e.status() == EquipmentStatus.CAL_DUE && !"OVERRIDE".equals(v.basis())) {
            throw new DomainException(EqpErrors.STATE, "교정대기(CAL_DUE) 장비는 QA 예외 승인으로만 반출: " + e.code());
        }
        checkAnvil(e, on, c.preCheck());

        var active = eqp.jdbc().queryForList("""
                SELECT id FROM eqp.checkout WHERE equipment_id = ? AND in_at IS NULL AND NOT sync_conflict""",
                UUID.class, e.id());
        UUID id = UuidV7.next();
        if (!active.isEmpty()) {
            insert(id, e, c, by, outAt, null, v, true);
            outbox.publish(new EqpEvents.CheckoutConflicted(id, e.id(), c.projectId(), active.get(0)));
            audit.append("EQP", AuditLog.Action.CREATE, "checkout-conflict", id, null,
                    Map.of("equipmentId", e.id(), "activeCheckoutId", active.get(0), "clientOpId", c.clientOpId()));
            return new Result("CONFLICT_RECORDED", body(id, v));
        }
        UUID reservationId = matchReservation(e, c, on);
        insert(id, e, c, by, outAt, reservationId, v, false);
        if (reservationId != null) {
            eqp.jdbc().update("UPDATE eqp.reservation SET status = 'IN_USE', version = version + 1, updated_at = now() "
                    + "WHERE id = ?", reservationId);
        }
        eqp.transition(e, EquipmentStatus.CHECKED_OUT, "CHECKOUT", id, by);
        outbox.publish(new EqpEvents.EquipmentCheckedOut(id, e.id(), c.projectId(), by, outAt));
        return new Result("OK", body(id, v));
    }

    private void insert(UUID id, EquipmentRow e, Checkout c, UUID by, Instant outAt, UUID reservationId,
                        ValidityResult v, boolean conflict) {
        eqp.jdbc().update("""
                INSERT INTO eqp.checkout(id, equipment_id, reservation_id, project_id, out_by, out_at, out_lat, out_lng,
                                         pre_check, calibration_id, override_id, client_op_id, sync_conflict)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
                id, e.id(), reservationId, c.projectId(), by, Timestamp.from(outAt), c.lat(), c.lng(),
                json(c.preCheck()), v.calibrationId(), v.overrideId(), c.clientOpId(), conflict);
    }

    /** 예약 매칭: 지정 예약 → 검증, 미지정 → 같은 프로젝트의 그날 활성 예약. 다른 프로젝트 예약 기간이면 409 */
    private UUID matchReservation(EquipmentRow e, Checkout c, LocalDate on) {
        var rows = eqp.jdbc().queryForList("""
                SELECT id, project_id FROM eqp.reservation
                 WHERE equipment_id = ? AND status IN ('REQUESTED','CONFIRMED') AND period @> ?::date""", e.id(),
                EqpJdbc.d(on));
        if (c.reservationId() != null) {
            return rows.stream().filter(r -> r.get("id").equals(c.reservationId())
                            && r.get("project_id").equals(c.projectId())).map(r -> (UUID) r.get("id")).findFirst()
                    .orElseThrow(() -> new DomainException(EqpErrors.INVALID,
                            "예약이 이 장비 · 프로젝트 · 반출일과 맞지 않습니다: " + c.reservationId()));
        }
        for (var r : rows) {
            if (!r.get("project_id").equals(c.projectId())) {
                throw new DomainException(EqpErrors.RESERVED_BY_OTHER, "다른 프로젝트가 예약한 기간입니다")
                        .with("reservationId", r.get("id"));
            }
        }
        return rows.isEmpty() ? null : (UUID) rows.get(0).get("id");
    }

    /** BR-EQP-006: 유형 규칙의 pre_use_check.anvil {target, tol} — 값 누락 422, 범위 이탈 422 + 교정 의뢰 권고 */
    private void checkAnvil(EquipmentRow e, LocalDate on, Map<String, Object> preCheck) {
        List<String> rule = eqp.jdbc().queryForList("""
                SELECT pre_use_check::text FROM eqp.calibration_rule
                 WHERE type_id = ? AND effective_from <= ? AND (effective_to IS NULL OR ? < effective_to)
                   AND pre_use_check -> 'anvil' IS NOT NULL""", String.class, e.typeId(), EqpJdbc.d(on), EqpJdbc.d(on));
        if (rule.isEmpty()) {
            return;
        }
        JsonNode anvil;
        try {
            anvil = om.readTree(rule.get(0)).get("anvil");
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("calibration_rule.pre_use_check JSON 오류", ex);
        }
        double target = anvil.get("target").asDouble();
        double tol = anvil.get("tol").asDouble();
        Object raw = preCheck == null ? null : preCheck.get("anvilValue");
        if (!(raw instanceof Number n)) {
            throw new DomainException(EqpErrors.INVALID, "테스트 앤빌 값(preCheck.anvilValue) 필수 — 기준 %s±%s"
                    .formatted(fmt(target), fmt(tol)));
        }
        if (!EqpRules.anvilWithin(n.doubleValue(), target, tol)) {
            throw new DomainException(EqpErrors.ANVIL_OUT_OF_RANGE, "앤빌값 %s — 기준 %s±%s 이탈, 교정 의뢰 권고"
                    .formatted(fmt(n.doubleValue()), fmt(target), fmt(tol)))
                    .with("recommendation", "CALIBRATION_REQUEST").with("target", target).with("tolerance", tol);
        }
    }

    private static String fmt(double x) {
        return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x);
    }

    @Transactional
    public Map<String, Object> returnEquipment(UUID checkoutId, Return r) {
        UUID by = actor.actorIdOrSystem();
        var rows = eqp.jdbc().queryForList("SELECT equipment_id FROM eqp.checkout WHERE id = ?", UUID.class, checkoutId);
        if (rows.isEmpty()) {
            throw new DomainException(EqpErrors.NOT_FOUND, "반출 없음: " + checkoutId);
        }
        EquipmentRow e = eqp.lock(rows.get(0));
        var co = eqp.jdbc().queryForMap("""
                SELECT project_id, reservation_id, out_at, in_at, sync_conflict FROM eqp.checkout WHERE id = ? FOR UPDATE""",
                checkoutId);
        if (co.get("in_at") != null) {
            throw new DomainException(EqpErrors.ALREADY_RETURNED, "이미 반납된 반출: " + checkoutId);
        }
        if ((Boolean) co.get("sync_conflict")) {
            throw new DomainException(EqpErrors.STATE, "충돌로 기록된 반출은 PM 해결 대상입니다(반납 불가)");
        }
        Instant outAt = ((Timestamp) co.get("out_at")).toInstant();
        Instant inAt = r.inAt() == null ? clock.instant() : r.inAt();
        if (inAt.isBefore(outAt)) {
            throw new DomainException(EqpErrors.INVALID, "반납 시각이 반출 시각보다 빠릅니다");
        }
        LocalDate outOn = LocalDate.ofInstant(outAt, EquipmentService.SEOUL);
        LocalDate inOn = LocalDate.ofInstant(inAt, EquipmentService.SEOUL);
        int days = EqpRules.usageDays(outOn, inOn);
        Map<String, Object> post = r.postCheck() == null ? Map.of() : r.postCheck();
        boolean abnormal = Boolean.TRUE.equals(post.get("abnormal"));
        eqp.jdbc().update("""
                UPDATE eqp.checkout SET in_by = ?, in_at = ?, in_lat = ?, in_lng = ?, post_check = ?::jsonb, usage_days = ?
                 WHERE id = ?""", by, Timestamp.from(inAt), r.lat(), r.lng(), json(post), days, checkoutId);
        UUID projectId = (UUID) co.get("project_id");
        UUID reservationId = (UUID) co.get("reservation_id");
        if (reservationId != null) {
            eqp.jdbc().update("UPDATE eqp.reservation SET status = 'RETURNED', version = version + 1, updated_at = now() "
                    + "WHERE id = ? AND status IN ('IN_USE','OVERDUE')", reservationId);
        }
        UUID faultId = null;
        if (abnormal) {   // 사후 점검 이상 → 고장 신고 연계(FR-EQP-008)
            faultId = UuidV7.next();
            Object symptom = post.getOrDefault("symptom", "반납 점검 이상");
            eqp.jdbc().update("""
                    INSERT INTO eqp.fault(id, equipment_id, checkout_id, symptom, reported_by) VALUES (?, ?, ?, ?, ?)""",
                    faultId, e.id(), checkoutId, String.valueOf(symptom), by);
            eqp.transition(e, EquipmentStatus.BROKEN, "RETURN_FAULT", checkoutId, by);
            outbox.publish(new EqpEvents.FaultReported(faultId, e.id(), checkoutId));
        } else {
            eqp.transition(e, EquipmentStatus.AVAILABLE, "RETURN", checkoutId, by);
        }
        outbox.publish(new EqpEvents.EquipmentReturned(checkoutId, e.id(), e.code(), projectId, days, inOn));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkoutId", checkoutId);
        m.put("equipmentId", e.id());
        m.put("equipmentStatus", eqp.get(e.id()).status());
        m.put("usageDays", days);
        m.put("returnedOn", inOn);
        m.put("faultId", faultId);
        return m;
    }

    private Map<String, Object> body(UUID checkoutId, ValidityResult v) {
        var co = eqp.jdbc().queryForMap("""
                SELECT c.id, c.equipment_id, c.project_id, c.reservation_id, c.out_at, c.sync_conflict, c.calibration_id,
                       c.override_id, e.status FROM eqp.checkout c JOIN eqp.equipment e ON e.id = c.equipment_id
                 WHERE c.id = ?""", checkoutId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkoutId", co.get("id"));
        m.put("equipmentId", co.get("equipment_id"));
        m.put("projectId", co.get("project_id"));
        m.put("reservationId", co.get("reservation_id"));
        m.put("syncConflict", co.get("sync_conflict"));
        m.put("equipmentStatus", co.get("status"));
        if (v != null) {
            Map<String, Object> cal = new LinkedHashMap<>();
            cal.put("basis", v.basis());
            cal.put("validUntil", v.validUntil());
            cal.put("daysLeft", v.validUntil() == null ? null
                    : ChronoUnit.DAYS.between(equipment.today(), v.validUntil()));
            cal.put("calibrationId", v.calibrationId());
            cal.put("overrideId", v.overrideId());
            m.put("calibration", cal);
        }
        return m;
    }

    private String json(Map<String, Object> m) {
        try {
            return om.writeValueAsString(m == null ? Map.of() : m);
        } catch (JsonProcessingException e) {
            throw new DomainException(EqpErrors.INVALID, "점검표 JSON 오류");
        }
    }
}
