package com.atomeng.common.events;

import com.atomeng.common.core.actor.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

/** common-events 자동 구성. Relay 스케줄은 atom.outbox.relay.enabled=true(기본)일 때만. */
@AutoConfiguration
public class EventsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, ObjectMapper om, ObjectProvider<CurrentActor> actor,
                                    ObjectProvider<Clock> clock) {
        return new OutboxPublisher(jdbc, om, actor.getIfAvailable(() -> Optional::empty),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    EventTransport eventTransport(ApplicationEventPublisher publisher,
                                  org.springframework.beans.factory.config.ConfigurableListableBeanFactory beans) {
        // CMN-14: 빈 팩토리를 넘겨 구독자 단위 전달 · 재시도 · DLQ 를 켠다
        return new SpringEventTransport(publisher, beans);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(JdbcTemplate jdbc, PlatformTransactionManager tx, EventTransport transport,
                            ObjectMapper om, ObjectProvider<MeterRegistry> meters, ObjectProvider<Clock> clock) {
        return new OutboxRelay(jdbc, tx, transport, om, meters.getIfAvailable(SimpleMeterRegistry::new),
                clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    ProcessedEvents processedEvents(JdbcTemplate jdbc) {
        return new ProcessedEvents(jdbc);
    }

    @Bean
    IdempotentListenerAspect idempotentListenerAspect(ProcessedEvents processed) {
        return new IdempotentListenerAspect(processed);
    }

    @Bean
    @ConditionalOnProperty(prefix = "atom.outbox.relay", name = "enabled", havingValue = "true", matchIfMissing = true)
    OutboxRelayScheduler outboxRelayScheduler(OutboxRelay relay) {
        return new OutboxRelayScheduler(relay);
    }

    /** 500ms 폴링. 다중 인스턴스는 SKIP LOCKED 로 안전(리더 선출 ShedLock/K8s Lease 는 Step 15). */
    public static class OutboxRelayScheduler {
        private final OutboxRelay relay;

        public OutboxRelayScheduler(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${atom.outbox.relay.poll-interval:500ms}")
        public void poll() {
            relay.pollOnce();
        }
    }
}
