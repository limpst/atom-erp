package com.atomeng.erp.hrq.adapter.in.batch;

import com.atomeng.erp.hrq.application.CredentialService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 매일 06:00 KST 자격 만료 스캔 (03-IMPL Step 5). 다중 Pod 중복은 notification_log·상태 전이로 방어(ShedLock 후속) */
@Component
@ConditionalOnProperty(prefix = "atom.hrq.expiry", name = "enabled", havingValue = "true", matchIfMissing = true)
class ExpiryScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScanScheduler.class);

    private final CredentialService credentials;

    ExpiryScanScheduler(CredentialService credentials) {
        this.credentials = credentials;
    }

    @Scheduled(cron = "${atom.hrq.expiry.cron:0 0 6 * * *}", zone = "Asia/Seoul")
    void run() {
        var r = credentials.scan();
        log.info("hrq expiry scan: notified={} expiring={} expired={}", r.notified(), r.expiring(), r.expired());
    }
}
