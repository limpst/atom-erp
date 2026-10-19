package com.atomeng.erp.ptl.application;

import com.atomeng.common.core.UuidV7;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 고객 접근 로그(ptl.access_log, append-only · RLS). 거부(DENIED — 없음 또는 다른 고객사 자원 시도, TC-PTL-01)는 404 응답으로
 * 업무 트랜잭션이 롤백돼도 남도록 별도 트랜잭션(REQUIRES_NEW)으로 기록하고 보안 로그(PTL_CROSS_TENANT_ATTEMPT)를 남긴다.
 */
@Component
public class PortalAccessLog {

    private static final Logger security = LoggerFactory.getLogger("SECURITY.PTL");

    private final JdbcTemplate jdbc;
    private final PortalRls rls;
    private final Clock clock;

    public PortalAccessLog(JdbcTemplate jdbc, PortalRls rls, Clock clock) {
        this.jdbc = jdbc;
        this.rls = rls;
        this.clock = clock;
    }

    /** 같은 트랜잭션(업무와 함께 커밋) — VIEW · DOWNLOAD. 호출자는 RLS 컨텍스트 안이어야 한다 */
    public void appendInContext(UUID customerId, UUID actorId, String action, String targetType, UUID targetId,
                                String detail) {
        jdbc.update("""
                INSERT INTO ptl.access_log(id, customer_id, actor_id, action, target_type, target_id, detail, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), customerId, actorId, action, targetType, targetId,
                detail, Timestamp.from(clock.instant()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(PortalAccess.Customer c, String targetType, UUID targetId, String reason) {
        security.warn("PTL_CROSS_TENANT_ATTEMPT customer={} actor={} target={}:{} reason={}", c.customerId(), c.actorId(),
                targetType, targetId, reason);
        rls.runAsCustomer(c.customerId(),
                () -> appendInContext(c.customerId(), c.actorId(), "DENIED", targetType, targetId, reason));
    }
}
