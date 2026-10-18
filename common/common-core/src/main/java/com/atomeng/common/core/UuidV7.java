package com.atomeng.common.core;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * RFC 9562 UUID version 7 생성기 (설계 7.2: "UUID v7 (애플리케이션 생성)").
 *
 * <p>구조: 48비트 Unix epoch 밀리초 | ver(4)=0111 | rand_a 12비트 | var(2)=10 | rand_b 62비트.
 * 같은 밀리초 안에서는 rand_a를 카운터로 사용해 프로세스 내 <b>단조 증가</b>를 보장한다
 * (RFC 9562 §6.2 Method 1). DB 쪽 동일 규칙 함수는 {@code core.uuid_generate_v7()}.
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock LOCK = new ReentrantLock();
    private static long lastMillis = -1L;
    private static int counter; // 12비트 rand_a 영역

    private static volatile Clock clock = Clock.systemUTC();

    private UuidV7() {}

    /** 새 UUID v7. 스레드 안전, 프로세스 내 단조 증가. */
    public static UUID next() {
        long millis;
        int seq;
        LOCK.lock();
        try {
            millis = clock.millis();
            if (millis <= lastMillis) {
                // 같은 ms 또는 시계 역행: 직전 ms를 유지하고 카운터 증가
                millis = lastMillis;
                counter++;
                if (counter > 0xFFF) { // 카운터 소진 → 논리 시각을 1ms 전진
                    millis = ++lastMillis;
                    counter = RANDOM.nextInt(0x400);
                }
            } else {
                lastMillis = millis;
                counter = RANDOM.nextInt(0x400); // 상위 여유를 남긴 작은 랜덤 시작값
            }
            seq = counter;
        } finally {
            LOCK.unlock();
        }
        long msb = (millis & 0xFFFF_FFFF_FFFFL) << 16 | 0x7000L | (seq & 0xFFFL);
        long lsb = (RANDOM.nextLong() & 0x3FFF_FFFF_FFFF_FFFFL) | 0x8000_0000_0000_0000L;
        return new UUID(msb, lsb);
    }

    public static boolean isV7(UUID u) {
        return u != null && u.version() == 7 && u.variant() == 2;
    }

    /** UUID v7에 담긴 생성 시각(ms 정밀도). */
    public static Instant timestampOf(UUID u) {
        if (!isV7(u)) {
            throw new IllegalArgumentException("UUID v7 required: " + u);
        }
        return Instant.ofEpochMilli(u.getMostSignificantBits() >>> 16);
    }

    /** 테스트용 시계 교체(패키지 전용). */
    static void useClock(Clock c) {
        clock = c;
    }
}
