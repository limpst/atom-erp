package com.atomeng.common.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Outbox Relay (설계 10.2·10.3, 00-IMPL Step 7).
 *
 * <ul>
 *   <li>미발행 AND next_attempt_at ≤ now 인 행을 {@code FOR UPDATE SKIP LOCKED} 로 1건씩 점유 → 여러 Relay 동시 실행 안전</li>
 *   <li>이벤트 1건 = 트랜잭션 1개: send(리스너 처리 포함) + published_at 갱신이 원자적</li>
 *   <li>실패 n회째(n=1..4): next_attempt_at = now + 5·2^n 초 → 10s, 20s, 40s, 80s</li>
 *   <li>5회째 실패: core.outbox_dead 로 이동(원본 삭제) + outbox_dead_total 증가</li>
 * </ul>
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    public static final int MAX_ATTEMPTS = 5;
    public static final int DEFAULT_BATCH = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate perEventTx;
    private final TransactionTemplate failureTx;
    private final EventTransport transport;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meters;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, PlatformTransactionManager txManager, EventTransport transport,
                       ObjectMapper objectMapper, MeterRegistry meters, Clock clock) {
        this.jdbc = jdbc;
        this.perEventTx = new TransactionTemplate(txManager);
        this.perEventTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.failureTx = new TransactionTemplate(txManager);
        this.failureTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.meters = meters;
        this.clock = clock;
    }

    /** 지수 백오프: n회째 실패 후 대기 = 5·2^n 초 (n=1 → 10s … n=4 → 80s) */
    public static Duration backoff(int attempts) {
        if (attempts < 1) {
            throw new IllegalArgumentException("attempts >= 1");
        }
        return Duration.ofSeconds(5L << attempts);
    }

    public record PollResult(int published, int failed, int dead) {}

    /** 한 번 폴링(최대 batchSize 건). 스케줄러가 500ms 간격으로 호출한다. */
    public PollResult pollOnce(int batchSize) {
        int published = 0;
        int failed = 0;
        int dead = 0;
        for (int i = 0; i < batchSize; i++) {
            Outcome o = processNext();
            if (o == Outcome.NONE) {
                break;
            }
            switch (o) {
                case PUBLISHED -> published++;
                case RETRY -> failed++;
                case DEAD -> dead++;
                default -> { }
            }
        }
        return new PollResult(published, failed, dead);
    }

    public PollResult pollOnce() {
        return pollOnce(DEFAULT_BATCH);
    }

    private enum Outcome { NONE, PUBLISHED, RETRY, DEAD }

    private record Claimed(UUID id, String eventType, int attempts, EventEnvelope envelope) {}

    private Outcome processNext() {
        final Claimed[] claimed = new Claimed[1];
        try {
            Boolean found = perEventTx.execute(status -> {
                Instant now = clock.instant();
                var rows = jdbc.query("""
                        SELECT id, aggregate_type, aggregate_id, event_type, event_version, payload::text AS payload,
                               headers::text AS headers, occurred_at, attempts
                          FROM core.outbox_event
                         WHERE published_at IS NULL AND next_attempt_at <= ?
                         ORDER BY occurred_at, id
                         LIMIT 1
                           FOR UPDATE SKIP LOCKED""", this::map, Timestamp.from(now));
                if (rows.isEmpty()) {
                    return false;
                }
                claimed[0] = rows.get(0);
                Timer.Sample sample = Timer.start(meters);
                try {
                    transport.send(claimed[0].envelope());
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new TransportException(e);
                } finally {
                    sample.stop(meters.timer("outbox_publish_seconds"));
                }
                jdbc.update("UPDATE core.outbox_event SET published_at = ? WHERE id = ?",
                        Timestamp.from(clock.instant()), claimed[0].id());
                return true;
            });
            return Boolean.TRUE.equals(found) ? Outcome.PUBLISHED : Outcome.NONE;
        } catch (RuntimeException ex) {
            if (claimed[0] == null) {
                throw ex; // 점유 전 오류(DB 장애 등)
            }
            Throwable cause = ex instanceof TransportException te ? te.getCause() : ex;
            return recordFailure(claimed[0], cause);
        }
    }

    private Outcome recordFailure(Claimed c, Throwable cause) {
        int attempts = c.attempts() + 1;
        String error = abbreviate(String.valueOf(cause), 2000);
        log.warn("outbox publish failed: id={} type={} attempt={}/{} error={}", c.id(), c.eventType(), attempts,
                MAX_ATTEMPTS, error);
        return failureTx.execute(status -> {
            Instant now = clock.instant();
            if (attempts >= MAX_ATTEMPTS) {
                jdbc.update("UPDATE core.outbox_event SET attempts = ?, last_error = ? WHERE id = ?",
                        attempts, error, c.id());
                jdbc.update("""
                        INSERT INTO core.outbox_dead
                        SELECT *, ?::timestamptz FROM core.outbox_event WHERE id = ?""", Timestamp.from(now), c.id());
                jdbc.update("DELETE FROM core.outbox_event WHERE id = ?", c.id());
                meters.counter("outbox_dead_total", "type", c.eventType()).increment();
                return Outcome.DEAD;
            }
            jdbc.update("""
                    UPDATE core.outbox_event SET attempts = ?, last_error = ?, next_attempt_at = ?
                     WHERE id = ? AND published_at IS NULL""",
                    attempts, error, Timestamp.from(now.plus(backoff(attempts))), c.id());
            return Outcome.RETRY;
        });
    }

    /** DLQ 재투입 (설계 10.3-3: SYSADMIN 이 원인 조치 후 attempts=0 으로 되돌림). */
    public int requeueDead(UUID eventId) {
        Integer n = failureTx.execute(status -> {
            int moved = jdbc.update("""
                    INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, event_version, payload,
                                                  headers, occurred_at, published_at, attempts, last_error,
                                                  next_attempt_at)
                    SELECT id, aggregate_type, aggregate_id, event_type, event_version, payload, headers, occurred_at,
                           NULL, 0, last_error, ?::timestamptz
                      FROM core.outbox_dead WHERE id = ?""", Timestamp.from(clock.instant()), eventId);
            jdbc.update("DELETE FROM core.outbox_dead WHERE id = ?", eventId);
            return moved;
        });
        return n == null ? 0 : n;
    }

    private Claimed map(ResultSet rs, int rowNum) throws SQLException {
        try {
            JsonNode headers = objectMapper.readTree(rs.getString("headers"));
            UUID actorId = headers.hasNonNull("actorId") ? UUID.fromString(headers.get("actorId").asText()) : null;
            String traceId = headers.hasNonNull("traceId") ? headers.get("traceId").asText() : null;
            UUID id = rs.getObject("id", UUID.class);
            OffsetDateTime occurredAt = rs.getTimestamp("occurred_at").toInstant().atOffset(ZoneOffset.UTC);
            var env = new EventEnvelope(id, rs.getString("event_type"), rs.getInt("event_version"), occurredAt,
                    rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), actorId, traceId,
                    objectMapper.readTree(rs.getString("payload")));
            return new Claimed(id, env.eventType(), rs.getInt("attempts"), env);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
    }

    /** 체크 예외를 트랜잭션 롤백 대상으로 감싸기 위한 내부 예외 */
    static final class TransportException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        TransportException(Throwable cause) {
            super(cause);
        }
    }
}
