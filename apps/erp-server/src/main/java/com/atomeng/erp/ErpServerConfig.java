package com.atomeng.erp;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;

@Configuration(proxyBeanMethods = false)
class ErpServerConfig {

    /** JPA 감사 시각도 주입된 Clock 을 따른다(테스트 시계 제어). µs 절삭 = timestamptz 정밀도. */
    @Bean
    DateTimeProvider atomDateTimeProvider(Clock clock) {
        return () -> Optional.of(clock.instant().truncatedTo(ChronoUnit.MICROS));
    }
}
