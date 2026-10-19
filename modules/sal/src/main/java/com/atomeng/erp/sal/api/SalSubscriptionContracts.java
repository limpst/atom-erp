package com.atomeng.erp.sal.api;

import java.util.UUID;

/**
 * 구독 계약 — PTL(09) 발행 이벤트의 payload 를 SAL 쪽에 고정한다(FAC ReportPublishedContract 와 같은 방식).
 * PTL 이 sal::api 에 의존하므로(견적 · 서명 요청 구독) 이 계약을 ptl::api 로 옮기면 모듈 순환이 생긴다 → SAL 에 두고,
 * 실제 PTL 발행 레코드(ptl::api PtlEvents.ServiceRequestSubmitted)와의 호환은 계약 테스트(apps PtlSalContractTest)가 확인한다.
 */
public final class SalSubscriptionContracts {

    private SalSubscriptionContracts() {}

    /** ptl.service-request.submitted (설계 01 B6) — 고객 포털 견적 · 점검 요청 → PORTAL 리드. 멱등키 serviceRequestId */
    public record PtlServiceRequestSubmitted(UUID serviceRequestId, UUID clientId, UUID facilityId, String requestType,
                                             String message) {
        public static final String TYPE = "ptl.service-request.submitted";
    }
}
