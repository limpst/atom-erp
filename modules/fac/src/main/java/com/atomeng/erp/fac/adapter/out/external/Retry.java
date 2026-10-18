package com.atomeng.erp.fac.adapter.out.external;

import com.atomeng.erp.fac.application.port.RegisterPorts.ExternalUnavailable;
import java.time.Duration;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 단순 재시도: 타임아웃·I/O(ResourceAccessException)·5xx 만 재시도, 지수 백오프(×2, 최대 2s).
 * 운영은 Resilience4j retry + circuitBreaker 로 교체 예정(02-IMPL Step 3) — 지금은 의존성 없이 같은 정책.
 */
final class Retry {

    private static final Logger log = LoggerFactory.getLogger(Retry.class);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    private Retry() {}

    static <T> T call(String name, int maxAttempts, Duration initialBackoff, Supplier<T> action) {
        Duration backoff = initialBackoff;
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return action.get();
            } catch (ResourceAccessException | HttpServerErrorException e) {
                last = e;
                log.warn("{} 호출 실패 {}/{}: {}", name, attempt, maxAttempts, e.getClass().getSimpleName());
                if (attempt < maxAttempts) {
                    sleep(backoff);
                    backoff = backoff.multipliedBy(2).compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : backoff.multipliedBy(2);
                }
            }
        }
        throw new ExternalUnavailable(last instanceof ResourceAccessException ? "TIMEOUT" : "HTTP_5XX", last);
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExternalUnavailable("INTERRUPTED", e);
        }
    }
}
