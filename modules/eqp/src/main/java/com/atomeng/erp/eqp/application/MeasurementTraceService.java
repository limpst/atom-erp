package com.atomeng.erp.eqp.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.erp.eqp.api.CalibrationValidityPort;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import com.atomeng.erp.eqp.api.MeasurementTracePort;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** {@link MeasurementTracePort} 구현 — eqp.measurement_trace INSERT ONLY (V4_0_2) */
@Service
public class MeasurementTraceService implements MeasurementTracePort {

    private final JdbcTemplate jdbc;
    private final CalibrationValidityPort validity;
    private final CurrentActor actor;
    private final Clock clock;

    public MeasurementTraceService(JdbcTemplate jdbc, CalibrationValidityPort validity, CurrentActor actor, Clock clock) {
        this.jdbc = jdbc;
        this.validity = validity;
        this.actor = actor;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, noRollbackFor = TraceRejectedException.class)
    public Trace recordTrace(TraceCommand c) {
        List<Map<String, Object>> existing = jdbc.queryForList("""
                SELECT id, basis, calibration_id, override_id, cert_no, valid_until FROM eqp.measurement_trace
                 WHERE source_module = ? AND source_id = ?""", c.sourceModule(), c.sourceId());
        if (!existing.isEmpty()) {
            Map<String, Object> r = existing.get(0);
            Date vu = (Date) r.get("valid_until");
            return new Trace((UUID) r.get("id"), (String) r.get("basis"), (UUID) r.get("calibration_id"),
                    (UUID) r.get("override_id"), (String) r.get("cert_no"), vu == null ? null : vu.toLocalDate(), true);
        }
        ValidityResult v = validity.validate(c.equipmentId(), c.measuredOn(), c.projectId());
        if (!v.valid()) {
            throw new TraceRejectedException(v.reason(), "측정일 기준 장비 무효: " + v.reason()
                    + (v.detail() == null ? "" : " (" + v.detail() + ")"));
        }
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO eqp.measurement_trace(id, equipment_id, project_id, measured_on, source_module, source_type,
                                                  source_id, item, value, unit, basis, calibration_id, override_id,
                                                  cert_no, valid_until, recorded_at, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, c.equipmentId(), c.projectId(),
                Date.valueOf(c.measuredOn()), c.sourceModule(), c.sourceType(), c.sourceId(), c.item(), c.value(),
                c.unit(), v.basis(), v.calibrationId(), v.overrideId(), v.certNo(),
                v.validUntil() == null ? null : Date.valueOf(v.validUntil()), Timestamp.from(clock.instant()),
                actor.actorIdOrSystem());
        return new Trace(id, v.basis(), v.calibrationId(), v.overrideId(), v.certNo(), v.validUntil(), false);
    }
}
