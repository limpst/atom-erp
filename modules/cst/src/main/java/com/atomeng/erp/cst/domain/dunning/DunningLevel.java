package com.atomeng.erp.cst.domain.dunning;

import java.util.Optional;

/** 독촉 단계 (BR-CST-BIL-009, 설계 §9-1). 경과일 = 기준일 − 발행일. L0 31일 · L1 61일 · L2 91일 이상. 30일 이하는 단계 없음 */
public enum DunningLevel {
    L0(31, "알림 — 담당 PM · 재무 내 할 일"),
    L1(61, "담당자 안내 — 발주처 담당자 안내문 초안(발송은 재무)"),
    L2(91, "공문 — 임원 결재 뒤 발송");

    public final int minAgeDays;
    public final String meaning;

    DunningLevel(int minAgeDays, String meaning) {
        this.minAgeDays = minAgeDays;
        this.meaning = meaning;
    }

    /** 경과일에 해당하는 가장 높은 단계. 30일 이하면 비어 있다 */
    public static Optional<DunningLevel> forAge(long ageDays) {
        DunningLevel best = null;
        for (DunningLevel l : values()) {
            if (ageDays >= l.minAgeDays) {
                best = l;
            }
        }
        return Optional.ofNullable(best);
    }

    /** 기준일에 "있어야 하는" 단계 전부(L2 면 L0 · L1 · L2) — 빠진 단계도 한 번씩 만든다 */
    public static DunningLevel[] dueLevels(long ageDays) {
        return java.util.Arrays.stream(values()).filter(l -> ageDays >= l.minAgeDays).toArray(DunningLevel[]::new);
    }
}
