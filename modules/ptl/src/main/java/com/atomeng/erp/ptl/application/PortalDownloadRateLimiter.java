package com.atomeng.erp.ptl.application;

import com.atomeng.common.core.error.DomainException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 보안 검토 PTL-03 — 고객사(cid)별 워터마크 다운로드 속도 제한(1분 슬라이딩 창).
 * 다운로드마다 동기 워터마크(CPU · 임시파일)를 만들므로 반복 호출 DoS 를 막는다. 한도 초과 → 429 {@code PTL-429-001}
 * (+ {@code retryAfterSeconds}). 한도는 {@code atom.ptl.download.max-per-minute}(기본 10, 0 이하면 끔).
 * 노드 메모리 안의 계수기다 — 서버가 여러 대면 노드마다 따로 센다(운영 다중 노드는 게이트웨이/Redis 제한을 함께 둔다).
 */
@Component
public class PortalDownloadRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxPerMinute;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> windows = new ConcurrentHashMap<>();

    public PortalDownloadRateLimiter(@Value("${atom.ptl.download.max-per-minute:10}") int maxPerMinute, Clock clock) {
        this.maxPerMinute = maxPerMinute;
        this.clock = clock;
    }

    public int maxPerMinute() {
        return maxPerMinute;
    }

    /** 한 번 쓴다(허용되면 기록). 초과면 DomainException(PTL-429-001) */
    public void acquire(UUID customerId) {
        if (maxPerMinute <= 0) {
            return;
        }
        Instant now = clock.instant();
        Deque<Instant> q = windows.computeIfAbsent(customerId, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && !q.peekFirst().isAfter(now.minus(WINDOW))) {
                q.pollFirst();
            }
            if (q.size() >= maxPerMinute) {
                long retry = Math.max(1, Duration.between(now, q.peekFirst().plus(WINDOW)).toSeconds());
                throw new DomainException(PtlErrors.DOWNLOAD_RATE_LIMITED,
                        "보고서 다운로드는 고객사당 1분에 %d건까지입니다 — %d초 뒤 다시 시도하세요".formatted(maxPerMinute, retry))
                        .with("retryAfterSeconds", retry).with("limitPerMinute", maxPerMinute);
            }
            q.addLast(now);
        }
    }
}
