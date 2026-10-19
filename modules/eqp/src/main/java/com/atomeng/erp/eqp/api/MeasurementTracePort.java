package com.atomeng.erp.eqp.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 측정 추적성 (04-IMPL Step 9 "EquipmentQueryPort.recordTrace", 05 FLD BR-FLD-008). 측정값을 확정하는 모듈(FLD)이
 * 자기 트랜잭션 안에서 부른다(MANDATORY — 측정 저장과 추적 행이 함께 커밋 · 롤백).
 * 측정일 기준 {@link CalibrationValidityPort} 판정을 다시 하고, 무효면 기록하지 않고 {@link TraceRejectedException}.
 */
public interface MeasurementTracePort {

    record TraceCommand(UUID equipmentId, UUID projectId, LocalDate measuredOn, String sourceModule, String sourceType,
                        UUID sourceId, String item, BigDecimal value, String unit) {}

    /** basis = CALIBRATION · OVERRIDE · NOT_REQUIRED, replayed = 같은 (sourceModule, sourceId) 기존 행 */
    record Trace(UUID traceId, String basis, UUID calibrationId, UUID overrideId, String certNo, LocalDate validUntil,
                 boolean replayed) {}

    Trace recordTrace(TraceCommand cmd);

    /** 측정일 기준 장비 무효(사유 코드 = CalibrationValidityPort.ValidityResult.reason) */
    class TraceRejectedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public TraceRejectedException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
