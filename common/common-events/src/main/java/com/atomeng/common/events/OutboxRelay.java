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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *
 * <p><b>구독자별 전달(보안 검토 CMN-14)</b> — 전송이 {@link SubscriberTransport}(기본 {@link SpringEventTransport})이면:
 * <ul>
 *   <li>점유는 그대로(이벤트 행 {@code FOR UPDATE SKIP LOCKED}), 구독자마다 <b>별도 트랜잭션</b>(REQUIRES_NEW)으로 전달하고
 *       같은 트랜잭션에 {@code core.outbox_delivery} DONE 행을 남긴다 → 한 구독자가 실패해도 다른 구독자의 처리는 커밋되고
 *       다시 전달되지 않는다. 구독자의 processed_event 중복 제거는 그 구독자 트랜잭션 안(MANDATORY) 그대로</li>
 *   <li>실패한 구독자만 RETRY(시도 n회 → 5·2^n 초: 10 · 20 · 40 · 80s), 5회째 실패면 그 구독자만 DEAD(구독자별 DLQ,
 *       뷰 core.outbox_subscriber_dead) + outbox_subscriber_dead_total</li>
 *   <li>이벤트 행: attempts = 구독자 최대 시도 수, next_attempt_at = 남은 RETRY 중 가장 이른 시각. 모두 DONE → published_at,
 *       RETRY 없이 DEAD 가 남으면 core.outbox_dead 로 이동({@link #requeueDead} 는 DEAD 구독자만 다시 전달)</li>
 * </ul>
 * 그 밖의 {@link EventTransport}(시험용 대역 등)는 예전처럼 이벤트 1건 = 트랜잭션 1개.
 */
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    public static final int MAX_ATTEMPTS = 5;
    public static final int DEFAULT_BATCH = 100;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate perEventTx;
    private final TransactionTemplate failureTx;
    private final TransactionTemplate subscriberTx;
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
        this.subscriberTx = new TransactionTemplate(txManager);
        this.subscriberTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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

    private static final String CLAIM_SQL = """
            SELECT id, aggregate_type, aggregate_id, event_type, event_version, payload::text AS payload,
                   headers::text AS headers, occurred_at, attempts
              FROM core.outbox_event
             WHERE published_at IS NULL AND next_attempt_at <= ?
             ORDER BY occurred_at, id
             LIMIT 1
               FOR UPDATE SKIP LOCKED""";

    private boolean perSubscriber() {
        return transport instanceof SubscriberTransport
                && !(transport instanceof SpringEventTransport s && !s.perSubscriber());
    }

    private Outcome processNext() {
        if (perSubscriber()) {
            return processNextPerSubscriber((SubscriberTransport) transport);
        }
        final Claimed[] claimed = new Claimed[1];
        try {
            Boolean found = perEventTx.execute(status -> {
                Instant now = clock.instant();
                var rows = jdbc.query(CLAIM_SQL, this::map, Timestamp.from(now));
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

    /** 구독자별 전달 상태(core.outbox_delivery) */
    private record Delivery(String status, int attempts, Instant nextAttemptAt) {}

    /** CMN-14: 이벤트 1건을 구독자마다 별도 트랜잭션으로 전달하고 구독자별로 재시도 · DLQ */
    private Outcome processNextPerSubscriber(SubscriberTransport st) {
        Outcome o = perEventTx.execute(status -> {
            Instant now = clock.instant();
            var rows = jdbc.query(CLAIM_SQL, this::map, Timestamp.from(now));
            if (rows.isEmpty()) {
                return Outcome.NONE;
            }
            Claimed c = rows.get(0);
            Map<String, Delivery> state = new HashMap<>();
            jdbc.query("SELECT subscriber, status, attempts, next_attempt_at FROM core.outbox_delivery WHERE event_id = ?",
                    rs -> {
                        Timestamp next = rs.getTimestamp("next_attempt_at");
                        state.put(rs.getString("subscriber"), new Delivery(rs.getString("status"), rs.getInt("attempts"),
                                next == null ? null : next.toInstant()));
                    }, c.id());
            List<SubscriberTransport.Subscriber> subs = st.subscribersFor(c.envelope());
            String lastError = null;
            Timer.Sample sample = Timer.start(meters);
            for (SubscriberTransport.Subscriber sub : subs) {
                String sid = abbreviate(sub.id(), 200);
                Delivery d = state.get(sid);
                if (d != null && (!"RETRY".equals(d.status()) || d.nextAttemptAt().isAfter(now))) {
                    continue;   // 이미 전달(DONE) · 구독자 DLQ(DEAD) · 아직 재시도 시각 전
                }
                int attempts = (d == null ? 0 : d.attempts()) + 1;
                try {
                    subscriberTx.executeWithoutResult(x -> {
                        try {
                            sub.deliver(c.envelope());
                        } catch (RuntimeException e) {
                            throw e;
                        } catch (Exception e) {
                            throw new TransportException(e);
                        }
                        upsertDelivery(c.id(), sid, "DONE", attempts, null, null, clock.instant());
                    });
                    state.put(sid, new Delivery("DONE", attempts, null));
                } catch (RuntimeException ex) {
                    Throwable cause = ex instanceof TransportException te ? te.getCause() : ex;
                    String error = abbreviate(sid + ": " + cause, 2000);
                    boolean dead = attempts >= MAX_ATTEMPTS;
                    Instant next = dead ? null : now.plus(backoff(attempts));
                    log.warn("outbox delivery failed: id={} type={} subscriber={} attempt={}/{} error={}", c.id(),
                            c.eventType(), sid, attempts, MAX_ATTEMPTS, error);
                    upsertDelivery(c.id(), sid, dead ? "DEAD" : "RETRY", attempts, next, error, now);
                    state.put(sid, new Delivery(dead ? "DEAD" : "RETRY", attempts, next));
                    lastError = error;
                    if (dead) {
                        meters.counter("outbox_subscriber_dead_total", "type", c.eventType(), "subscriber", sid).increment();
                    }
                }
            }
            sample.stop(meters.timer("outbox_publish_seconds"));

            int maxAttempts = c.attempts();
            Instant nextDue = null;
            boolean anyDead = false;
            for (SubscriberTransport.Subscriber sub : subs) {
                Delivery d = state.get(abbreviate(sub.id(), 200));
                if (d == null) {
                    continue;
                }
                maxAttempts = Math.max(maxAttempts, d.attempts());
                if ("DEAD".equals(d.status())) {
                    anyDead = true;
                } else if ("RETRY".equals(d.status()) && (nextDue == null || d.nextAttemptAt().isBefore(nextDue))) {
                    nextDue = d.nextAttemptAt();
                }
            }
            if (nextDue == null && !anyDead) {
                jdbc.update("UPDATE core.outbox_event SET published_at = ? WHERE id = ?", Timestamp.from(clock.instant()),
                        c.id());
                jdbc.update("DELETE FROM core.outbox_delivery WHERE event_id = ?", c.id());
                return Outcome.PUBLISHED;
            }
            if (nextDue == null) {
                // 남은 재시도 없이 DEAD 구독자만 — 이벤트를 DLQ 로(구독자 상태는 남겨 재투입 시 DEAD 구독자만 재전달)
                jdbc.update("UPDATE core.outbox_event SET attempts = ?, last_error = coalesce(?, last_error) WHERE id = ?",
                        maxAttempts, lastError, c.id());
                jdbc.update("""
                        INSERT INTO core.outbox_dead
                        SELECT *, ?::timestamptz FROM core.outbox_event WHERE id = ?""", Timestamp.from(now), c.id());
                jdbc.update("DELETE FROM core.outbox_event WHERE id = ?", c.id());
                meters.counter("outbox_dead_total", "type", c.eventType()).increment();
                return Outcome.DEAD;
            }
            jdbc.update("""
                    UPDATE core.outbox_event SET attempts = ?, last_error = coalesce(?, last_error), next_attempt_at = ?
                     WHERE id = ?""", maxAttempts, lastError, Timestamp.from(nextDue), c.id());
            return Outcome.RETRY;
        });
        return o == null ? Outcome.NONE : o;
    }

    private void upsertDelivery(UUID eventId, String subscriber, String status, int attempts, Instant next, String error,
                                Instant at) {
        jdbc.update("""
                INSERT INTO core.outbox_delivery(event_id, subscriber, status, attempts, next_attempt_at, last_error, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id, subscriber) DO UPDATE SET status = EXCLUDED.status, attempts = EXCLUDED.attempts,
                       next_attempt_at = EXCLUDED.next_attempt_at, last_error = EXCLUDED.last_error,
                       updated_at = EXCLUDED.updated_at""",
                eventId, subscriber, status, attempts, next == null ? null : Timestamp.from(next), error, Timestamp.from(at));
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
            // CMN-14: 구독자별 DLQ(DEAD) 구독자만 다시 전달(이미 DONE 인 구독자는 다시 받지 않는다)
            jdbc.update("""
                    UPDATE core.outbox_delivery SET status = 'RETRY', attempts = 0, next_attempt_at = ?, updated_at = ?
                     WHERE event_id = ? AND status = 'DEAD'""", Timestamp.from(clock.instant()),
                    Timestamp.from(clock.instant()), eventId);
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
