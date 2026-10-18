package com.atomeng.erp.eqp.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.EqpEvents;
import com.atomeng.erp.eqp.domain.EqpRules;
import com.atomeng.erp.eqp.domain.EquipmentStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검교정 등록 · Override (FR-EQP-003, BR-EQP-003, 04-IMPL Step 6).
 * 차기일 누락 → 규칙 주기(calibration_rule, 교정일 기준 유효 규칙)로 추정 + next_due_estimated (A8-3).
 * 상태: PASS/LIMITED 이고 오늘을 포함하면 AVAILABLE 로(전이표 경로), FAIL 이면 BROKEN 으로(반출 중이면 유지 — 반출 · 측정은 판정이 차단).
 */
@Service
public class CalibrationService {

    public record Record(LocalDate calibratedOn, LocalDate nextDueOn, String result, String certNo, String labName,
                         String labAccreditationNo, Map<String, Object> uncertainty, UUID certAttachmentId,
                         String certSha256, BigDecimal cost) {}

    private final EqpJdbc eqp;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final EquipmentService equipment;
    private final ObjectMapper om;

    public CalibrationService(EqpJdbc eqp, OutboxPublisher outbox, AuditLog audit, CurrentActor actor,
                              EquipmentService equipment, ObjectMapper om) {
        this.eqp = eqp;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.equipment = equipment;
        this.om = om;
    }

    @Transactional
    public Map<String, Object> record(UUID equipmentId, Record r) {
        UUID by = actor.actorIdOrSystem();
        EquipmentRow e = eqp.lock(equipmentId);
        if (e.status() == EquipmentStatus.DISPOSED) {
            throw new DomainException(EqpErrors.STATE, "폐기 장비: " + e.code());
        }
        if (!List.of("PASS", "FAIL", "LIMITED").contains(r.result())) {
            throw new DomainException(EqpErrors.INVALID, "result: PASS | FAIL | LIMITED");
        }
        LocalDate next = r.nextDueOn();
        boolean estimated = false;
        if (next == null) {
            List<Integer> interval = eqp.jdbc().queryForList("""
                    SELECT interval_months FROM eqp.calibration_rule
                     WHERE type_id = ? AND effective_from <= ? AND (effective_to IS NULL OR ? < effective_to)""",
                    Integer.class, e.typeId(), EqpJdbc.d(r.calibratedOn()), EqpJdbc.d(r.calibratedOn()));
            if (interval.isEmpty()) {
                throw new DomainException(EqpErrors.INVALID, "차기 교정일이 없고 적용할 규칙도 없습니다: " + e.typeCode());
            }
            next = EqpRules.estimateNextDue(r.calibratedOn(), interval.get(0));
            estimated = true;
        }
        if (!next.isAfter(r.calibratedOn())) {
            throw new DomainException(EqpErrors.INVALID, "차기 교정일은 교정일 이후여야 합니다");
        }
        UUID id = UuidV7.next();
        eqp.jdbc().update("""
                INSERT INTO eqp.calibration(id, equipment_id, calibrated_on, next_due_on, next_due_estimated, result, cert_no,
                                            lab_name, lab_accreditation_no, uncertainty, cert_attachment_id, cert_sha256,
                                            cost, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
                id, equipmentId, EqpJdbc.d(r.calibratedOn()), EqpJdbc.d(next), estimated, r.result(), r.certNo(),
                r.labName(), r.labAccreditationNo(), json(r.uncertainty()), r.certAttachmentId(), r.certSha256(),
                r.cost(), by);

        LocalDate today = equipment.today();
        boolean coversToday = !r.calibratedOn().isAfter(today) && !next.isBefore(today);
        if (!r.result().equals("FAIL") && coversToday) {
            List<EquipmentStatus> path = switch (e.status()) {
                case REGISTERED, IN_CALIBRATION -> List.of(EquipmentStatus.AVAILABLE);
                case CAL_DUE -> List.of(EquipmentStatus.IN_CALIBRATION, EquipmentStatus.AVAILABLE);
                default -> List.of();
            };
            eqp.transitionPath(e, path, "CAL_PASS", id, by);
        } else if (r.result().equals("FAIL")) {
            List<EquipmentStatus> path = switch (e.status()) {
                case REGISTERED, CAL_DUE -> List.of(EquipmentStatus.IN_CALIBRATION, EquipmentStatus.BROKEN);
                case IN_CALIBRATION -> List.of(EquipmentStatus.BROKEN);
                case AVAILABLE, RESERVED -> List.of(EquipmentStatus.CAL_DUE, EquipmentStatus.IN_CALIBRATION,
                        EquipmentStatus.BROKEN);
                default -> List.of();
            };
            eqp.transitionPath(e, path, "CAL_FAIL", id, by);
        }
        outbox.publish(new EqpEvents.CalibrationRecorded(equipmentId, id, r.result(), r.calibratedOn(), next, estimated));
        Map<String, Object> view = view(id);
        audit.append("EQP", AuditLog.Action.CREATE, "calibration", id, null, view);
        return view;
    }

    public Map<String, Object> view(UUID calibrationId) {
        return new LinkedHashMap<>(eqp.jdbc().queryForMap("""
                SELECT id, equipment_id, calibrated_on, next_due_on AS valid_until, next_due_estimated, result, cert_no,
                       lab_name, lab_accreditation_no, cert_sha256, cost FROM eqp.calibration WHERE id = ?""",
                calibrationId));
    }

    public List<Map<String, Object>> list(UUID equipmentId) {
        eqp.get(equipmentId);
        return eqp.jdbc().queryForList("""
                SELECT id, calibrated_on, next_due_on AS valid_until, next_due_estimated, result, cert_no, lab_name,
                       lab_accreditation_no, cert_sha256 FROM eqp.calibration
                 WHERE equipment_id = ? ORDER BY calibrated_on DESC, created_at DESC""", equipmentId);
    }

    /** BR-EQP-003: QA 만, 사유 필수, 최대 7일(양 끝 포함, TC-EQP-008 → 400), 감사 로그 APPROVE */
    @Transactional
    public Map<String, Object> override(UUID equipmentId, UUID projectId, String reason, LocalDate from, LocalDate to) {
        UUID by = actor.actorIdOrSystem();
        eqp.get(equipmentId);
        if (from == null || to == null || !EqpRules.overridePeriodOk(from, to)) {
            throw new DomainException(EqpErrors.OVERRIDE_TOO_LONG,
                    "예외 승인 유효기간은 최대 %d일입니다".formatted(EqpRules.OVERRIDE_MAX_DAYS));
        }
        if (reason == null || reason.strip().length() < 5) {
            throw new DomainException(EqpErrors.INVALID, "예외 승인 사유(5자 이상) 필수");
        }
        UUID id = UuidV7.next();
        eqp.jdbc().update("""
                INSERT INTO eqp.calibration_override(id, equipment_id, project_id, reason, valid_from, valid_to, approved_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, equipmentId, projectId, reason.strip(), EqpJdbc.d(from),
                EqpJdbc.d(to), by);
        Map<String, Object> view = new LinkedHashMap<>(eqp.jdbc().queryForMap("""
                SELECT id, equipment_id, project_id, reason, valid_from, valid_to, approved_by
                  FROM eqp.calibration_override WHERE id = ?""", id));
        audit.append("EQP", AuditLog.Action.APPROVE, "calibration-override", id, null, view);
        return view;
    }

    private String json(Map<String, Object> m) {
        if (m == null) {
            return null;
        }
        try {
            return om.writeValueAsString(m);
        } catch (JsonProcessingException e) {
            throw new DomainException(EqpErrors.INVALID, "uncertainty JSON 오류");
        }
    }
}
