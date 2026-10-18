package com.atomeng.common.events;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 컨슈머 멱등 처리 도우미 (설계 10.2 하단, 10.3-1).
 * 업무 처리와 <b>같은 트랜잭션</b>에서 호출해야 한다(MANDATORY): 처리 실패 시 processed_event 도 함께 롤백.
 */
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return true = 처음 처리(1행 INSERT) → 업무 처리 진행, false = 이미 처리됨(0행) → 스킵
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String consumer, UUID eventId) {
        return jdbc.update("""
                INSERT INTO core.processed_event(consumer, event_id) VALUES (?, ?)
                ON CONFLICT DO NOTHING""", consumer, eventId) == 1;
    }
}
