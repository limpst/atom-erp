package com.atomeng.erp.ptl.application;

import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 고객 격리 실행기 (BR-PTL-001 · NFR-PTL-01 · 09-IMPL Step 3 "SET LOCAL" 의 이번 단계 구현).
 * 현재 트랜잭션 안에서만 {@code set_config('app.customer_id', cid, true)} + {@code SET LOCAL ROLE ptl_customer} 로 바꿔
 * ptl 테이블 쿼리를 실행하고, 끝나면 역할 · 컨텍스트를 되돌린다(같은 이벤트 트랜잭션의 다른 모듈 리스너에 새지 않게).
 * <ul>
 *   <li>ptl_customer 는 NOBYPASSRLS · ptl 스키마 권한만 → 세션 계정이 슈퍼유저여도 RLS 가 적용된다.</li>
 *   <li>트랜잭션이 없으면 즉시 실패(fail-closed). SET LOCAL 이라 커넥션 풀에 남지 않는다.</li>
 *   <li>안쪽에서 다른 모듈 API(fac · rpt · sal) · outbox · 감사 로그를 부르면 권한 오류 → 반드시 바깥에서 호출.</li>
 * </ul>
 */
@Component
public class PortalRls {

    public static final String DB_ROLE = "ptl_customer";
    private static final Logger log = LoggerFactory.getLogger(PortalRls.class);
    private static final ThreadLocal<UUID> ACTIVE = new ThreadLocal<>();

    private final JdbcTemplate jdbc;

    public PortalRls(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public <T> T asCustomer(UUID customerId, Supplier<T> work) {
        if (customerId == null) {
            throw new IllegalStateException("PTL 고객 컨텍스트 없음(fail-closed)");
        }
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("PTL RLS 실행은 트랜잭션 안에서만(SET LOCAL)");
        }
        if (ACTIVE.get() != null) {
            throw new IllegalStateException("PTL RLS 컨텍스트 중첩 금지");
        }
        jdbc.queryForObject("SELECT set_config('app.customer_id', ?, true)", String.class, customerId.toString());
        jdbc.execute("SET LOCAL ROLE " + DB_ROLE);
        ACTIVE.set(customerId);
        try {
            return work.get();
        } finally {
            ACTIVE.remove();
            try {
                jdbc.execute("RESET ROLE");
                jdbc.queryForObject("SELECT set_config('app.customer_id', '', true)", String.class);
            } catch (RuntimeException e) {
                // 이미 실패(aborted)한 트랜잭션 — 롤백이 SET LOCAL 을 함께 되돌린다
                log.debug("PTL RLS 컨텍스트 해제 생략(트랜잭션 중단): {}", e.getMessage());
            }
        }
    }

    public void runAsCustomer(UUID customerId, Runnable work) {
        asCustomer(customerId, () -> {
            work.run();
            return null;
        });
    }
}
