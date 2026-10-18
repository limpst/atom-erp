package com.atomeng.erp.core.api;

import com.atomeng.common.events.DomainEvent;
import java.util.UUID;

/** core.project.created — 프로젝트 생성 (FAC·CST 등 구독 예정). payload 는 레코드 컴포넌트. */
public record ProjectCreatedEvent(UUID projectId, String projectNo, String serviceType, UUID clientId)
        implements DomainEvent {

    public static final String TYPE = "core.project.created";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "Project";
    }

    @Override
    public UUID aggregateId() {
        return projectId;
    }
}
