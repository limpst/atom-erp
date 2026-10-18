package com.atomeng.erp.eqp.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.CalibrationValidityPort;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검교정 유효성 판정 단일 진입점 (설계 B7.1, 04-IMPL Step 6) — 예약 · 반출 · (후속) 측정 확정이 모두 이 판정을 쓴다.
 * <ol>
 *   <li>폐기 · 분실 · 고장 · 수리 중 → EQP_NOT_USABLE</li>
 *   <li>키트: 자식(폐기 제외) 중 하나라도 무효 → EQP_KIT_CHILD_INVALID (TC-EQP-006), 순환 → EQP_KIT_CYCLE</li>
 *   <li>교정 비대상 유형 → NOT_REQUIRED</li>
 *   <li>측정일을 포함하는(calibrated_on ≤ on ≤ next_due_on) 최신 PASS/LIMITED 교정 — 그 뒤 on 까지 FAIL 이 있으면 EQP_CAL_FAILED_AFTER</li>
 *   <li>QA Override(프로젝트 한정 또는 전역) → OVERRIDE</li>
 *   <li>그 외 EQP_CAL_EXPIRED (TC-EQP-001)</li>
 * </ol>
 * 경계값: on = calibrated_on 유효, on = next_due_on 유효, next_due_on + 1 무효.
 */
@Service
@Transactional(readOnly = true)
public class CalibrationValidityService implements CalibrationValidityPort {

    private final EqpJdbc eqp;
    private final JdbcTemplate jdbc;

    public CalibrationValidityService(EqpJdbc eqp) {
        this.eqp = eqp;
        this.jdbc = eqp.jdbc();
    }

    @Override
    public ValidityResult validate(UUID equipmentId, LocalDate on, UUID projectId) {
        return validate(equipmentId, on, projectId, new HashSet<>());
    }

    private ValidityResult validate(UUID id, LocalDate on, UUID projectId, Set<UUID> visited) {
        if (!visited.add(id)) {
            return ValidityResult.invalid("EQP_KIT_CYCLE", "키트 구성 순환: " + id);
        }
        EquipmentRow eq = eqp.get(id);
        if (eq.status().isUnusable()) {
            return ValidityResult.invalid("EQP_NOT_USABLE", eq.code() + " 상태 " + eq.status());
        }
        for (EquipmentRow child : eqp.children(id)) {
            ValidityResult v = validate(child.id(), on, projectId, visited);
            if (!v.valid()) {
                return ValidityResult.invalid("EQP_KIT_CHILD_INVALID", child.code() + ": " + v.reason()
                        + (v.detail() == null ? "" : " (" + v.detail() + ")"));
            }
        }
        if (!eq.calibrationRequired()) {
            return ValidityResult.notRequired();
        }
        return own(eq, on, projectId, false);
    }

    /**
     * 장비 자신의 교정 · Override 만 본 판정(상태 · 키트 제외). 일일 스캔의 만료 판정에 쓴다.
     * @param globalOverrideOnly true 면 프로젝트 한정 Override 는 무시(전역 만료 판정)
     */
    public ValidityResult own(EquipmentRow eq, LocalDate on, UUID projectId, boolean globalOverrideOnly) {
        var covering = jdbc.queryForList("""
                SELECT id, cert_no, calibrated_on, next_due_on FROM eqp.calibration
                 WHERE equipment_id = ? AND result <> 'FAIL' AND calibrated_on <= ? AND ? <= next_due_on
                 ORDER BY calibrated_on DESC, created_at DESC LIMIT 1""", eq.id(), EqpJdbc.d(on), EqpJdbc.d(on));
        if (!covering.isEmpty()) {
            Map<String, Object> c = covering.get(0);
            LocalDate calibratedOn = ((java.sql.Date) c.get("calibrated_on")).toLocalDate();
            Integer fails = jdbc.queryForObject("""
                    SELECT count(*) FROM eqp.calibration WHERE equipment_id = ? AND result = 'FAIL'
                       AND calibrated_on > ? AND calibrated_on <= ?""", Integer.class,
                    eq.id(), EqpJdbc.d(calibratedOn), EqpJdbc.d(on));
            if (fails != null && fails > 0) {
                return ValidityResult.invalid("EQP_CAL_FAILED_AFTER", "교정(" + calibratedOn + ") 이후 불합격 이력");
            }
            return new ValidityResult(true, "CALIBRATION", null, (UUID) c.get("id"), null, (String) c.get("cert_no"),
                    calibratedOn, ((java.sql.Date) c.get("next_due_on")).toLocalDate(), null);
        }
        var override = jdbc.queryForList("""
                SELECT id, valid_to FROM eqp.calibration_override
                 WHERE equipment_id = ? AND ? BETWEEN valid_from AND valid_to
                   AND (project_id IS NULL OR (NOT ? AND project_id = ?))
                 ORDER BY valid_to DESC LIMIT 1""", eq.id(), EqpJdbc.d(on), globalOverrideOnly, projectId);
        if (!override.isEmpty()) {
            return new ValidityResult(true, "OVERRIDE", null, null, (UUID) override.get(0).get("id"), null, null,
                    ((java.sql.Date) override.get(0).get("valid_to")).toLocalDate(), "QA 예외 승인(보고서 각주)");
        }
        LocalDate lastDue = lastValidDue(eq.id(), on).orElse(null);
        return ValidityResult.invalid("EQP_CAL_EXPIRED", lastDue == null ? "유효 교정 없음" : "검교정 만료(" + lastDue + ")");
    }

    /** on 이전에 끝난 마지막 PASS/LIMITED 교정의 next_due_on */
    public Optional<LocalDate> lastValidDue(UUID equipmentId, LocalDate on) {
        List<java.sql.Date> r = jdbc.queryForList("""
                SELECT max(next_due_on) FROM eqp.calibration
                 WHERE equipment_id = ? AND result <> 'FAIL' AND next_due_on < ?""", java.sql.Date.class,
                equipmentId, EqpJdbc.d(on));
        return r.isEmpty() || r.get(0) == null ? Optional.empty() : Optional.of(r.get(0).toLocalDate());
    }

    /** 무효면 사유별 오류 코드로 예외(409). problem+json 에 reason · equipmentId · overrideAllowed · alternatives */
    public static DomainException blocked(UUID equipmentId, ValidityResult v, List<Map<String, Object>> alternatives) {
        String code = EqpErrors.BY_REASON.getOrDefault(v.reason(), EqpErrors.CAL_EXPIRED);
        return new DomainException(code, v.detail() == null ? v.reason() : v.detail())
                .with("reason", v.reason())
                .with("equipmentId", equipmentId)
                .with("overrideAllowed", "EQP_CAL_EXPIRED".equals(v.reason()))
                .with("alternatives", alternatives);
    }
}
