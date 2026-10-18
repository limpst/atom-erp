package com.atomeng.erp.eqp.api;

import com.atomeng.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * EQP 발행 이벤트 (설계 04 B6). payload = 레코드 컴포넌트.
 * {@code eqp.equipment.returned} 의 필드는 08 CST 구독 계약(cst::api CstSubscriptionContracts.EqpEquipmentReturned)과
 * 같아야 한다 — 계약 테스트(apps EqpCstContractTest)가 JSON 왕복(알 수 없는 필드 = 실패)으로 확인한다.
 */
public final class EqpEvents {

    private EqpEvents() {}

    /** eqp.equipment.registered — 대장 등록 */
    public record EquipmentRegistered(UUID equipmentId, String code, String typeCode, BigDecimal acquisitionCost)
            implements DomainEvent {
        public static final String TYPE = "eqp.equipment.registered";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /** eqp.calibration.recorded — 검교정 등록(PASS · LIMITED · FAIL 모두) */
    public record CalibrationRecorded(UUID equipmentId, UUID calibrationId, String result, LocalDate calibratedOn,
                                      LocalDate nextDueOn, boolean nextDueEstimated) implements DomainEvent {
        public static final String TYPE = "eqp.calibration.recorded";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /**
     * eqp.calibration.due-soon — 일 배치 D-60/30/7/0. 멱등키 (equipmentId, nextDueOn, thresholdDays):
     * 남은 일수 이하인 가장 작은 기준 1개만(배치를 건너뛴 날이 있어도 누락 0, 같은 기준 재발행 0).
     */
    public record CalibrationDueSoon(UUID equipmentId, String equipmentCode, LocalDate nextDueOn, int daysLeft,
                                     int thresholdDays) implements DomainEvent {
        public static final String TYPE = "eqp.calibration.due-soon";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /**
     * eqp.calibration.expired (v0.2) — 유효 교정 · Override 가 없어진 장비(상태 CAL_DUE 전이와 같은 트랜잭션).
     * expiredOn = 마지막 유효 next_due_on 의 다음 날(처음으로 무효가 된 날). 멱등키 (equipmentId, expiredOn).
     * 구독: QAR(AUTO-CHECK 재실행) · FLD(측정 확정 차단 캐시) · 알림.
     */
    public record CalibrationExpired(UUID equipmentId, String equipmentCode, LocalDate expiredOn,
                                     List<UUID> activeCheckoutIds, List<UUID> activeReservationIds)
            implements DomainEvent {
        public static final String TYPE = "eqp.calibration.expired";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /** eqp.equipment.checked-out — 반출(FLD 장비 후보 캐시) */
    public record EquipmentCheckedOut(UUID checkoutId, UUID equipmentId, UUID projectId, UUID engineerId, Instant outAt)
            implements DomainEvent {
        public static final String TYPE = "eqp.equipment.checked-out";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /** eqp.checkout.conflicted — 오프라인 반출 충돌 기록(A8-1) → PM 해결 태스크 */
    public record CheckoutConflicted(UUID checkoutId, UUID equipmentId, UUID projectId, UUID activeCheckoutId)
            implements DomainEvent {
        public static final String TYPE = "eqp.checkout.conflicted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /**
     * eqp.equipment.returned — 반납 확정 1회(CST 장비 원가, 멱등 source_id = rentalId = checkoutId).
     * 필드는 CST 구독 계약(rentalId · equipmentId · equipmentCode · projectId · days · returnedOn)과 정확히 같다.
     * days = 반출일 ~ 반납일(KST, 양 끝 포함) 사용 일수 ≥ 1. 단가는 CST rate_table(EQUIP_DAILY, equipmentCode).
     */
    public record EquipmentReturned(UUID rentalId, UUID equipmentId, String equipmentCode, UUID projectId, int days,
                                    LocalDate returnedOn) implements DomainEvent {
        public static final String TYPE = "eqp.equipment.returned";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /** eqp.fault.reported — 반납 사후 점검 이상 → 고장 신고 */
    public record FaultReported(UUID faultId, UUID equipmentId, UUID checkoutId) implements DomainEvent {
        public static final String TYPE = "eqp.fault.reported";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Equipment"; }
        @Override public UUID aggregateId() { return equipmentId; }
    }

    /** eqp.flight.logged — 비행로그 등록(AIP 영상 파이프라인 트리거, QAR: outOfArea) */
    public record FlightLogged(UUID flightLogId, UUID equipmentId, UUID projectId, UUID pilotId, boolean outOfArea,
                               boolean outsideApprovalPeriod) implements DomainEvent {
        public static final String TYPE = "eqp.flight.logged";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "FlightLog"; }
        @Override public UUID aggregateId() { return flightLogId; }
    }
}
