package com.atomeng.erp.support;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 통합 테스트 전용 빈: 5xx 를 내는 컨트롤러, 멱등 컨슈머. */
@TestConfiguration(proxyBeanMethods = false)
public class TestBeans {

    // BoomController 는 @RestController 라 컴포넌트 스캔으로 등록된다(별도 @Bean 불필요).

    /** EQP 조종자 자격 포트 대역(HRQ 연동 전) — 기본 VALID, 테스트가 무효 · 조회 장애 조종자를 지정 */
    @Bean
    @org.springframework.context.annotation.Primary
    FakePilotQualification fakePilotQualification() {
        return new FakePilotQualification();
    }

    public static class FakePilotQualification
            implements com.atomeng.erp.eqp.application.port.PilotQualificationPort {
        private final java.util.Set<UUID> invalid = java.util.concurrent.ConcurrentHashMap.newKeySet();
        private final java.util.Set<UUID> failing = java.util.concurrent.ConcurrentHashMap.newKeySet();

        public void markInvalid(UUID pilotId) {
            invalid.add(pilotId);
        }

        public void markFailing(UUID pilotId) {
            failing.add(pilotId);
        }

        @Override
        public Result check(UUID pilotId, java.math.BigDecimal mtowKg, java.time.LocalDate on) {
            if (failing.contains(pilotId)) {
                throw new IllegalStateException("synthetic HRQ outage");
            }
            return invalid.contains(pilotId) ? new Result(Status.INVALID, "합성: 초경량비행장치 조종자 자격 만료")
                    : new Result(Status.VALID, "합성");
        }
    }

    @Bean
    TestEventConsumer testEventConsumer(JdbcTemplate jdbc) {
        return new TestEventConsumer(jdbc);
    }

    /** 항상 500 을 내는 엔드포인트 — "5xx 는 멱등 저장하지 않음" 검증용 */
    @RestController
    public static class BoomController {
        public final AtomicInteger calls = new AtomicInteger();

        @PostMapping("/api/v1/test/boom")
        public Map<String, Object> boom() {
            calls.incrementAndGet();
            throw new IllegalStateException("synthetic failure");
        }
    }

    /**
     * 멱등 컨슈머 예시 (설계 10.2). 부수효과를 <b>같은 트랜잭션의 DB 행</b>(audit_log, module 'TST')으로 남긴다
     * → 실패·크래시 시 processed_event 와 함께 롤백되어 "중복 소비 0" 을 DB 로 확인할 수 있다.
     */
    public static class TestEventConsumer {
        public static final String CONSUMER = "test.side-effect-counter";

        private final JdbcTemplate jdbc;
        // CGLIB 프록시(멱등 aspect) 너머에서도 보이도록 필드는 private + 메서드로만 접근
        private final AtomicInteger deliveries = new AtomicInteger();
        private volatile boolean alwaysFail;

        TestEventConsumer(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        @EventListener
        @IdempotentListener(consumer = CONSUMER)
        public void on(EventEnvelope env) {
            deliveries.incrementAndGet();
            jdbc.queryForObject("SELECT core.audit_append(NULL, NULL, 'TST', 'CREATE', 'Event', ?::uuid, "
                    + "NULL, NULL, NULL)", Long.class, env.eventId());
            if (alwaysFail) {
                throw new IllegalStateException("injected consumer/transport failure");
            }
        }

        public void reset() {
            deliveries.set(0);
            alwaysFail = false;
        }

        public void setAlwaysFail(boolean v) {
            alwaysFail = v;
        }

        public int deliveries() {
            return deliveries.get();
        }

        /** 이 이벤트의 커밋된 부수효과 횟수 */
        public int sideEffects(UUID eventId) {
            return jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'TST' AND target_id = ?",
                    Integer.class, eventId);
        }

        /** 주어진 이벤트들 중 부수효과가 정확히 1회인 개수 */
        public int exactlyOnceCount(java.util.Collection<UUID> eventIds) {
            return (int) eventIds.stream().filter(id -> sideEffects(id) == 1).count();
        }
    }
}
