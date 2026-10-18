package com.atomeng.common.events;

import java.util.UUID;

/**
 * 도메인 이벤트 (설계 10.1). 이벤트 객체 자체(레코드 컴포넌트)가 payload 로 직렬화된다.
 * 이름 규칙: {mod}.{aggregate}.{과거형 동사} — 예 core.project.created, fld.defect.recorded
 */
public interface DomainEvent {

    String eventType();

    default int eventVersion() {
        return 1;
    }

    String aggregateType();

    UUID aggregateId();
}
