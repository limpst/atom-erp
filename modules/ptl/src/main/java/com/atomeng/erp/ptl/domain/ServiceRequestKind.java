package com.atomeng.erp.ptl.domain;

/**
 * 서비스 요청 종류 (설계 09 B3 service_request.kind · A6.3). 긴급점검은 접수 + 2시간 SLA(BR-PTL-007),
 * 사유 코드 필수. defaultServiceType = 요청 본문에 용역 종류가 없을 때 SAL 리드에 넘길 값.
 */
public enum ServiceRequestKind {
    QUOTE("INQUIRY"), EMERGENCY("EMERGENCY_INSP"), REINSPECTION("REINSPECTION"), VERIFY_REPAIR("VERIFY_REPAIR");

    public static final long EMERGENCY_SLA_HOURS = 2;

    private final String defaultServiceType;

    ServiceRequestKind(String defaultServiceType) {
        this.defaultServiceType = defaultServiceType;
    }

    public String defaultServiceType() {
        return defaultServiceType;
    }

    public boolean requiresReason() {
        return this == EMERGENCY;
    }
}
