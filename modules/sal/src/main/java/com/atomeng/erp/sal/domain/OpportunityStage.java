package com.atomeng.erp.sal.domain;

import static java.util.Map.entry;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/** 기회 단계 (설계 A6.1 전이표 — 01-IMPL Step 2 코드 그대로). LEAD → QUALIFYING 은 담당 배정. */
public enum OpportunityStage {
    LEAD, QUALIFYING, PROPOSING, NEGOTIATING, BIDDING, BID_APPROVED, SUBMITTED, WON, LOST, DROPPED, CONTRACTED;

    private static final Map<OpportunityStage, Set<OpportunityStage>> NEXT = new EnumMap<>(Map.ofEntries(
            entry(LEAD, EnumSet.of(QUALIFYING, DROPPED)),
            entry(QUALIFYING, EnumSet.of(PROPOSING, BIDDING, DROPPED)),
            entry(BIDDING, EnumSet.of(BID_APPROVED, DROPPED)),
            entry(BID_APPROVED, EnumSet.of(SUBMITTED)),
            entry(SUBMITTED, EnumSet.of(WON, LOST)),
            entry(PROPOSING, EnumSet.of(NEGOTIATING)),
            entry(NEGOTIATING, EnumSet.of(PROPOSING, WON, LOST)),
            entry(WON, EnumSet.of(CONTRACTED)),
            entry(LOST, EnumSet.noneOf(OpportunityStage.class)),
            entry(DROPPED, EnumSet.noneOf(OpportunityStage.class)),
            entry(CONTRACTED, EnumSet.noneOf(OpportunityStage.class))));

    public boolean canMoveTo(OpportunityStage to) {
        return NEXT.get(this).contains(to);
    }

    public boolean isTerminal() {
        return NEXT.get(this).isEmpty();
    }

    /** 견적 작성 가능 단계 (01-IMPL Step 6 requireStageIn) */
    public boolean allowsEstimate() {
        return this == QUALIFYING || this == PROPOSING || this == NEGOTIATING;
    }
}
