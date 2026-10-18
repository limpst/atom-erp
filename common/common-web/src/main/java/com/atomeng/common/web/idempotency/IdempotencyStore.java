package com.atomeng.common.web.idempotency;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * core.idempotency_key 저장소 (PK (actor_id, key), 설계 9.4).
 *
 * <p>처리 중 잠금: response_code IS NULL 인 행 = 처리 중. 운영 설계의 Redis {@code SET NX 30s} 와 같은 의미를
 * DB 행 하나로 구현했다(로컬·테스트에 Redis 불필요). INSERT … ON CONFLICT DO NOTHING 이 원자적 락 획득이다.
 * 각 호출은 자동 커밋(업무 트랜잭션 밖)이라 다른 요청에 즉시 보인다.
 */
public class IdempotencyStore {

    public record Entry(UUID actorId, String key, String requestHash, Integer responseCode,
                        String responseBody, Instant createdAt) {
        public boolean inProgress() {
            return responseCode == null;
        }
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public IdempotencyStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 처리 중 행 선점. 성공 시 true(이 요청이 소유자). */
    public boolean tryBegin(UUID actorId, String key, String requestHash) {
        return jdbc.update("""
                INSERT INTO core.idempotency_key(key, actor_id, request_hash, created_at)
                VALUES (?, ?, ?, ?) ON CONFLICT (actor_id, key) DO NOTHING""",
                key, actorId, requestHash, Timestamp.from(clock.instant())) == 1;
    }

    public Optional<Entry> find(UUID actorId, String key) {
        return jdbc.query("""
                SELECT actor_id, key, request_hash, response_code, response_body::text AS body, created_at
                  FROM core.idempotency_key WHERE actor_id = ? AND key = ?""",
                (rs, i) -> new Entry(rs.getObject("actor_id", UUID.class), rs.getString("key"),
                        rs.getString("request_hash"), (Integer) rs.getObject("response_code"),
                        rs.getString("body"), rs.getTimestamp("created_at").toInstant()),
                actorId, key).stream().findFirst();
    }

    /** 만료된 행(보관 기간 경과 또는 잠금 시간 초과)을 조건부로 새 요청 소유로 교체. */
    public boolean takeOver(Entry stale, String requestHash) {
        return jdbc.update("""
                UPDATE core.idempotency_key
                   SET request_hash = ?, response_code = NULL, response_body = NULL, created_at = ?
                 WHERE actor_id = ? AND key = ? AND created_at = ?""",
                requestHash, Timestamp.from(clock.instant()), stale.actorId(), stale.key(),
                Timestamp.from(stale.createdAt())) == 1;
    }

    public void complete(UUID actorId, String key, int status, String jsonBodyOrNull) {
        jdbc.update("""
                UPDATE core.idempotency_key SET response_code = ?, response_body = ?::jsonb
                 WHERE actor_id = ? AND key = ?""", status, jsonBodyOrNull, actorId, key);
    }

    /** 5xx·예외: 저장하지 않고 잠금 해제 → 재시도 시 재처리 허용 (설계 9.4) */
    public void release(UUID actorId, String key) {
        jdbc.update("DELETE FROM core.idempotency_key WHERE actor_id = ? AND key = ?", actorId, key);
    }

    /** 보관 기간 경과 행 삭제. 삭제 건수 반환. */
    public int purgeOlderThan(Instant cutoff) {
        return jdbc.update("DELETE FROM core.idempotency_key WHERE created_at < ?", Timestamp.from(cutoff));
    }
}
