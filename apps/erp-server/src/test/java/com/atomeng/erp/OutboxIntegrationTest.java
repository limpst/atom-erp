package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.EventTransport;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.core.api.ProjectCreatedEvent;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.TestBeans.TestEventConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 설계 10장 Transactional Outbox — TC-CORE-004, TC-CORE-005, 백오프·DLQ, 컨슈머 멱등 */
class OutboxIntegrationTest extends IntegrationTestBase {

    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxPublisher publisher;
    @Autowired
    TestEventConsumer consumer;
    @Autowired
    PlatformTransactionManager txManager;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    com.atomeng.erp.core.application.ProjectService projectService;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead, core.processed_event");
        consumer.reset();
        clock.reset();
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private UUID createProjectViaApi(UUID actor, UUID clientId, String name) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/core/projects",
                        "{\"name\":\"" + name + "\",\"serviceType\":\"PRECISE_DIAG\",\"clientId\":\"" + clientId
                                + "\",\"contractAmount\":12000000}"), actor, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.projectNo").value(org.hamcrest.Matchers.startsWith("PRJ-")))
                .andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private int pending() {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE published_at IS NULL", Integer.class);
    }

    @Test
    @DisplayName("설계 10.2: 프로젝트 생성 = 업무 행 + outbox(core.project.created) 같은 트랜잭션, envelope 필드 채움")
    void projectCreationWritesOutboxEvent() throws Exception {
        UUID actor = actor();
        UUID clientId = createClient(actor, "합성발주처-" + UUID.randomUUID());
        UUID projectId = createProjectViaApi(actor, clientId, "합성 정밀안전진단");

        var row = jdbc.queryForMap("""
                SELECT event_type, aggregate_type, aggregate_id, payload->>'projectNo' AS no,
                       headers->>'actorId' AS actor, attempts, published_at FROM core.outbox_event""");
        assertThat(row.get("event_type")).isEqualTo("core.project.created");
        assertThat(row.get("aggregate_type")).isEqualTo("Project");
        assertThat(row.get("aggregate_id")).isEqualTo(projectId);
        assertThat((String) row.get("no")).matches("PRJ-\\d{4}-\\d{4}");
        assertThat(row.get("actor")).isEqualTo(actor.toString());
        assertThat(row.get("published_at")).isNull();

        UUID eventId = jdbc.queryForObject("SELECT id FROM core.outbox_event", UUID.class);
        var result = relay.pollOnce();
        assertThat(result.published()).isEqualTo(1);
        assertThat(pending()).isZero();
        assertThat(consumer.sideEffects(eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-CORE-004: 프로젝트 생성 트랜잭션 중 예외 → 프로젝트·outbox 모두 롤백, 이벤트 미발행")
    void tcCore004_rollbackLeavesNoEvent() throws Exception {
        UUID clientId = createClient(actor(), "합성발주처-rb-" + UUID.randomUUID());
        String name = "합성 롤백 프로젝트 " + UUID.randomUUID();
        var tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            projectService.create(new com.atomeng.erp.core.application.ProjectService.CreateProject(
                    name, "PRECISE_INSP", clientId, null, null, null));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event", Integer.class)).isEqualTo(1);
            throw new IllegalStateException("후속 단계 실패(주입)");
        })).hasMessageContaining("주입");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.project WHERE name = ?", Integer.class, name))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event", Integer.class)).isZero();
        assertThat(relay.pollOnce().published()).isZero();
        assertThat(consumer.deliveries()).isZero();

        // API 경로: 존재하지 않는 고객 → 422, outbox 0
        mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성\",\"serviceType\":\"PRECISE_DIAG\","
                        + "\"clientId\":\"" + UUID.randomUUID() + "\"}"), actor(), "PM"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("CORE-422-001"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event", Integer.class)).isZero();
    }

    @Test
    @DisplayName("설계 10.2: OutboxPublisher 는 업무 트랜잭션 밖에서 호출 불가 (MANDATORY)")
    void publisherRequiresTransaction() {
        assertThatThrownBy(() -> publisher.publish(new ProjectCreatedEvent(UUID.randomUUID(), "PRJ-2026-9999",
                "PRECISE_DIAG", UUID.randomUUID()))).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("TC-CORE-005: Relay 강제 종료(처리 도중 중단) 후 재기동 → 미발행 이벤트 전량 발행, 중복 소비 0")
    void tcCore005_relayCrashAndRestart() throws Exception {
        UUID actor = actor();
        UUID clientId = createClient(actor, "합성발주처-crash-" + UUID.randomUUID());
        for (int i = 0; i < 5; i++) {
            createProjectViaApi(actor, clientId, "합성 프로젝트 " + i);
        }
        assertThat(pending()).isEqualTo(5);
        List<UUID> eventIds = jdbc.queryForList("SELECT id FROM core.outbox_event", UUID.class);

        // Relay #1: 2건 발행 후 3번째 이벤트를 컨슈머에 전달하는 도중 프로세스가 죽은 상황
        //  → 전송과 published_at 갱신·컨슈머 처리가 한 트랜잭션이라 3번째는 통째로 롤백
        AtomicInteger sent = new AtomicInteger();
        EventTransport crashing = env -> {
            if (sent.incrementAndGet() == 3) {
                events.publishEvent(env);                       // 컨슈머까지 전달된 뒤
                throw new IllegalStateException("relay killed"); // 커밋 전에 중단
            }
            events.publishEvent(env);
        };
        var relay1 = new OutboxRelay(jdbc, txManager, crashing, objectMapper, new SimpleMeterRegistry(), clock);
        var r1 = relay1.pollOnce(3);
        assertThat(r1.published()).isEqualTo(2);
        assertThat(pending()).isEqualTo(3);
        assertThat(consumer.exactlyOnceCount(eventIds)).isEqualTo(2);
        assertThat(consumer.deliveries()).isEqualTo(3); // 3번째는 전달됐지만 롤백

        // Relay #2 재기동 (백오프 10초 경과 후)
        clock.advance(Duration.ofSeconds(11));
        var r2 = relay.pollOnce();
        assertThat(r2.published()).isEqualTo(3);
        assertThat(pending()).isZero();

        // 전량 1회씩만 소비(커밋된 부수효과 기준)
        assertThat(consumer.exactlyOnceCount(eventIds)).isEqualTo(5);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event WHERE consumer = ?",
                Integer.class, TestEventConsumer.CONSUMER)).isEqualTo(5);

        // at-least-once 재전달(같은 envelope 다시 도착) → processed_event 로 스킵, 부수효과 그대로
        UUID anyEvent = eventIds.get(0);
        var dup = new EventEnvelope(anyEvent, "core.project.created", 1, Instant.now().atOffset(java.time.ZoneOffset.UTC),
                "Project", UUID.randomUUID(), actor, null, objectMapper.createObjectNode());
        new TransactionTemplate(txManager).executeWithoutResult(s -> events.publishEvent(dup));
        assertThat(consumer.sideEffects(anyEvent)).isEqualTo(1);
        assertThat(consumer.exactlyOnceCount(eventIds)).isEqualTo(5);
    }

    @Test
    @DisplayName("설계 10.3: 실패 시 지수 백오프 10s→20s→40s→80s, 5회째 실패 → outbox_dead 이동, 재투입 후 발행")
    void backoffThenDeadLetterThenRequeue() throws Exception {
        UUID clientId = createClient(actor(), "합성발주처-dlq-" + UUID.randomUUID());
        createProjectViaApi(actor(), clientId, "합성 DLQ 프로젝트");
        UUID eventId = jdbc.queryForObject("SELECT id FROM core.outbox_event", UUID.class);
        consumer.setAlwaysFail(true);

        long[] expectedDelays = {10, 20, 40, 80};
        for (int attempt = 1; attempt <= 4; attempt++) {
            var r = relay.pollOnce();
            assertThat(r.failed()).as("attempt %d", attempt).isEqualTo(1);
            var row = jdbc.queryForMap("SELECT attempts, last_error, next_attempt_at FROM core.outbox_event");
            assertThat(row.get("attempts")).isEqualTo(attempt);
            assertThat((String) row.get("last_error")).contains("injected");
            Instant next = ((java.sql.Timestamp) row.get("next_attempt_at")).toInstant();
            long delay = Duration.between(clock.instant(), next).toSeconds();
            assertThat(delay).as("backoff after attempt %d", attempt)
                    .isBetween(expectedDelays[attempt - 1] - 1, expectedDelays[attempt - 1]);
            // 아직 때가 안 됐으면 재시도하지 않는다
            assertThat(relay.pollOnce()).isEqualTo(new OutboxRelay.PollResult(0, 0, 0));
            clock.advance(Duration.ofSeconds(expectedDelays[attempt - 1]));
        }
        var last = relay.pollOnce();
        assertThat(last.dead()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event", Integer.class)).isZero();
        var dead = jdbc.queryForMap("SELECT id, attempts, dead_at FROM core.outbox_dead");
        assertThat(dead.get("id")).isEqualTo(eventId);
        assertThat(dead.get("attempts")).isEqualTo(5);
        assertThat(dead.get("dead_at")).isNotNull();
        assertThat(consumer.deliveries()).isEqualTo(5);
        assertThat(consumer.sideEffects(eventId)).isZero();
        // 실패한 시도의 processed_event 는 업무와 함께 롤백되어 남지 않는다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event", Integer.class)).isZero();

        // 운영자 원인 조치 후 재투입(attempts=0)
        consumer.setAlwaysFail(false);
        assertThat(relay.requeueDead(eventId)).isEqualTo(1);
        assertThat(relay.pollOnce().published()).isEqualTo(1);
        assertThat(consumer.sideEffects(eventId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_dead", Integer.class)).isZero();
    }
}
