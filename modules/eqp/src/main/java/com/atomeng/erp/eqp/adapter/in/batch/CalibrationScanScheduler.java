package com.atomeng.erp.eqp.adapter.in.batch;

import com.atomeng.erp.eqp.application.CalibrationScanService;
import com.atomeng.erp.eqp.application.EquipmentService;
import java.time.Clock;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 매일 00:10 KST 검교정 스캔 (설계 B6 v0.2 expired 00:10 — due-soon 도 같은 배치로 통합).
 * 다중 Pod 중복은 notification_log 멱등키 + 장비 행 잠금으로 방어(ShedLock 은 후속).
 */
@Component
@ConditionalOnProperty(prefix = "atom.eqp.calibration-scan", name = "enabled", havingValue = "true", matchIfMissing = true)
class CalibrationScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(CalibrationScanScheduler.class);

    private final CalibrationScanService scans;
    private final Clock clock;

    CalibrationScanScheduler(CalibrationScanService scans, Clock clock) {
        this.scans = scans;
        this.clock = clock;
    }

    @Scheduled(cron = "${atom.eqp.calibration-scan.cron:0 10 0 * * *}", zone = "Asia/Seoul")
    void run() {
        var r = scans.scan(LocalDate.now(clock.withZone(EquipmentService.SEOUL)));
        log.info("eqp calibration scan {}: due-soon={} expired={} calDue={}", r.date(), r.dueSoonPublished(),
                r.expiredPublished(), r.movedToCalDue());
    }
}
