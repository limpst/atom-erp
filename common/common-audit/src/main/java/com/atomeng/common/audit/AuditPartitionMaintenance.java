package com.atomeng.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * audit_log 월 파티션 사전 생성 (00-IMPL Step 6: 매월 25일 03:00, 다음·다다음 달).
 * 5년 경과 파티션 DETACH → 아카이브는 Step 15(백업) 와 함께.
 */
public class AuditPartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionMaintenance.class);

    private final JdbcTemplate jdbc;

    public AuditPartitionMaintenance(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${atom.audit.partition-cron:0 0 3 25 * *}", zone = "Asia/Seoul")
    public void ensureNextPartitions() {
        jdbc.execute("SELECT core.ensure_audit_partition((now() + interval '1 month')::date)");
        jdbc.execute("SELECT core.ensure_audit_partition((now() + interval '2 month')::date)");
        log.info("audit_log partitions ensured for +1, +2 months");
    }
}
