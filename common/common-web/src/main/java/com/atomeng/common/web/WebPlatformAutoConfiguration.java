package com.atomeng.common.web;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.web.idempotency.IdempotencyFilter;
import com.atomeng.common.web.idempotency.IdempotencyProperties;
import com.atomeng.common.web.idempotency.IdempotencyPurgeJob;
import com.atomeng.common.web.idempotency.IdempotencyStore;
import com.atomeng.common.web.problem.GlobalProblemHandler;
import com.atomeng.common.web.problem.ProblemFactory;
import com.atomeng.common.web.problem.ProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

/** common-web 자동 구성: problem+json, 멱등 키 필터·저장소·정리 배치. */
@AutoConfiguration
@EnableConfigurationProperties(IdempotencyProperties.class)
public class WebPlatformAutoConfiguration {

    /** 보안 필터 체인(-100) 뒤에서 실행되어야 actor 를 알 수 있다. */
    public static final int IDEMPOTENCY_FILTER_ORDER = -50;

    @Bean
    @ConditionalOnMissingBean
    Clock atomClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemFactory problemFactory(MessageSource messageSource) {
        return new ProblemFactory(messageSource);
    }

    @Bean
    @ConditionalOnMissingBean
    ProblemWriter problemWriter(ProblemFactory factory, ObjectMapper objectMapper) {
        return new ProblemWriter(factory, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    GlobalProblemHandler globalProblemHandler(ProblemFactory factory) {
        return new GlobalProblemHandler(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    IdempotencyStore idempotencyStore(JdbcTemplate jdbc, Clock clock) {
        return new IdempotencyStore(jdbc, clock);
    }

    @Bean
    FilterRegistrationBean<IdempotencyFilter> idempotencyFilter(IdempotencyStore store,
            ObjectProvider<CurrentActor> currentActor, ProblemWriter writer, IdempotencyProperties props,
            ObjectMapper objectMapper, Clock clock) {
        CurrentActor actor = currentActor.getIfAvailable(() -> Optional::empty);
        var reg = new FilterRegistrationBean<>(new IdempotencyFilter(store, actor, writer, props, objectMapper, clock));
        reg.setOrder(IDEMPOTENCY_FILTER_ORDER);
        reg.addUrlPatterns("/api/*");
        return reg;
    }

    @Bean
    @ConditionalOnProperty(prefix = "atom.idempotency", name = "purge-enabled", havingValue = "true", matchIfMissing = true)
    IdempotencyPurgeJob idempotencyPurgeJob(IdempotencyStore store, IdempotencyProperties props, Clock clock) {
        return new IdempotencyPurgeJob(store, props, clock);
    }
}
