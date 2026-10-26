package com.atomeng.erp.eqp.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.EqpEvents;
import com.atomeng.erp.eqp.application.port.PilotQualificationPort;
import com.atomeng.erp.eqp.domain.Geo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 드론 기체 · 비행승인 · 비행로그 (FR-EQP-016·017·018, BR-EQP-007, 04-IMPL Step 11).
 * 비행로그 등록 조건: ① 보험 유효(비행일 KST) ② 조종자 자격 유효(포트, 장애 시 Fail-closed) — 위반은 422.
 * 승인 기간 밖 → 경고 플래그, 경로가 승인 구역 밖 → out_of_area=true (TC-EQP-010). 등록 후 {@code eqp.flight.logged}.
 */
@Service
public class DroneService {

    public record Profile(String registrationNo, BigDecimal mtowKg, String insurancePolicyNo,
                          LocalDate insuranceExpiresOn, Map<String, Object> batteryCycles) {}

    public record Approval(UUID projectId, String approvalNo, List<double[]> area, Integer maxAltM, Instant validFrom,
                           Instant validTo, String photoPermitNo) {}

    public record Flight(UUID projectId, UUID approvalId, UUID pilotId, Instant startedAt, Instant endedAt,
                         List<double[]> track, BigDecimal maxAltM, UUID rawLogAttachmentId) {}

    private static final TypeReference<List<double[]>> POINTS = new TypeReference<>() {};

    private final EqpJdbc eqp;
    private final PilotQualificationPort pilots;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final ObjectMapper om;
    private final EqpAccess access;

    public DroneService(EqpJdbc eqp, PilotQualificationPort pilots, OutboxPublisher outbox, AuditLog audit,
                        CurrentActor actor, ObjectMapper om, EqpAccess access) {
        this.access = access;
        this.eqp = eqp;
        this.pilots = pilots;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.om = om;
    }

    @Transactional
    public Map<String, Object> upsertProfile(UUID equipmentId, Profile p) {
        UUID by = actor.actorIdOrSystem();
        EquipmentRow e = eqp.get(equipmentId);
        if (!"UAV".equals(e.typeCode())) {
            throw new DomainException(EqpErrors.INVALID, "드론(UAV) 유형 장비만 기체 프로필을 가질 수 있습니다: " + e.code());
        }
        try {
            eqp.jdbc().update("""
                    INSERT INTO eqp.drone_profile(equipment_id, registration_no, mtow_kg, insurance_policy_no,
                                                  insurance_expires_on, battery_cycles, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                    ON CONFLICT (equipment_id) DO UPDATE SET registration_no = EXCLUDED.registration_no,
                        mtow_kg = EXCLUDED.mtow_kg, insurance_policy_no = EXCLUDED.insurance_policy_no,
                        insurance_expires_on = EXCLUDED.insurance_expires_on, battery_cycles = EXCLUDED.battery_cycles,
                        updated_at = now(), updated_by = EXCLUDED.updated_by""",
                    equipmentId, p.registrationNo(), p.mtowKg(), p.insurancePolicyNo(),
                    EqpJdbc.d(p.insuranceExpiresOn()), p.batteryCycles() == null ? null : json(p.batteryCycles()), by);
        } catch (DuplicateKeyException ex) {
            throw new DomainException(EqpErrors.INVALID, "이미 다른 기체에 등록된 신고번호: " + p.registrationNo());
        }
        Map<String, Object> view = profile(equipmentId);
        audit.append("EQP", AuditLog.Action.UPDATE, "drone-profile", equipmentId, null, view);
        return view;
    }

    public Map<String, Object> profile(UUID equipmentId) {
        var rows = eqp.jdbc().queryForList("""
                SELECT equipment_id, registration_no, mtow_kg, insurance_policy_no, insurance_expires_on
                  FROM eqp.drone_profile WHERE equipment_id = ?""", equipmentId);
        if (rows.isEmpty()) {
            throw new DomainException(EqpErrors.NOT_FOUND, "드론 프로필 없음: " + equipmentId);
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    @Transactional
    public Map<String, Object> addApproval(UUID equipmentId, Approval a) {
        UUID by = actor.actorIdOrSystem();
        profile(equipmentId);
        if (a.area() == null || a.area().size() < 3 || a.area().stream().anyMatch(p -> p.length < 2)) {
            throw new DomainException(EqpErrors.INVALID, "승인 구역은 [lng, lat] 꼭짓점 3개 이상");
        }
        if (a.validFrom() == null || a.validTo() == null || !a.validTo().isAfter(a.validFrom())) {
            throw new DomainException(EqpErrors.INVALID, "승인 기간 오류");
        }
        UUID id = UuidV7.next();
        eqp.jdbc().update("""
                INSERT INTO eqp.flight_approval(id, equipment_id, project_id, approval_no, area, max_alt_m, valid_from,
                                                valid_to, photo_permit_no, created_by)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""", id, equipmentId, a.projectId(), a.approvalNo(),
                json(a.area()), a.maxAltM(), Timestamp.from(a.validFrom()), Timestamp.from(a.validTo()),
                a.photoPermitNo(), by);
        Map<String, Object> view = new LinkedHashMap<>(eqp.jdbc().queryForMap("""
                SELECT id, equipment_id, project_id, approval_no, max_alt_m, valid_from, valid_to, photo_permit_no
                  FROM eqp.flight_approval WHERE id = ?""", id));
        audit.append("EQP", AuditLog.Action.CREATE, "flight-approval", id, null, view);
        return view;
    }

    @Transactional
    public Map<String, Object> logFlight(UUID equipmentId, Flight f) {
        UUID by = actor.actorIdOrSystem();
        // EQP-03: 조종자 = 요청자 본인. 다른 조종자 대리 등록은 ADMIN 만(웹 요청이 아닌 시스템 실행은 지정값 그대로)
        UUID pilotId = f.pilotId() == null ? by : f.pilotId();
        if (access.principal().isPresent() && !pilotId.equals(by)
                && !access.hasRole(com.atomeng.common.identity.Role.ADMIN)) {
            throw new DomainException(EqpErrors.PILOT_NOT_REQUESTER,
                    "비행기록 조종자(pilotId)는 요청자 본인이어야 합니다(대리 등록은 ADMIN)");
        }
        f = new Flight(f.projectId(), f.approvalId(), pilotId, f.startedAt(), f.endedAt(), f.track(), f.maxAltM(),
                f.rawLogAttachmentId());
        Map<String, Object> profile = profile(equipmentId);
        if (f.startedAt() == null || f.endedAt() == null || !f.endedAt().isAfter(f.startedAt())) {
            throw new DomainException(EqpErrors.INVALID, "비행 시각 오류(종료 > 시작)");
        }
        if (f.track() == null || f.track().isEmpty() || f.track().stream().anyMatch(p -> p.length < 2)) {
            throw new DomainException(EqpErrors.INVALID, "비행 경로는 [lng, lat, alt] 점 1개 이상");
        }
        LocalDate day = LocalDate.ofInstant(f.startedAt(), EquipmentService.SEOUL);
        java.sql.Date ins = (java.sql.Date) profile.get("insurance_expires_on");
        if (ins == null || ins.toLocalDate().isBefore(day)) {
            throw new DomainException(EqpErrors.DRONE_INSURANCE_EXPIRED,
                    "기체 보험 만료(" + (ins == null ? "미등록" : ins.toLocalDate()) + ") — 비행 등록 차단");
        }
        PilotQualificationPort.Result q;
        try {
            q = pilots.check(f.pilotId(), (BigDecimal) profile.get("mtow_kg"), day);
        } catch (RuntimeException ex) {
            throw new DomainException(EqpErrors.PILOT_CHECK_UNAVAILABLE, "조종자 자격 확인 불가 — 비행 등록 보류(Fail-closed)");
        }
        if (q == null || q.status() != PilotQualificationPort.Status.VALID) {
            throw new DomainException(EqpErrors.PILOT_LICENSE_INVALID, "조종자 자격 무효"
                    + (q == null || q.detail() == null ? "" : ": " + q.detail()))
                    .with("reason", "HRQ_LICENSE_INVALID");
        }
        boolean outsidePeriod = false;
        boolean outOfArea = false;
        List<String> warnings = new ArrayList<>();
        if (f.approvalId() != null) {
            var rows = eqp.jdbc().queryForList("""
                    SELECT area::text AS area, valid_from, valid_to FROM eqp.flight_approval
                     WHERE id = ? AND equipment_id = ?""", f.approvalId(), equipmentId);
            if (rows.isEmpty()) {
                throw new DomainException(EqpErrors.INVALID, "이 기체의 비행승인이 아닙니다: " + f.approvalId());
            }
            Instant from = ((Timestamp) rows.get(0).get("valid_from")).toInstant();
            Instant to = ((Timestamp) rows.get(0).get("valid_to")).toInstant();
            outsidePeriod = f.startedAt().isBefore(from) || f.endedAt().isAfter(to);
            if (outsidePeriod) {
                warnings.add("EQP_FLIGHT_OUTSIDE_APPROVAL_PERIOD");
            }
            outOfArea = Geo.anyOutside(f.track(), readPoints((String) rows.get(0).get("area")));
            if (outOfArea) {
                warnings.add("EQP_FLIGHT_OUT_OF_AREA");
            }
        } else {
            warnings.add("EQP_FLIGHT_NO_APPROVAL");
        }
        BigDecimal maxAlt = f.maxAltM() != null ? f.maxAltM()
                : f.track().stream().filter(p -> p.length >= 3).map(p -> BigDecimal.valueOf(p[2]))
                .max(BigDecimal::compareTo).orElse(null);
        UUID id = UuidV7.next();
        eqp.jdbc().update("""
                INSERT INTO eqp.flight_log(id, equipment_id, project_id, approval_id, pilot_id, started_at, ended_at, track,
                                           max_alt_m, raw_log_attachment_id, out_of_area, outside_approval_period,
                                           created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)""", id, equipmentId, f.projectId(), f.approvalId(),
                f.pilotId(), Timestamp.from(f.startedAt()), Timestamp.from(f.endedAt()), json(f.track()), maxAlt,
                f.rawLogAttachmentId(), outOfArea, outsidePeriod, by);
        outbox.publish(new EqpEvents.FlightLogged(id, equipmentId, f.projectId(), f.pilotId(), outOfArea, outsidePeriod));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flightLogId", id);
        m.put("equipmentId", equipmentId);
        m.put("projectId", f.projectId());
        m.put("pilotId", f.pilotId());
        m.put("maxAltM", maxAlt);
        m.put("outOfArea", outOfArea);
        m.put("outsideApprovalPeriod", outsidePeriod);
        m.put("warnings", warnings);
        return m;
    }

    public List<Map<String, Object>> flights(UUID equipmentId) {
        return eqp.jdbc().queryForList("""
                SELECT id, project_id, approval_id, pilot_id, started_at, ended_at, max_alt_m, out_of_area,
                       outside_approval_period FROM eqp.flight_log WHERE equipment_id = ? ORDER BY started_at DESC""",
                equipmentId);
    }

    private List<double[]> readPoints(String json) {
        try {
            return om.readValue(json, POINTS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("flight_approval.area JSON 오류", e);
        }
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new DomainException(EqpErrors.INVALID, "JSON 직렬화 오류");
        }
    }
}
