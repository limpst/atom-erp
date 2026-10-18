package com.atomeng.erp.fac.adapter.in.batch;

import com.atomeng.erp.fac.application.DueScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 02:00 KST 도래 스캔. 다중 Pod 중복은 due_notification 멱등키로 방어(ShedLock 은 후속). */
@Component
@ConditionalOnProperty(prefix = "atom.fac.due-scan", name = "enabled", havingValue = "true", matchIfMissing = true)
class DueScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(DueScanScheduler.class);

    private final DueScanService service;

    DueScanScheduler(DueScanService service) {
        this.service = service;
    }

    @Scheduled(cron = "${atom.fac.due-scan.cron:0 0 2 * * *}", zone = "Asia/Seoul")
    void run() {
        var r = service.scan();
        log.info("fac due scan: approaching={} overdue={}", r.approaching(), r.overdue());
    }
}
