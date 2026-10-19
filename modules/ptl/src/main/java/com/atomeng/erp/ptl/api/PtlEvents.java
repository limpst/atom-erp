package com.atomeng.erp.ptl.api;

import com.atomeng.common.events.DomainEvent;
import java.util.UUID;

/**
 * PTL 발행 이벤트 (설계 09 v0.3 B6 정본 이름). payload = 레코드 컴포넌트.
 * {@code ptl.service-request.submitted} 필드는 SAL 구독 계약(sal::api SalSubscriptionContracts.PtlServiceRequestSubmitted)과
 * 정확히 같아야 한다 — 계약 테스트(apps PtlSalContractTest)가 JSON 왕복(알 수 없는 필드 = 실패)으로 확인한다.
 */
public final class PtlEvents {

    private PtlEvents() {}

    /**
     * ptl.service-request.submitted — 고객 포털 견적 · 긴급 · 재점검 · 확인점검 요청(A6.3 SUBMITTED). 소비: SAL(PORTAL 리드,
     * 멱등키 serviceRequestId), HRQ 당직(후속). clientId = 요청 고객사(core.client.id), requestType = SAL 용역 종류,
     * message = "[종류/사유] 본문".
     */
    public record ServiceRequestSubmitted(UUID serviceRequestId, UUID clientId, UUID facilityId, String requestType,
                                          String message) implements DomainEvent {
        public static final String TYPE = "ptl.service-request.submitted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ServiceRequest"; }
        @Override public UUID aggregateId() { return serviceRequestId; }
    }
}
