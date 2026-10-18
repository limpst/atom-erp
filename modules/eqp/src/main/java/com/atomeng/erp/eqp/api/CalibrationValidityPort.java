package com.atomeng.erp.eqp.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 검교정 유효성 판정 — 예약 · 반출 · 측정 확정이 모두 거치는 단일 진입점(설계 B1 설계 포인트, B7.1).
 * 다른 모듈(FLD · RPT)은 EQP 테이블을 직접 읽지 않고 이 포트만 호출한다(TC-EQP-012).
 */
public interface CalibrationValidityPort {

    /** {@code on} 날짜(KST) 기준 판정. projectId 는 프로젝트 한정 Override 조회용(null 허용) */
    ValidityResult validate(UUID equipmentId, LocalDate on, UUID projectId);

    /**
     * 판정 결과.
     * @param basis CALIBRATION · OVERRIDE · NOT_REQUIRED(교정 비대상) · NONE(무효)
     * @param reason 무효 사유 코드(EQP_CAL_EXPIRED, EQP_NOT_USABLE, EQP_KIT_CHILD_INVALID, EQP_CAL_FAILED_AFTER) — 유효면 null
     * @param validUntil 근거 교정의 next_due_on 또는 Override valid_to
     */
    record ValidityResult(boolean valid, String basis, String reason, UUID calibrationId, UUID overrideId,
                          String certNo, LocalDate calibratedOn, LocalDate validUntil, String detail) {

        public static ValidityResult invalid(String reason, String detail) {
            return new ValidityResult(false, "NONE", reason, null, null, null, null, null, detail);
        }

        public static ValidityResult notRequired() {
            return new ValidityResult(true, "NOT_REQUIRED", null, null, null, null, null, null, null);
        }
    }
}
