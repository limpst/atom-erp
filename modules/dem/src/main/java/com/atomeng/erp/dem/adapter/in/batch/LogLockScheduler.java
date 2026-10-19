package com.atomeng.erp.dem.adapter.in.batch;

import com.atomeng.erp.dem.application.DailyLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 감리일지 자동 잠금 (BR-DEM-006, 설계 B7.1) — 매일 00:00 KST 전일 이전 미잠금 일지를 정규화 JSON 해시와 함께 잠근다.
 * 다중 인스턴스는 FOR UPDATE SKIP LOCKED 로 중복 잠금 0(ShedLock 은 후속). 해외 현장 TZ 별 잠금은 후속.
 */
@Component
@ConditionalOnProperty(prefix = "atom.dem.log-lock", name = "enabled", havingValue = "true", matchIfMissing = true)
class LogLockScheduler {

    private static final Logger log = LoggerFactory.getLogger(LogLockScheduler.class);

    private final DailyLogService logs;

    LogLockScheduler(DailyLogService logs) {
        this.logs = logs;
    }

    @Scheduled(cron = "${atom.dem.log-lock.cron:0 0 0 * * *}", zone = "Asia/Seoul")
    void run() {
        log.info("dem daily-log lock: locked={}", logs.lockDue());
    }
}
