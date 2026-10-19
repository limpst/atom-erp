package com.atomeng.erp.ptl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 09-IMPL Step 1 교차 고객 하네스(DB 수준) — 내장 PostgreSQL 16, 합성 데이터만.
 * ptl 스키마의 모든 테이블을 카탈로그에서 자동 열거해 고객 A 컨텍스트(SET LOCAL ROLE ptl_customer + app.customer_id)로
 * 고객 B 행 SELECT · UPDATE · INSERT 를 시도한다(TC-PTL-01 DB 레벨), 컨텍스트 없으면 0행(TC-PTL-02),
 * 새 테이블이 RLS(ENABLE + FORCE) 없이 추가되면 실패, 다운로드 · 접근 로그 append-only.
 */
class PtlMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static final UUID A = UUID.randomUUID();
    static final UUID B = UUID.randomUUID();
    static UUID reportA;
    static UUID reportB;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("ptl_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "ptl").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/ptl")
                .load().migrate().success).isTrue();
        try (Connection c = conn(); Statement st = c.createStatement()) {
            for (UUID cl : List.of(A, B)) {
                st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                        + "('%s', 'CL-PTL-%s', '합성고객 %s', 'PRIVATE', '%s', '%s')")
                        .formatted(cl, cl.toString().substring(0, 8), cl == A ? "A" : "B", ACTOR, ACTOR));
            }
            reportA = seedAll(st, A);
            reportB = seedAll(st, B);
        }
    }

    /** 고객마다 모든 ptl 테이블에 1행씩(소유자 = 슈퍼유저 세션이라 RLS 우회) */
    static UUID seedAll(Statement st, UUID cid) throws SQLException {
        UUID report = UUID.randomUUID();
        st.execute(("INSERT INTO ptl.published_report(id, customer_id, report_id, revision_id, rev_no, project_id, facility_id, "
                + "report_type, title, inspection_date, pdf_sha256, delivered_at) VALUES ('%s', '%s', '%s', '%s', 1, '%s', "
                + "'%s', 'RT_REG', '합성 보고서', DATE '2026-10-01', '%s', now())").formatted(report, cid, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "a".repeat(64)));
        st.execute(("INSERT INTO ptl.report_download(id, customer_id, published_report_id, actor_id, downloaded_at, "
                + "watermark_text, source_sha256, output_sha256) VALUES ('%s', '%s', '%s', '%s', now(), 'wm', '%s', '%s')")
                .formatted(UUID.randomUUID(), cid, report, ACTOR, "a".repeat(64), "b".repeat(64)));
        st.execute(("INSERT INTO ptl.service_request(id, customer_id, kind, service_type, description, submitted_by) "
                + "VALUES ('%s', '%s', 'QUOTE', 'INQUIRY', '합성 요청', '%s')").formatted(UUID.randomUUID(), cid, ACTOR));
        st.execute(("INSERT INTO ptl.notification(id, customer_id, kind, title, body, dedupe_key) VALUES ('%s', '%s', "
                + "'GRADE_RECORDED', 't', 'b', '%s')").formatted(UUID.randomUUID(), cid, UUID.randomUUID()));
        st.execute(("INSERT INTO ptl.sales_document(id, customer_id, doc_kind, sal_ref_id, status) VALUES ('%s', '%s', "
                + "'ESTIMATE', '%s', 'ISSUED')").formatted(UUID.randomUUID(), cid, UUID.randomUUID()));
        st.execute(("INSERT INTO ptl.access_log(id, customer_id, actor_id, action, target_type) VALUES ('%s', '%s', '%s', "
                + "'VIEW', 'PublishedReport')").formatted(UUID.randomUUID(), cid, ACTOR));
        return report;
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(url, EmbeddedPg.user(), EmbeddedPg.password());
    }

    static long scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    /** 앱 PortalRls 와 같은 진입: 트랜잭션 + set_config(…, true) + SET LOCAL ROLE */
    static Connection asCustomer(UUID cid) throws SQLException {
        Connection c = conn();
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            if (cid != null) {
                st.execute("SELECT set_config('app.customer_id', '" + cid + "', true)");
            }
            st.execute("SET LOCAL ROLE ptl_customer");
        }
        return c;
    }

    static List<String> ptlTables() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = conn(); Statement st = c.createStatement(); ResultSet rs = st.executeQuery("""
                SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                 WHERE n.nspname = 'ptl' AND c.relkind IN ('r','p') ORDER BY 1""")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    @DisplayName("NFR-PTL-01: ptl 의 모든 테이블이 customer_id + RLS(ENABLE · FORCE) + 정책 — 새 테이블 누락 방지")
    void everyTableHasForcedRls() throws Exception {
        List<String> tables = ptlTables();
        assertThat(tables).hasSize(6).contains("published_report", "report_download", "service_request", "notification",
                "sales_document", "access_log");
        try (Connection c = conn(); Statement st = c.createStatement()) {
            for (String t : tables) {
                assertThat(scalar(st, "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE n.nspname = 'ptl' AND c.relname = '" + t + "' AND c.relrowsecurity AND c.relforcerowsecurity"))
                        .as("RLS forced on %s", t).isEqualTo(1);
                assertThat(scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'ptl' "
                        + "AND table_name = '" + t + "' AND column_name = 'customer_id' AND is_nullable = 'NO'"))
                        .as("customer_id NOT NULL on %s", t).isEqualTo(1);
                assertThat(scalar(st, "SELECT count(*) FROM pg_policies WHERE schemaname = 'ptl' AND tablename = '" + t
                        + "' AND policyname = 'customer_isolation'")).isEqualTo(1);
            }
            assertThat(scalar(st, "SELECT count(*) FROM pg_roles WHERE rolname = 'ptl_customer' AND NOT rolbypassrls "
                    + "AND NOT rolsuper AND NOT rolcanlogin")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TC-PTL-01(DB): 고객 A 컨텍스트에서 모든 테이블의 B 행 0건 · 자기 행만 보임 · B 행 UPDATE 0건")
    void customerACannotReadOrUpdateB() throws Exception {
        try (Connection c = asCustomer(A); Statement st = c.createStatement()) {
            for (String t : ptlTables()) {
                assertThat(scalar(st, "SELECT count(*) FROM ptl." + t + " WHERE customer_id = '" + B + "'"))
                        .as("leak in %s", t).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM ptl." + t + " WHERE customer_id <> '" + A + "'"))
                        .as("foreign rows in %s", t).isZero();
                assertThat(scalar(st, "SELECT count(*) FROM ptl." + t)).as("own rows in %s", t).isGreaterThanOrEqualTo(1);
            }
            for (String t : List.of("published_report", "service_request", "notification")) {
                assertThat(st.executeUpdate("UPDATE ptl." + t + " SET customer_id = customer_id WHERE customer_id = '"
                        + B + "'")).as("update in %s", t).isZero();
            }
            assertThat(st.executeUpdate("UPDATE ptl.published_report SET status = 'REVOKED' WHERE id = '" + reportB + "'"))
                    .isZero();
            c.rollback();
        }
        try (Connection c = conn(); Statement st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM ptl.published_report WHERE id = '" + reportB
                    + "' AND status = 'PUBLISHED'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TC-PTL-01(DB): 고객 A 가 customer_id = B 로 INSERT · A 행을 B 로 옮기는 UPDATE → RLS WITH CHECK 위반")
    void customerACannotWriteIntoB() throws Exception {
        try (Connection c = asCustomer(A); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.execute(("INSERT INTO ptl.service_request(id, customer_id, kind, service_type, "
                    + "description, submitted_by) VALUES ('%s', '%s', 'QUOTE', 'INQUIRY', '위장 요청', '%s')")
                    .formatted(UUID.randomUUID(), B, ACTOR)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            c.rollback();
        }
        try (Connection c = asCustomer(A); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE ptl.notification SET customer_id = '" + B + "'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
            c.rollback();
        }
    }

    @Test
    @DisplayName("TC-PTL-02: app.customer_id 미설정(또는 빈 값) → 모든 ptl 테이블 0행, INSERT 거부 (fail-closed)")
    void noContextReturnsZeroRows() throws Exception {
        try (Connection c = asCustomer(null); Statement st = c.createStatement()) {
            for (String t : ptlTables()) {
                assertThat(scalar(st, "SELECT count(*) FROM ptl." + t)).as("no-context rows in %s", t).isZero();
            }
            assertThatThrownBy(() -> st.execute(("INSERT INTO ptl.access_log(id, customer_id, actor_id, action, target_type) "
                    + "VALUES ('%s', '%s', '%s', 'VIEW', 'X')").formatted(UUID.randomUUID(), A, ACTOR)))
                    .isInstanceOf(SQLException.class);
            c.rollback();
        }
        try (Connection c = conn()) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute("SELECT set_config('app.customer_id', '', true)");
                st.execute("SET LOCAL ROLE ptl_customer");
                assertThat(scalar(st, "SELECT count(*) FROM ptl.published_report")).isZero();
            }
            c.rollback();
        }
    }

    @Test
    @DisplayName("ptl_customer 는 내부 스키마(core) 읽기 · ptl DELETE 권한 없음")
    void customerRoleHasNoInternalAccess() throws Exception {
        try (Connection c = asCustomer(A); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeQuery("SELECT count(*) FROM core.client"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            c.rollback();
        }
        try (Connection c = asCustomer(A); Statement st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM ptl.notification"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            c.rollback();
        }
    }

    @Test
    @DisplayName("NFR-PTL-09: 다운로드 감사 · 접근 로그 UPDATE · DELETE · TRUNCATE → PTL_APPEND_ONLY(소유자도)")
    void auditTablesAreAppendOnly() throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            for (String sql : List.of("UPDATE ptl.report_download SET watermark_text = 'x'",
                    "DELETE FROM ptl.report_download", "TRUNCATE ptl.report_download",
                    "UPDATE ptl.access_log SET detail = 'x'", "DELETE FROM ptl.access_log", "TRUNCATE ptl.access_log")) {
                assertThatThrownBy(() -> st.execute(sql)).as(sql).isInstanceOf(SQLException.class)
                        .hasMessageContaining("PTL_APPEND_ONLY");
            }
        }
    }
}
