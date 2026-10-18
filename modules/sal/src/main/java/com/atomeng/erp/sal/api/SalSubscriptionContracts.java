package com.atomeng.erp.sal.api;

import java.util.UUID;

/**
 * 구독 계약(스텁) — 상대 모듈(PTL 09)이 아직 없어 SAL 쪽에 payload 를 고정한다(FAC ReportPublishedContract 와 같은 방식).
 * PTL 구현 시 그쪽 api 로 옮기고 계약 테스트로 호환을 확인한다.
 */
public final class SalSubscriptionContracts {

    private SalSubscriptionContracts() {}

    /** ptl.service-request.submitted (설계 01 B6) — 고객 포털 견적 · 점검 요청 → PORTAL 리드. 멱등키 serviceRequestId */
    public record PtlServiceRequestSubmitted(UUID serviceRequestId, UUID clientId, UUID facilityId, String requestType,
                                             String message) {
        public static final String TYPE = "ptl.service-request.submitted";
    }
}
