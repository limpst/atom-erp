package com.atomeng.common.events;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional Outbox 발행 (설계 10.2, 00-IMPL Step 7).
 * 업무 트랜잭션 안에서만 호출할 수 있다(MANDATORY) → 업무 데이터와 이벤트가 같이 커밋/롤백(TC-CORE-004).
 */
public class OutboxPublisher {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final CurrentActor currentActor;
    private final Clock clock;

    public OutboxPublisher(JdbcTemplate jdbc, ObjectMapper objectMapper, CurrentActor currentActor, Clock clock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    /** @return eventId (UUID v7) */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(DomainEvent event) {
        EventNames.requireValid(event.eventType());
        UUID eventId = UuidV7.next();
        ObjectNode headers = objectMapper.createObjectNode();
        headers.put("actorId", currentActor.actorIdOrSystem().toString());
        String traceId = MDC.get("traceId");
        if (traceId != null) {
            headers.put("traceId", traceId);
        }
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, event_version,
                                              payload, headers, occurred_at, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)""",
                eventId, event.aggregateType(), event.aggregateId(), event.eventType(), event.eventVersion(),
                objectMapper.valueToTree(event).toString(), headers.toString(), now, now);
        return eventId;
    }
}
