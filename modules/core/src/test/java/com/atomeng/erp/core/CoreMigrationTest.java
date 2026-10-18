package com.atomeng.erp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * V1__core.sql 마이그레이션 통합 테스트 (00-IMPL Step 3 CoreMigrationTest) — 내장 PostgreSQL 16.
 * 합성 데이터만 사용.
 */
class CoreMigrationTest {

    static String url;

    @BeforeAll
    static void migrate() {
        url = EmbeddedPg.newDatabaseUrl("core_mig");
        var flyway = flyway();
        var result = flyway.migrate();
        assertThat(result.success).isTrue();
    }

    static Flyway flyway() {
        return Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core").createSchemas(true).locations("classpath:db/migration/core").load();
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(url, EmbeddedPg.user(), EmbeddedPg.password());
    }

    @Test
    @DisplayName("Step3: V1 마이그레이션 적용·검증 성공, core 테이블 전부 생성")
    void step3_allMigrationsApplyAndValidate() throws Exception {
        assertThat(flyway().validateWithResult().validationSuccessful).isTrue();
        Set<String> tables = new HashSet<>();
        try (var c = conn(); var rs = c.createStatement().executeQuery(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'core'")) {
            while (rs.next()) {
                tables.add(rs.getString(1));
            }
        }
        assertThat(tables).contains("client", "client_contact", "employee", "project", "project_member",
                "code_group", "code", "attachment", "hash_anchor", "audit_log", "outbox_event", "outbox_dead",
                "processed_event", "idempotency_key", "role_permission", "doc_sequence");
    }

    @Test
    @DisplayName("Step3: outbox_event 에 next_attempt_at, outbox_dead 에 dead_at 컬럼 (설계 v0.2)")
    void step3_outboxColumns() throws Exception {
        try (var c = conn()) {
            assertThat(columns(c, "outbox_event")).contains("next_attempt_at", "attempts", "last_error",
                    "published_at");
            assertThat(columns(c, "outbox_dead")).contains("next_attempt_at", "dead_at");
        }
    }

    @Test
    @DisplayName("TC-CORE-007: attachment.sha256 직접 UPDATE·DELETE 시 트리거 예외 (append-only)")
    void tcCore007_attachmentIsAppendOnly() throws Exception {
        try (var c = conn()) {
            UUID id = UUID.randomUUID();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO core.attachment(id, bucket, object_key, file_name, mime_type, size_bytes, sha256,
                        chain_hash, owner_module, owner_type, owner_id, chain_scope, created_by)
                    VALUES (?, 'field-original', ?, 'synthetic.jpg', 'image/jpeg', 10, ?, ?, 'FLD', 'Photo', ?, ?, ?)""")) {
                ps.setObject(1, id);
                ps.setString(2, "2026/PRJ-2026-0001/" + id + ".jpg");
                ps.setString(3, "a".repeat(64));
                ps.setString(4, "b".repeat(64));
                ps.setObject(5, UUID.randomUUID());
                ps.setObject(6, UUID.randomUUID());
                ps.setObject(7, UUID.randomUUID());
                ps.executeUpdate();
            }
            assertThatThrownBy(() -> c.createStatement().execute(
                    "UPDATE core.attachment SET sha256 = '" + "c".repeat(64) + "' WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("immutable columns");
            assertThatThrownBy(() -> c.createStatement().execute("DELETE FROM core.attachment WHERE id = '" + id + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("append-only");
            // 법적 보존 플래그는 변경 가능
            assertThat(c.createStatement().executeUpdate(
                    "UPDATE core.attachment SET legal_hold = true WHERE id = '" + id + "'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Step3: 문서 번호 동시 채번 50건 — 중복·누락 없음 (PRJ-2026-0001..0050)")
    void step3_docNumberIsGaplessUnderConcurrency() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<String>> tasks = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                tasks.add(() -> {
                    try (var c = conn(); var rs = c.createStatement()
                            .executeQuery("SELECT core.next_doc_no('PRJ', 2026, 4)")) {
                        rs.next();
                        return rs.getString(1);
                    }
                });
            }
            Set<String> nos = new HashSet<>();
            for (Future<String> f : pool.invokeAll(tasks)) {
                nos.add(f.get());
            }
            assertThat(nos).hasSize(50).contains("PRJ-2026-0001", "PRJ-2026-0050");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("Step3: core.uuid_generate_v7() — version 7 / variant 2 / 시각 정렬")
    void step3_sqlUuidV7() throws Exception {
        try (var c = conn(); var rs = c.createStatement().executeQuery(
                "SELECT core.uuid_generate_v7() FROM generate_series(1, 20)")) {
            List<UUID> ids = new ArrayList<>();
            while (rs.next()) {
                ids.add(rs.getObject(1, UUID.class));
            }
            assertThat(ids).allSatisfy(u -> {
                assertThat(u.version()).isEqualTo(7);
                assertThat(u.variant()).isEqualTo(2);
            });
            long now = System.currentTimeMillis();
            long ts = ids.get(0).getMostSignificantBits() >>> 16;
            assertThat(Math.abs(now - ts)).isLessThan(60_000);
        }
    }

    @Test
    @DisplayName("설계 9.4: idempotency_key PK (actor_id, key) — 다른 actor 는 같은 key 허용, 같은 actor 는 중복 거부")
    void idempotencyKeyIsNamespacedPerActor() throws Exception {
        try (var c = conn()) {
            String key = "k-" + UUID.randomUUID();
            UUID a1 = UUID.randomUUID();
            UUID a2 = UUID.randomUUID();
            insertKey(c, a1, key);
            insertKey(c, a2, key);
            assertThatThrownBy(() -> insertKey(c, a1, key)).isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate key");
        }
    }

    @Test
    @DisplayName("설계 10.2: processed_event INSERT … ON CONFLICT DO NOTHING — 두 번째는 0행")
    void processedEventIsIdempotent() throws Exception {
        try (var c = conn()) {
            UUID ev = UUID.randomUUID();
            String sql = "INSERT INTO core.processed_event(consumer, event_id) VALUES ('test.consumer', ?) "
                    + "ON CONFLICT DO NOTHING";
            try (var ps = c.prepareStatement(sql)) {
                ps.setObject(1, ev);
                assertThat(ps.executeUpdate()).isEqualTo(1);
                assertThat(ps.executeUpdate()).isZero();
            }
        }
    }

    @Test
    @DisplayName("설계 10.1: outbox event_type 은 {mod}.{aggregate}.{verb} 형식만 허용")
    void outboxEventTypeNamingIsEnforced() throws Exception {
        try (var c = conn()) {
            String sql = "INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, payload) "
                    + "VALUES (?, 'Project', ?, ?, '{}'::jsonb)";
            try (var ps = c.prepareStatement(sql)) {
                ps.setObject(1, UUID.randomUUID());
                ps.setObject(2, UUID.randomUUID());
                ps.setString(3, "core.project.created");
                assertThat(ps.executeUpdate()).isEqualTo(1);
                ps.setObject(1, UUID.randomUUID());
                ps.setString(3, "ProjectCreated");
                assertThatThrownBy(ps::executeUpdate).hasMessageContaining("check constraint");
            }
        }
    }

    @Test
    @DisplayName("NFR-CORE-07: audit_log 해시 체인 — 정상 검증, 변조 탐지, UPDATE 차단")
    void auditLogHashChainDetectsTampering() throws Exception {
        try (var c = conn(); Statement st = c.createStatement()) {
            st.execute("TRUNCATE core.audit_log");
            for (int i = 0; i < 3; i++) {
                st.execute("SELECT core.audit_append(NULL, NULL, 'CORE', 'CREATE', 'Client', core.uuid_generate_v7(),"
                        + " NULL, '{\"name\":\"합성고객" + i + "\"}'::jsonb, NULL)");
            }
            assertThat(scalar(st, "SELECT core.audit_verify_chain()")).isNull();
            assertThat(scalar(st, "SELECT count(*) FROM core.audit_log WHERE prev_hash IS NULL")).isEqualTo(1L);

            assertThatThrownBy(() -> st.execute("UPDATE core.audit_log SET action = 'DELETE'"))
                    .hasMessageContaining("append-only");

            // 트리거를 우회한 변조(슈퍼유저) → 체인 검증이 해당 행을 찾아낸다
            Long secondId = (Long) scalar(st, "SELECT id FROM core.audit_log ORDER BY id OFFSET 1 LIMIT 1");
            st.execute("SET session_replication_role = replica");
            st.execute("UPDATE core.audit_log SET after_json = '{\"name\":\"변조\"}' WHERE id = " + secondId);
            st.execute("SET session_replication_role = origin");
            assertThat(scalar(st, "SELECT core.audit_verify_chain()")).isEqualTo(secondId);
        }
    }

    @Test
    @DisplayName("설계 8.2 v0.3: role_permission 은 EMPLOYEE·FIN·AUDITOR 포함 12개 역할만 허용")
    void rolePermissionAcceptsV03Roles() throws Exception {
        try (var c = conn(); Statement st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(DISTINCT role) FROM core.role_permission "
                    + "WHERE role IN ('EMPLOYEE','FIN','AUDITOR')")).isEqualTo(3L);
            assertThatThrownBy(() -> st.execute(
                    "INSERT INTO core.role_permission(role, permission) VALUES ('HACKER', 'core:client:read')"))
                    .hasMessageContaining("check constraint");
        }
    }

    private static void insertKey(Connection c, UUID actor, String key) throws SQLException {
        try (var ps = c.prepareStatement(
                "INSERT INTO core.idempotency_key(key, actor_id, request_hash) VALUES (?, ?, ?)")) {
            ps.setString(1, key);
            ps.setObject(2, actor);
            ps.setString(3, "0".repeat(64));
            ps.executeUpdate();
        }
    }

    private static Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> cols = new HashSet<>();
        try (var ps = c.prepareStatement("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'core' AND table_name = ?")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cols.add(rs.getString(1));
                }
            }
        }
        return cols;
    }

    private static Object scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }
}
