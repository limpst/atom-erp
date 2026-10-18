package com.atomeng.common.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UuidV7Test {

    @AfterEach
    void resetClock() {
        UuidV7.useClock(Clock.systemUTC());
    }

    @Test
    @DisplayName("Step3: RFC 9562 version 7 / variant 10")
    void versionAndVariant() {
        UUID u = UuidV7.next();
        assertThat(u.version()).isEqualTo(7);
        assertThat(u.variant()).isEqualTo(2);
        assertThat(UuidV7.isV7(u)).isTrue();
        assertThat(UuidV7.isV7(UUID.randomUUID())).isFalse();
    }

    @Test
    @DisplayName("Step3: 타임스탬프 추출 = 생성 시각(ms)")
    void timestampRoundTrip() {
        Instant fixed = Instant.parse("2999-01-01T00:00:00.123Z"); // 직전 테스트의 논리 시각보다 미래
        UuidV7.useClock(Clock.fixed(fixed, ZoneOffset.UTC));
        assertThat(UuidV7.timestampOf(UuidV7.next())).isEqualTo(fixed);
        assertThatThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Step3: 같은 ms·시계 역행에서도 단조 증가(정렬 = 생성 순서)")
    void monotonicWithinSameMillisAndClockRollback() {
        UuidV7.useClock(Clock.fixed(Instant.parse("2026-10-19T00:00:01Z"), ZoneOffset.UTC));
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) { // 같은 ms 에서 카운터(12비트) 소진 → 논리 시각 전진
            ids.add(UuidV7.next());
        }
        UuidV7.useClock(Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC)); // 역행
        ids.add(UuidV7.next());
        for (int i = 1; i < ids.size(); i++) {
            assertThat(compareUnsigned(ids.get(i - 1), ids.get(i))).as("index %d", i).isNegative();
        }
    }

    @Test
    @DisplayName("Step3: 다중 스레드 10만 건 중복 없음")
    void uniqueAcrossThreads() {
        var q = new ConcurrentLinkedQueue<UUID>();
        IntStream.range(0, 8).parallel().forEach(t -> {
            for (int i = 0; i < 12_500; i++) {
                q.add(UuidV7.next());
            }
        });
        assertThat(new HashSet<>(q)).hasSize(100_000);
    }

    private static int compareUnsigned(UUID a, UUID b) {
        int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
