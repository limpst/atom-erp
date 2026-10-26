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

    /** SAL BR-SAL-011 HRQ 투입 가능성 포트 대역(HRQ 연동 전) — 기본 OK, 테스트가 충돌 계약을 지정(TC-SAL-030) */
    @Bean
    @org.springframework.context.annotation.Primary
    FakeAssignmentFeasibility fakeAssignmentFeasibility() {
        return new FakeAssignmentFeasibility();
    }

    public static class FakeAssignmentFeasibility
            implements com.atomeng.erp.sal.application.port.AssignmentFeasibilityPort {
        private final java.util.Set<UUID> conflicting = java.util.concurrent.ConcurrentHashMap.newKeySet();

        public void markConflict(UUID contractId) {
            conflicting.add(contractId);
        }

        @Override
        public Result check(UUID contractId, UUID estimateId, java.time.LocalDate from, java.time.LocalDate to) {
            return conflicting.contains(contractId)
                    ? new Result(false, java.util.List.of(new Conflict(UUID.nameUUIDFromBytes(new byte[] {1}),
                            "합성: 같은 기간 다른 프로젝트 투입")))
                    : Result.okResult();
        }
    }

    /**
     * QAR Atom AI Core 대역 — 실제 vLLM 은 호출하지 않는다. 기본: 지적 0건. 테스트가 응답 함수(합성 지적 · 예외)를 지정.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    FakeAtomAiCore fakeAtomAiCore() {
        return new FakeAtomAiCore();
    }

    public static class FakeAtomAiCore implements com.atomeng.erp.qar.application.port.AtomAiCorePort {
        private volatile java.util.function.Function<Request, Response> behavior = r -> new Response("stub-model",
                java.util.List.of());
        private final AtomicInteger calls = new AtomicInteger();

        public void respond(java.util.function.Function<Request, Response> f) {
            this.behavior = f;
        }

        public void reset() {
            behavior = r -> new Response("stub-model", java.util.List.of());
            calls.set(0);
        }

        public int calls() {
            return calls.get();
        }

        @Override
        public Response review(Request request) {
            calls.incrementAndGet();
            return behavior.apply(request);
        }
    }

    /** QAR 원천 데이터 포트 대역(HRQ · FLD · FAC 미연동) — 기본 미연동 · 자격 UNKNOWN, 테스트가 무효 승인자 지정(TC-QAR-011) */
    @Bean
    @org.springframework.context.annotation.Primary
    FakeQarEvidence fakeQarEvidence() {
        return new FakeQarEvidence();
    }

    public static class FakeQarEvidence implements com.atomeng.erp.qar.application.port.QarEvidencePort {
        private final java.util.Set<UUID> invalidApprovers = java.util.concurrent.ConcurrentHashMap.newKeySet();

        public void markInvalid(UUID approver) {
            invalidApprovers.add(approver);
        }

        public void clear() {
            invalidApprovers.clear();
        }

        @Override
        public com.atomeng.erp.qar.domain.autocheck.ReviewContext.ExternalEvidence evidence(Query q) {
            return com.atomeng.erp.qar.domain.autocheck.ReviewContext.ExternalEvidence.NONE;
        }

        @Override
        public Qualification approverQualification(UUID approverId, UUID projectId, java.time.LocalDate on) {
            return invalidApprovers.contains(approverId) ? Qualification.INVALID : Qualification.UNKNOWN;
        }
    }

    /**
     * AIP Python 워커 대역 — 실제 워커 · vLLM 은 호출하지 않는다. 기본: 활성, 경로별 계약 예시 응답(jobId 에코).
     * 요청 본문을 기록해 시험이 기록된 OpenAPI 계약(AipContract)으로 검증한다. enabled=false 면 미설정 워커와 같다.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    FakeAipWorker fakeAipWorker() {
        return new FakeAipWorker();
    }

    public static class FakeAipWorker implements com.atomeng.erp.aip.application.port.AipWorkerPort {
        private volatile boolean enabled = true;
        private final Map<String, java.util.function.Function<com.fasterxml.jackson.databind.JsonNode,
                com.fasterxml.jackson.databind.JsonNode>> handlers = new java.util.concurrent.ConcurrentHashMap<>();
        private final java.util.List<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> requests =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        public void reset() {
            enabled = true;
            handlers.clear();
            requests.clear();
        }

        public void setEnabled(boolean v) {
            enabled = v;
        }

        public void on(String path, java.util.function.Function<com.fasterxml.jackson.databind.JsonNode,
                com.fasterxml.jackson.databind.JsonNode> fn) {
            handlers.put(path, fn);
        }

        public java.util.List<Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> requests() {
            synchronized (requests) {
                return java.util.List.copyOf(requests);
            }
        }

        public long calls(String path) {
            return requests().stream().filter(e -> e.getKey().equals(path)).count();
        }

        @Override
        public boolean enabled() {
            return enabled;
        }

        @Override
        public Response post(String path, com.fasterxml.jackson.databind.JsonNode body, java.time.Duration timeout) {
            if (!enabled) {
                throw new com.atomeng.erp.aip.api.AipJobApi.AipDisabledException("DISABLED: 시험 대역 비활성");
            }
            requests.add(Map.entry(path, body.deepCopy()));
            var fn = handlers.get(path);
            return new Response(200, fn != null ? fn.apply(body) : AipSamples.reply(path, body), 5);
        }
    }

    @Bean
    TestEventConsumer testEventConsumer(JdbcTemplate jdbc) {
        return new TestEventConsumer(jdbc);
    }

    /** 항상 500 을 내는 엔드포인트 — "5xx 는 멱등 저장하지 않음" 검증용 · 바이너리 응답(CMN-09) 검증용 */
    @RestController
    public static class BoomController {
        public final AtomicInteger calls = new AtomicInteger();
        public final AtomicInteger binaryCalls = new AtomicInteger();

        /** 보안 검토 CMN-09: 비 JSON(PDF) 응답 — 멱등 저장 · 재반환 제외 확인용(합성 바이트) */
        @PostMapping("/api/v1/test/binary")
        public org.springframework.http.ResponseEntity<byte[]> binary() {
            int n = binaryCalls.incrementAndGet();
            return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .body(("%PDF-1.7 synthetic " + n).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        }

        @PostMapping("/api/v1/test/boom")
        public Map<String, Object> boom() {
            calls.incrementAndGet();
            throw new IllegalStateException("synthetic failure");
        }
    }

    @Bean
    FanoutSubscribers fanoutSubscribers() {
        return new FanoutSubscribers();
    }

    /**
     * 보안 검토 CMN-14 시험용: 같은 이벤트(tst.fanout.raised)의 두 구독자 — good 은 항상 성공, bad 는 failBad 이면 실패.
     * 커밋된 처리 = core.processed_event(consumer, event_id) 행(구독자 트랜잭션과 함께 커밋 · 롤백).
     */
    public static class FanoutSubscribers {
        public static final String GOOD = "test.fanout-good";
        public static final String BAD = "test.fanout-bad";
        private final AtomicInteger good = new AtomicInteger();
        private final AtomicInteger bad = new AtomicInteger();
        private volatile boolean failBad;

        @EventListener(condition = "#env.eventType == 'tst.fanout.raised'")
        @IdempotentListener(consumer = GOOD)
        public void onGood(EventEnvelope env) {
            good.incrementAndGet();
        }

        @EventListener(condition = "#env.eventType == 'tst.fanout.raised'")
        @IdempotentListener(consumer = BAD)
        public void onBad(EventEnvelope env) {
            bad.incrementAndGet();
            if (failBad) {
                throw new IllegalStateException("injected subscriber failure");
            }
        }

        public void reset(boolean failBad) {
            good.set(0);
            bad.set(0);
            this.failBad = failBad;
        }

        public int goodDeliveries() {
            return good.get();
        }

        public int badDeliveries() {
            return bad.get();
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
