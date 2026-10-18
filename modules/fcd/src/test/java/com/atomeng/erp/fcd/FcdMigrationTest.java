package com.atomeng.erp.fcd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 12-IMPL Step 1 FcdMigrationTest — 내장 PostgreSQL 16(Docker 없음), 합성 데이터만 */
class FcdMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static UUID card;
    static UUID txn;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("fcd_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fcd").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fcd")
                .load().migrate().success).isTrue();
        card = UUID.randomUUID();
        txn = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO fcd.card(id, card_company, card_token, token_key_version, first6, last4, card_type, "
                    + "limit_per_txn, limit_daily, limit_monthly, created_by, updated_by) VALUES ('%s', 'ACARD', '%s', 1, "
                    + "'999999', '0001', 'SHARED', 300000, 450000, 3000000, '%s', '%s')")
                    .formatted(card, "a".repeat(64), ACTOR, ACTOR));
            st.execute(("INSERT INTO fcd.txn(id, card_id, approved_at, approved_at_precision, amount_krw, merchant_name, status) "
                    + "VALUES ('%s', '%s', TIMESTAMP '2026-09-01 12:00', 'MINUTE', 12000, '(예시)A', 'APPROVED')")
                    .formatted(txn, card));
        }
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(url, EmbeddedPg.user(), EmbeddedPg.password());
    }

    static Object scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getObject(1);
        }
    }

    @Test
    @DisplayName("TC-FCD-001: PAN 저장 컬럼 없음(토큰 · 앞6 · 뒤4 · 마스킹만), 마스킹 번호 생성 컬럼, 기본 프로파일 4종 · 세부 권한 시드")
    void noPanColumnsAndSeeds() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'fcd' "
                    + "AND column_name ~ '^(pan|pan_.*|card_no|card_number|.*_pan)$'")).isEqualTo(0L);
            assertThat(scalar(st, "SELECT masked_no FROM fcd.card WHERE id = '" + card + "'")).isEqualTo("999999******0001");
            assertThat(scalar(st, "SELECT count(*) FROM fcd.import_profile")).isEqualTo(4L);
            assertThat(scalar(st, "SELECT count(*) FROM core.role_permission WHERE permission LIKE 'fcd:%'"))
                    .isEqualTo(54L);
        }
    }

    @Test
    @DisplayName("TC-FCD-024(DB): rule_set 4-eyes CHECK(작성자 = 승인자 거부) · 승인자 없는 ACTIVE 거부")
    void ruleSetFourEyes() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute("INSERT INTO fcd.rule_set(id, name, version, effective_from, status, authored_by, "
                    + "approved_by, yaml, sha256) VALUES (gen_random_uuid(), 'fcd', '9.9.9', DATE '2026-01-01', 'ACTIVE', "
                    + "'U1', 'U1', 'x', '" + "0".repeat(64) + "')")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("check");
            assertThatThrownBy(() -> st.execute("INSERT INTO fcd.rule_set(id, name, version, effective_from, status, authored_by, "
                    + "yaml, sha256) VALUES (gen_random_uuid(), 'fcd', '9.9.8', DATE '2026-01-01', 'ACTIVE', 'U1', 'x', '"
                    + "0".repeat(64) + "')")).isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("risk_score · case_event append-only(FCD-409-001), 공용카드 대여 기간 중복 금지, 동일 파일 SHA 활성 배치 1개(BR-FCD-004)")
    void appendOnlyOverlapAndSha() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO fcd.risk_score(id, txn_id, trigger, rule_set_version, score, grade, r, r_mitigated, hits, "
                    + "mitigations, flags, contributions, reasons, features, l3_status) VALUES (gen_random_uuid(), '%s', 'IMPORT', "
                    + "'2026.5.0', 10, 'NORMAL', 0.1, 0.1, '[]', '[]', '[]', '[]', '[]', '{}', 'DISABLED')").formatted(txn));
            assertThatThrownBy(() -> st.execute("UPDATE fcd.risk_score SET score = 99"))
                    .hasMessageContaining("FCD-409-001");
            assertThatThrownBy(() -> st.execute("DELETE FROM fcd.risk_score")).hasMessageContaining("FCD-409-001");
            UUID e = UUID.randomUUID();
            st.execute(("INSERT INTO fcd.card_assignment(id, card_id, employee_id, period_start, period_end, purpose, created_by) "
                    + "VALUES (gen_random_uuid(), '%s', '%s', TIMESTAMP '2026-09-01 09:00', TIMESTAMP '2026-09-01 18:00', '현장', '%s')")
                    .formatted(card, e, ACTOR));
            assertThatThrownBy(() -> st.execute(("INSERT INTO fcd.card_assignment(id, card_id, employee_id, period_start, "
                    + "period_end, purpose, created_by) VALUES (gen_random_uuid(), '%s', '%s', TIMESTAMP '2026-09-01 17:00', "
                    + "TIMESTAMP '2026-09-01 20:00', '현장', '%s')").formatted(card, UUID.randomUUID(), ACTOR)))
                    .hasMessageContaining("FCD-409-004");
            String sha = "b".repeat(64);
            st.execute("INSERT INTO fcd.import_batch(id, channel, file_sha256, status, uploaded_by) VALUES (gen_random_uuid(), "
                    + "'CSV', '" + sha + "', 'VALIDATED', '" + ACTOR + "')");
            assertThatThrownBy(() -> st.execute("INSERT INTO fcd.import_batch(id, channel, file_sha256, status, uploaded_by) "
                    + "VALUES (gen_random_uuid(), 'CSV', '" + sha + "', 'VALIDATED', '" + ACTOR + "')"))
                    .hasMessageContaining("ux_batch_sha");
            st.execute("INSERT INTO fcd.import_batch(id, channel, file_sha256, status, uploaded_by) VALUES (gen_random_uuid(), "
                    + "'CSV', '" + sha + "', 'DISCARDED', '" + ACTOR + "')");
        }
    }
}
