package com.atomeng.erp.sal.domain;

import static java.util.Map.entry;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 견적 상태 (설계 A6.2). ISSUED 이후는 금액 · 스냅샷 불변(NFR-SAL-05, DB 트리거). */
public enum EstimateStatus {
    DRAFT, PENDING_APPROVAL, ISSUED, SENT, ACCEPTED, REJECTED, SUPERSEDED, EXPIRED;

    private static final Map<EstimateStatus, Set<EstimateStatus>> NEXT = new EnumMap<>(Map.ofEntries(
            entry(DRAFT, EnumSet.of(PENDING_APPROVAL, ISSUED, SUPERSEDED)),
            entry(PENDING_APPROVAL, EnumSet.of(ISSUED, DRAFT, SUPERSEDED)),
            entry(ISSUED, EnumSet.of(SENT, SUPERSEDED)),
            entry(SENT, EnumSet.of(ACCEPTED, SUPERSEDED, REJECTED, EXPIRED)),
            entry(ACCEPTED, EnumSet.noneOf(EstimateStatus.class)),
            entry(REJECTED, EnumSet.noneOf(EstimateStatus.class)),
            entry(SUPERSEDED, EnumSet.noneOf(EstimateStatus.class)),
            entry(EXPIRED, EnumSet.noneOf(EstimateStatus.class))));

    public boolean canMoveTo(EstimateStatus to) {
        return NEXT.get(this).contains(to);
    }

    public boolean isFrozen() {
        return this != DRAFT && this != PENDING_APPROVAL;
    }
}
