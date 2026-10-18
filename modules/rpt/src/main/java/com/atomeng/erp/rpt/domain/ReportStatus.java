package com.atomeng.erp.rpt.domain;

import com.atomeng.common.core.error.DomainException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 리비전 상태 머신 (설계 06 A6 그대로, 06-IMPL Step 1). 전이는 이 enum 으로만 판정한다. */
public enum ReportStatus {
    DRAFT, SNAPSHOT_FROZEN, COMPOSING, IN_REVIEW, APPROVED, SIGNING, PUBLISHED, DELIVERED, SUPERSEDED;

    private static final Map<ReportStatus, Set<ReportStatus>> ALLOWED = Map.of(
            DRAFT, EnumSet.of(SNAPSHOT_FROZEN),
            SNAPSHOT_FROZEN, EnumSet.of(DRAFT, COMPOSING),
            COMPOSING, EnumSet.of(COMPOSING, IN_REVIEW),
            IN_REVIEW, EnumSet.of(COMPOSING, APPROVED),
            APPROVED, EnumSet.of(SIGNING),
            SIGNING, EnumSet.of(COMPOSING, PUBLISHED),
            PUBLISHED, EnumSet.of(DELIVERED, SUPERSEDED),
            DELIVERED, EnumSet.of(SUPERSEDED),
            SUPERSEDED, EnumSet.noneOf(ReportStatus.class));

    public boolean canMoveTo(ReportStatus target) {
        return ALLOWED.get(this).contains(target);
    }

    public ReportStatus transitionTo(ReportStatus target) {
        if (!canMoveTo(target)) {
            throw new DomainException(RptCodes.STATE, "리비전 상태 전이 불가: %s → %s".formatted(this, target));
        }
        return target;
    }

    /** 발행 이후(본문 · 등급 · 렌더 불변 — BR-RPT-008) */
    public boolean isFrozen() {
        return this == PUBLISHED || this == DELIVERED || this == SUPERSEDED;
    }
}
