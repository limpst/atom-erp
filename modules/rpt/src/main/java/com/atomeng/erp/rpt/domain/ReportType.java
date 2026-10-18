package com.atomeng.erp.rpt.domain;

import java.util.Optional;

/**
 * 보고서 유형 카탈로그 (설계 06 A1.3). {@link #facInspectionType()} = FAC 등급 이력의 점검 종류
 * (fac::api ReportPublishedContract.inspectionType) — 안전등급을 남기지 않는 유형은 비어 있다.
 */
public enum ReportType {
    RT_REG("정기안전점검", "REGULAR"),
    RT_PRC("정밀안전점검", "DETAILED"),
    RT_PSD("정밀안전진단", "PRECISION_DIAG"),
    RT_EMG("긴급안전점검", "EMERGENCY"),
    RT_BLD("건축물관리점검", "BMA_CHECK"),
    RT_SEI("내진성능평가", null),
    RT_ADJ_PRE("인접건축물 사전조사", null),
    RT_ADJ_POST("인접건축물 사후조사", null),
    RT_DEM("해체공사 구조안전성 검토", null);

    private final String displayName;
    private final String facInspectionType;

    ReportType(String displayName, String facInspectionType) {
        this.displayName = displayName;
        this.facInspectionType = facInspectionType;
    }

    public String displayName() {
        return displayName;
    }

    public Optional<String> facInspectionType() {
        return Optional.ofNullable(facInspectionType);
    }
}
