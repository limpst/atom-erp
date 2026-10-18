package com.atomeng.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 전송 봉투 (설계 10.1 Envelope). eventId = outbox_event.id (UUID v7) — 컨슈머 멱등 키. */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        OffsetDateTime occurredAt,
        String aggregateType,
        UUID aggregateId,
        UUID actorId,
        String traceId,
        JsonNode payload) {
}
