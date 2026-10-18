package com.atomeng.erp.sal.adapter.in.batch;

import com.atomeng.erp.sal.application.BidCollectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 나라장터 공고 정기 수집(설계 B5.1, 30분). 기능 플래그 {@code atom.sal.collect.enabled}(기본 false — 01-IMPL 배포:
 * ATOM_SAL_G2B_COLLECT 는 stg 7일 검증 후 ON). 다중 Pod 단일 실행(ShedLock · Redis 락)은 후속.
 */
@Component
@ConditionalOnProperty(prefix = "atom.sal.collect", name = "enabled", havingValue = "true")
class BidCollectScheduler {

    private static final Logger log = LoggerFactory.getLogger(BidCollectScheduler.class);

    private final BidCollectService collector;

    BidCollectScheduler(BidCollectService collector) {
        this.collector = collector;
    }

    @Scheduled(cron = "${atom.sal.collect.cron:0 0/30 * * * *}", zone = "Asia/Seoul")
    void run() {
        var r = collector.runScheduled();
        log.info("sal 공고 수집 {} {}~{} success={} fetched={} new={} error={}", r.jobType(), r.from(), r.to(),
                r.success(), r.fetched(), r.inserted(), r.error());
    }
}
