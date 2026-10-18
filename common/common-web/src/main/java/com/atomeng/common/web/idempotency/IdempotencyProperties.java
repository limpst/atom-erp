package com.atomeng.common.web.idempotency;

import java.time.Duration;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 멱등 키 설정 (설계 9.4).
 *
 * @param requiredClients 키가 필수인 OIDC 클라이언트 (기본 field-app → 없으면 428 CORE-428-001)
 * @param retention       보관 기간 (기본 24시간, 이후 배치 삭제·만료 키는 신규 요청으로 취급)
 * @param lockTimeout     처리 중 잠금 유효 시간 (Redis SET NX 30초와 동일 의미)
 * @param purgeEnabled    24시간 경과 행 삭제 배치 활성화
 */
@ConfigurationProperties("atom.idempotency")
public record IdempotencyProperties(
        Set<String> requiredClients,
        Duration retention,
        Duration lockTimeout,
        Boolean purgeEnabled) {

    public IdempotencyProperties {
        requiredClients = requiredClients == null ? Set.of("field-app") : Set.copyOf(requiredClients);
        retention = retention == null ? Duration.ofHours(24) : retention;
        lockTimeout = lockTimeout == null ? Duration.ofSeconds(30) : lockTimeout;
        purgeEnabled = purgeEnabled == null ? Boolean.TRUE : purgeEnabled;
    }
}
