package com.atomeng.erp.sal.api;

import java.util.Optional;
import java.util.UUID;

/**
 * SAL 공개 조회 — 09 PTL 고객 포털이 {@code sal.estimate.issued}(payload 에 고객사 없음)를 받는 고객사로 연결할 때 쓴다.
 * 권한 검사는 호출 모듈 책임.
 */
public interface SalQueryApi {

    /** 영업 기회의 고객사(core.client.id). 기회가 없거나 고객 미지정(LEAD)이면 empty */
    Optional<UUID> clientOfOpportunity(UUID opportunityId);
}
