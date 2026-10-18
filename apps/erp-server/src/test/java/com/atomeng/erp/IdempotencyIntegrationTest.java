package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.core.util.CanonicalJson;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.web.idempotency.IdempotencyPurgeJob;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.TestBeans;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** 설계 9.4 멱등 키 처리 규격 — TC-CORE-002 및 규격 표 6행 전부 */
class IdempotencyIntegrationTest extends IntegrationTestBase {

    private static final String CLIENTS = "/api/v1/core/clients";

    @Autowired
    TestBeans.BoomController boom;
    @Autowired
    IdempotencyPurgeJob purgeJob;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private static String body(String name) {
        return "{\"name\":\"" + name + "\",\"clientType\":\"PRIVATE\"}";
    }

    private int clientsNamed(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM core.client WHERE name = ?", Integer.class, name);
    }

    @Test
    @DisplayName("TC-CORE-002: 같은 Idempotency-Key 로 고객 생성 3회 → 고객 1건, 2·3회차 Idempotent-Replayed: true")
    void tcCore002_sameKeyThreeTimesCreatesOneClient() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        String name = "합성고객-TC002-" + key.substring(0, 8);

        var first = mvc.perform(as(postJson(CLIENTS, body(name)), actor, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"))
                .andReturn();
        String id = json(first).get("id").asText();

        for (int i = 2; i <= 3; i++) {
            mvc.perform(as(postJson(CLIENTS, body(name)), actor, "SALES").header("Idempotency-Key", key))
                    .andExpect(status().isCreated())
                    .andExpect(header().string("Idempotent-Replayed", "true"))
                    .andExpect(jsonPath("$.id").value(id));
        }
        assertThat(clientsNamed(name)).isEqualTo(1);
        // 감사 로그도 1건(업무 로직 미실행)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE target_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("설계 9.4: 키 순서만 다른 JSON 본문은 같은 요청(정규화 해시) → 재반환")
    void canonicalJsonBodyIsSameRequest() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        String name = "합성고객-canon-" + key.substring(0, 8);
        mvc.perform(as(postJson(CLIENTS, "{\"name\":\"" + name + "\",\"clientType\":\"APT\"}"), actor, "SALES")
                .header("Idempotency-Key", key)).andExpect(status().isCreated());
        mvc.perform(as(postJson(CLIENTS, "{ \"clientType\":\"APT\", \"name\":\"" + name + "\" }"), actor, "SALES")
                        .header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(clientsNamed(name)).isEqualTo(1);
    }

    @Test
    @DisplayName("설계 9.4: 다른 actor 가 같은 key 사용 → 별개 네임스페이스, 신규 처리(타인 응답 노출 없음)")
    void differentActorSameKeyIsIndependent() throws Exception {
        String key = UUID.randomUUID().toString();
        String name = "합성고객-ns-" + key.substring(0, 8);
        var a = mvc.perform(as(postJson(CLIENTS, body(name)), actor(), "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn();
        var b = mvc.perform(as(postJson(CLIENTS, body(name)), actor(), "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed")).andReturn();
        assertThat(json(a).get("id").asText()).isNotEqualTo(json(b).get("id").asText());
        assertThat(clientsNamed(name)).isEqualTo(2);
    }

    @Test
    @DisplayName("설계 9.4: 같은 actor·같은 key·다른 본문 → 422 CORE-422-009")
    void sameKeyDifferentBodyIs422() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        mvc.perform(as(postJson(CLIENTS, body("합성고객-A-" + key)), actor, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated());
        mvc.perform(as(postJson(CLIENTS, body("합성고객-B-" + key)), actor, "SALES").header("Idempotency-Key", key))
                .andExpect(status().is(422))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE-422-009"));
        assertThat(clientsNamed("합성고객-B-" + key)).isZero();
    }

    @Test
    @DisplayName("설계 9.4: 같은 key·같은 본문이 처리 중 → 409 CORE-409-010")
    void inProgressIs409() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        String b = body("합성고객-inflight-" + key.substring(0, 8));
        insertKeyRow(actor, key, hashOf(CLIENTS, b), null, clock.instant());   // 처리 중(응답 없음)
        mvc.perform(as(postJson(CLIENTS, b), actor, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORE-409-010"));
    }

    @Test
    @DisplayName("처리 중 잠금이 30초를 넘으면(프로세스 중단) 다음 요청이 이어받아 처리")
    void staleLockIsTakenOver() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        String name = "합성고객-stale-" + key.substring(0, 8);
        insertKeyRow(actor, key, hashOf(CLIENTS, body(name)), null, clock.instant().minusSeconds(31));
        mvc.perform(as(postJson(CLIENTS, body(name)), actor, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated());
        assertThat(clientsNamed(name)).isEqualTo(1);
    }

    @Test
    @DisplayName("설계 9.4: field-app 이 키 없이 POST → 428 CORE-428-001, 웹(erp-web)은 일반 처리")
    void fieldAppRequiresKey() throws Exception {
        mvc.perform(as(postJson(CLIENTS, body("합성고객-fa-" + UUID.randomUUID())), actor(), "SALES")
                        .header("X-Client-Id", "field-app"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("CORE-428-001"));
        mvc.perform(as(postJson(CLIENTS, body("합성고객-web-" + UUID.randomUUID())), actor(), "SALES")
                        .header("X-Client-Id", "erp-web"))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("설계 9.4: 5xx 응답은 저장하지 않음 → 같은 키 재시도 시 재처리")
    void fiveXxIsNotStored() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        int before = boom.calls.get();
        for (int i = 0; i < 2; i++) {
            mvc.perform(as(postJson("/api/v1/test/boom", "{}"), actor, "SALES").header("Idempotency-Key", key))
                    .andExpect(status().isInternalServerError())
                    .andExpect(header().doesNotExist("Idempotent-Replayed"))
                    .andExpect(jsonPath("$.code").value("CORE-500-001"));
        }
        assertThat(boom.calls.get() - before).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.idempotency_key WHERE actor_id = ? AND key = ?",
                Integer.class, actor, key)).isZero();
    }

    @Test
    @DisplayName("4xx(검증 실패) 응답은 저장 → 재시도 시 같은 422 재반환")
    void fourXxIsStoredAndReplayed() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            var r = mvc.perform(as(postJson(CLIENTS, "{\"clientType\":\"PRIVATE\"}"), actor, "SALES")
                            .header("Idempotency-Key", key))
                    .andExpect(status().is(422))
                    .andExpect(jsonPath("$.code").value("CORE-422-001"));
            if (i == 1) {
                r.andExpect(header().string("Idempotent-Replayed", "true"));
            }
        }
    }

    @Test
    @DisplayName("설계 9.4: 24시간 보관 — 경과 키는 신규 요청으로 처리되고 정리 배치가 삭제")
    void retention24h() throws Exception {
        UUID actor = actor();
        String key = UUID.randomUUID().toString();
        // 25시간 전 완료된(다른 본문) 키
        insertKeyRow(actor, key, "f".repeat(64), 201, clock.instant().minus(Duration.ofHours(25)));
        String name = "합성고객-ttl-" + key.substring(0, 8);
        mvc.perform(as(postJson(CLIENTS, body(name)), actor, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist("Idempotent-Replayed"));
        assertThat(clientsNamed(name)).isEqualTo(1);

        UUID other = actor();
        insertKeyRow(other, "old-" + key, "e".repeat(64), 201, clock.instant().minus(Duration.ofHours(30)));
        assertThat(purgeJob.purge()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.idempotency_key WHERE actor_id = ?",
                Integer.class, other)).isZero();
        // 방금 만든 키(24시간 이내)는 남는다
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.idempotency_key WHERE actor_id = ?",
                Integer.class, actor)).isEqualTo(1);
    }

    @Test
    @DisplayName("인증 없이 Idempotency-Key 사용 → 401 CORE-401-001 (actor 네임스페이스 불가)")
    void keyWithoutActorIs401() throws Exception {
        mvc.perform(postJson(CLIENTS, body("합성고객-anon")).header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CORE-401-001"));
    }

    private static String hashOf(String path, String json) {
        return Hashing.sha256Hex("POST\n" + path + "\n"
                + CanonicalJson.canonicalize(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void insertKeyRow(UUID actor, String key, String hash, Integer code, java.time.Instant createdAt) {
        jdbc.update("""
                INSERT INTO core.idempotency_key(key, actor_id, request_hash, response_code, response_body, created_at)
                VALUES (?, ?, ?, ?::int, CASE WHEN ?::int IS NULL THEN NULL ELSE '{}'::jsonb END, ?)""",
                key, actor, hash, code, code, Timestamp.from(createdAt));
    }
}
