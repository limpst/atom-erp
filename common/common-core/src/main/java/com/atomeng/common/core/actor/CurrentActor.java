package com.atomeng.common.core.actor;

import java.util.Optional;
import java.util.UUID;

/**
 * 현재 요청 주체(actor) 조회 SPI. 구현은 common-identity(Spring Security) 가 제공한다.
 * common-web(멱등 필터)·common-events(헤더 actorId)·감사 로그가 identity 에 직접 의존하지 않게 하는 경계.
 */
public interface CurrentActor {

    /** 시스템(배치·이벤트 리스너) actor — 설계 00-IMPL Step 3 SecurityAuditorAware.SYSTEM */
    UUID SYSTEM = UUID.fromString("00000000-0000-7000-8000-000000000000");

    Optional<Actor> current();

    default UUID actorIdOrSystem() {
        return current().map(Actor::actorId).orElse(SYSTEM);
    }

    /**
     * @param actorId  직원 ID(eid 클레임) 또는 subject 로부터 유도한 안정적 UUID
     * @param clientId OIDC 클라이언트(azp) — 예: erp-web, field-app
     */
    record Actor(UUID actorId, String clientId) {}
}
