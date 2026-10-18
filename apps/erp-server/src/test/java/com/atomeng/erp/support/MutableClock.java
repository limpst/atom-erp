package com.atomeng.erp.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트용 조작 가능한 시계 — 백오프·24시간 보관 검증에 사용. */
public class MutableClock extends Clock {

    private volatile Instant offsetBase = null;
    private volatile Duration offset = Duration.ZERO;

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return Clock.fixed(instant(), zone);
    }

    @Override
    public Instant instant() {
        Instant base = offsetBase != null ? offsetBase : Instant.now();
        return base.plus(offset);
    }

    public void advance(Duration d) {
        offset = offset.plus(d);
    }

    public void reset() {
        offsetBase = null;
        offset = Duration.ZERO;
    }
}
