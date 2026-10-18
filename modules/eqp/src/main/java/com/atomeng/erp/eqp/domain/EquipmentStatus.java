package com.atomeng.erp.eqp.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 장비 상태와 허용 전이 (설계 A6.1 = 04-IMPL Step 2 전이표). 상태를 직접 UPDATE 하지 않고 이 표로만 바꾼다.
 * 편차 1건: CAL_DUE → CHECKED_OUT — QA Override(BR-EQP-003)가 유효한 동안에만 반출 서비스가 쓴다
 * (설계 표대로면 일일 스캔이 CAL_DUE 로 바꾼 뒤에는 Override 를 받아도 반출할 길이 없다).
 */
public enum EquipmentStatus {
    REGISTERED, AVAILABLE, RESERVED, CHECKED_OUT, CAL_DUE, IN_CALIBRATION, BROKEN, IN_REPAIR, LOST, DISPOSED;

    private static final Map<EquipmentStatus, Set<EquipmentStatus>> ALLOWED = Map.ofEntries(
            Map.entry(REGISTERED, EnumSet.of(AVAILABLE, IN_CALIBRATION)),
            Map.entry(AVAILABLE, EnumSet.of(RESERVED, CHECKED_OUT, CAL_DUE, DISPOSED)),
            Map.entry(RESERVED, EnumSet.of(AVAILABLE, CHECKED_OUT, CAL_DUE)),
            Map.entry(CHECKED_OUT, EnumSet.of(AVAILABLE, BROKEN, LOST)),
            Map.entry(CAL_DUE, EnumSet.of(IN_CALIBRATION, CHECKED_OUT)),
            Map.entry(IN_CALIBRATION, EnumSet.of(AVAILABLE, BROKEN)),
            Map.entry(BROKEN, EnumSet.of(IN_REPAIR)),
            Map.entry(IN_REPAIR, EnumSet.of(CAL_DUE, AVAILABLE, DISPOSED)),
            Map.entry(LOST, EnumSet.of(AVAILABLE, DISPOSED)),
            Map.entry(DISPOSED, EnumSet.noneOf(EquipmentStatus.class)));

    public boolean canTransitionTo(EquipmentStatus target) {
        return ALLOWED.getOrDefault(this, Set.of()).contains(target);
    }

    /** 현장 투입(예약 · 반출) 자체가 불가능한 상태 — 판정 사유 EQP_NOT_USABLE (FR-EQP-011 수리 중 예약 불가 포함) */
    public boolean isUnusable() {
        return this == DISPOSED || this == LOST || this == BROKEN || this == IN_REPAIR;
    }
}
