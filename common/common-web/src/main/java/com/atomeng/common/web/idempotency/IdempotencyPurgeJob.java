package com.atomeng.common.web.idempotency;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/** 24시간 경과 멱등 키 삭제 배치 (설계 9.4 "24시간 후 배치 삭제"). */
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyStore store;
    private final IdempotencyProperties props;
    private final Clock clock;

    public IdempotencyPurgeJob(IdempotencyStore store, IdempotencyProperties props, Clock clock) {
        this.store = store;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${atom.idempotency.purge-cron:0 15 * * * *}", zone = "Asia/Seoul")
    public int purge() {
        int n = store.purgeOlderThan(clock.instant().minus(props.retention()));
        if (n > 0) {
            log.info("idempotency keys purged: {}", n);
        }
        return n;
    }
}
