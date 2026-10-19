package com.atomeng.erp.aip.api;

/**
 * AI 서비스 카탈로그 (설계 A1.2 + 워커 보조 서비스 KB_SEARCH). vramBudgetMb = 11-IMPL Step 1 기본 예산,
 * defaultPriority = 기본 우선순위(B7.4 P1–P4), supported = 이번 단계 워커가 처리하는 서비스(STT · 드론 · 위험요소는 후속 Phase).
 */
public enum AipServiceCode {
    CRACK_DETECT(1200, 1, true),
    CRACK_MEASURE(1200, 1, true),
    REPORT_DRAFT(300, 2, true),
    REPORT_REVIEW(300, 2, true),
    KB_SEARCH(0, 2, true),
    STT(1100, 1, false),
    DRONE_INSPECT(1200, 3, false),
    SITE_HAZARD(1200, 3, false);

    private final int vramBudgetMb;
    private final int defaultPriority;
    private final boolean supported;

    AipServiceCode(int vramBudgetMb, int defaultPriority, boolean supported) {
        this.vramBudgetMb = vramBudgetMb;
        this.defaultPriority = defaultPriority;
        this.supported = supported;
    }

    public int vramBudgetMb() {
        return vramBudgetMb;
    }

    public int defaultPriority() {
        return defaultPriority;
    }

    public boolean supported() {
        return supported;
    }
}
