package com.atomeng.erp.cst;

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

/** 08-IMPL Step 1 CstMigrationTest — 내장 PostgreSQL 16 (Docker 없음), 합성 데이터만 */
class CstMigrationTest {

    static String url;
    static UUID project;
    static UUID emp;
    static UUID building;
    static final UUID ACTOR = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("cst_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fac", "cst").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fac", "classpath:db/migration/cst")
                .load().migrate().success).isTrue();
        project = UUID.randomUUID();
        emp = UUID.randomUUID();
        building = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) VALUES "
                    + "('%s', 'E-CST-1', '합성기술자', DATE '2020-01-01', '%s', '%s')").formatted(emp, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-CST', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-8001', '합성 원가', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, ACTOR, ACTOR));
            st.execute("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES ('%s', '합성 단지', '%s', '%s')"
                    .formatted(site, ACTOR, ACTOR));
            st.execute(("INSERT INTO fac.building(id, site_id, name, main_purpose_nm, created_by, updated_by) VALUES "
                    + "('%s', '%s', '합성 동', '업무시설', '%s', '%s')").formatted(building, site, ACTOR, ACTOR));
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

    static String ledgerInsert(UUID id, String ym, String sourceType, UUID sourceId, UUID reversalOf, long amount) {
        return ("INSERT INTO cst.cost_ledger_entry(id, project_id, posting_date, period_ym, source_type, source_id, "
                + "cost_element, employee_id, hours, qty, amount_krw, amount_std_krw, orig_amount, reversal_of, created_by) "
                + "VALUES ('%s', '%s', DATE '%s-%s-15', '%s', '%s', '%s', 'LABOR', '%s', 8, 1, %d, %d, %d, %s, '%s')")
                .formatted(id, project, ym.substring(0, 4), ym.substring(4), ym, sourceType, sourceId, emp, amount, amount,
                        amount, reversalOf == null ? "NULL" : "'" + reversalOf + "'", ACTOR);
    }

    @Test
    @DisplayName("Step1: cst 테이블·월 파티션·회계기간 생성, period_ym char(6), fac.building 참조")
    void schemaCreated() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat((Long) scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'cst' "
                    + "AND table_type = 'BASE TABLE' AND table_name NOT LIKE 'cost_ledger_entry_%'")).isEqualTo(18L);   // V0052 청구 · 입금 · 배분 + V0054 제안 + V0056 독촉 2개 = 6개 추가
            assertThat((Long) scalar(st, "SELECT count(*) FROM pg_inherits i JOIN pg_class p ON p.oid = i.inhparent "
                    + "WHERE p.relname = 'cost_ledger_entry'")).isGreaterThanOrEqualTo(61L);
            assertThat((Long) scalar(st, "SELECT count(*) FROM cst.accounting_period")).isGreaterThanOrEqualTo(61L);
            for (String t : new String[] {"cost_ledger_entry", "accounting_period", "employee_cost_rate"}) {
                assertThat(scalar(st, "SELECT data_type FROM information_schema.columns WHERE table_schema = 'cst' "
                        + "AND table_name = '" + t + "' AND column_name = 'period_ym'")).isEqualTo("character");
            }
            assertThat((Long) scalar(st, "SELECT count(*) FROM information_schema.columns WHERE table_schema = 'cst' "
                    + "AND data_type IN ('real', 'double precision')")).as("부동소수 금지(NFR-CST-02)").isZero();
            assertThat(scalar(st, """
                    SELECT ccu.table_schema || '.' || ccu.table_name FROM information_schema.table_constraints tc
                      JOIN information_schema.constraint_column_usage ccu ON ccu.constraint_name = tc.constraint_name
                     WHERE tc.table_schema = 'cst' AND tc.table_name = 'project_facility'
                       AND tc.constraint_type = 'FOREIGN KEY' AND ccu.column_name = 'id' AND ccu.table_schema = 'fac'"""))
                    .isEqualTo("fac.building");
            st.execute("INSERT INTO cst.project_facility(project_id, building_id, created_by) VALUES ('%s', '%s', '%s')"
                    .formatted(project, building, ACTOR));
            assertThatThrownBy(() -> st.execute("INSERT INTO cst.accounting_period(period_ym) VALUES ('202613')"))
                    .hasMessageContaining("check");
        }
    }

    @Test
    @DisplayName("TC-CST-04: 원장 UPDATE·DELETE → CST-409-001(restrict_violation), 원 행 보존")
    void tcCst04_ledgerAppendOnly() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID id = UUID.randomUUID();
            st.execute(ledgerInsert(id, "202605", "TIMESHEET", UUID.randomUUID(), null, 50_000));
            assertThatThrownBy(() -> st.executeUpdate("UPDATE cst.cost_ledger_entry SET amount_krw = 0 WHERE id = '" + id + "'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23001");
                        assertThat(e.getMessage()).contains("CST-409-001");
                    });
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM cst.cost_ledger_entry WHERE id = '" + id + "'"))
                    .hasMessageContaining("CST-409-001");
            assertThat(scalar(st, "SELECT amount_krw::bigint FROM cst.cost_ledger_entry WHERE id = '" + id + "'"))
                    .isEqualTo(50_000L);
        }
    }

    @Test
    @DisplayName("멱등 UNIQUE NULLS NOT DISTINCT: 같은 원천·월 재기록 차단(reversal_of NULL 포함), 역분개는 원 행당 월 1건")
    void idempotencyUniqueNullsNotDistinct() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID src = UUID.randomUUID();
            UUID orig = UUID.randomUUID();
            st.execute(ledgerInsert(orig, "202606", "TIMESHEET", src, null, 100_000));
            assertThatThrownBy(() -> st.execute(ledgerInsert(UUID.randomUUID(), "202606", "TIMESHEET", src, null, 100_000)))
                    .hasMessageContaining("(source_type, source_id, period_ym, reversal_of)");
            st.execute(ledgerInsert(UUID.randomUUID(), "202606", "REVERSAL", src, orig, -100_000));
            assertThatThrownBy(() -> st.execute(ledgerInsert(UUID.randomUUID(), "202606", "REVERSAL", src, orig, -100_000)))
                    .hasMessageContaining("(source_type, source_id, period_ym, reversal_of)");
            // REVERSAL ⇔ reversal_of 필수
            assertThatThrownBy(() -> st.execute(ledgerInsert(UUID.randomUUID(), "202606", "REVERSAL", UUID.randomUUID(),
                    null, -1))).hasMessageContaining("ck_ledger_reversal");
            assertThat(scalar(st, "SELECT sum(amount_krw)::bigint FROM cst.cost_ledger_entry WHERE source_id = '" + src + "'"))
                    .isEqualTo(0L);
        }
    }

    @Test
    @DisplayName("NFR-CST-05: 마감(CLOSED) 월 원장 INSERT → CST-409-002, 다른 월은 허용")
    void closedPeriodInsertForbidden() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("SELECT cst.ensure_ledger_partition('202402')");
            st.execute("UPDATE cst.accounting_period SET status = 'CLOSED' WHERE period_ym = '202402'");
            assertThatThrownBy(() -> st.execute(ledgerInsert(UUID.randomUUID(), "202402", "TIMESHEET", UUID.randomUUID(),
                    null, 1_000))).hasMessageContaining("CST-409-002");
            st.execute(ledgerInsert(UUID.randomUUID(), "202403", "TIMESHEET", UUID.randomUUID(), null, 1_000));
            // 범위 밖 월은 함수가 파티션을 만든다
            st.execute("SELECT cst.ensure_ledger_partition('203012')");
            assertThat(scalar(st, "SELECT count(*) FROM pg_class WHERE relname = 'cost_ledger_entry_203012'")).isEqualTo(1L);
        }
    }

    @Test
    @DisplayName("BR-CST-005: 같은 요율(종류·키) 유효기간 겹침 → EXCLUDE 위반, 연속 구간 허용")
    void rateNoOverlap() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            String ins = "INSERT INTO cst.rate_table(id, rate_kind, key_code, value, valid_from, valid_to, source_ref, "
                    + "created_by) VALUES (gen_random_uuid(), 'STD_LABOR', 'SENIOR', 300000, DATE '%s', %s, '합성 근거', '"
                    + ACTOR + "')";
            st.execute(ins.formatted("2026-01-01", "DATE '2026-07-01'"));
            st.execute(ins.formatted("2026-07-01", "NULL"));
            assertThatThrownBy(() -> st.execute(ins.formatted("2026-06-01", "NULL")))
                    .hasMessageContaining("rate_table_no_overlap");
        }
    }

    @Test
    @DisplayName("BR-CST-001: 프로젝트당 APPROVED 실행예산 1개, 금액 생성 컬럼(qty×단가 반올림)·예상 이익률, MV 갱신")
    void budgetAndProfitabilityView() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID rev = UUID.randomUUID();
            UUID wbs = UUID.randomUUID();
            st.execute(("INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, sort_order) VALUES "
                    + "('%s', '%s', '10-FIELD', '현장조사', 30, 1)").formatted(wbs, project));
            st.execute(("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, "
                    + "created_by) VALUES ('%s', '%s', 0, 'APPROVED', 85000000, 100000000, '%s')").formatted(rev, project, ACTOR));
            assertThat(scalar(st, "SELECT expected_margin_pct::text FROM cst.budget_revision WHERE id = '" + rev + "'"))
                    .isEqualTo("15.00");
            st.execute(("INSERT INTO cst.budget_line(id, revision_id, wbs_id, cost_element, grade_code, qty, unit_cost_krw) "
                    + "VALUES (gen_random_uuid(), '%s', '%s', 'LABOR', 'SENIOR', 2.5, 333333)").formatted(rev, wbs));
            assertThat(scalar(st, "SELECT amount_krw::bigint FROM cst.budget_line WHERE revision_id = '" + rev + "'"))
                    .isEqualTo(833_333L);   // 833332.5 → HALF_UP
            assertThatThrownBy(() -> st.execute(("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, "
                    + "contract_amount_krw, created_by) VALUES (gen_random_uuid(), '%s', 1, 'APPROVED', 1, '%s')")
                    .formatted(project, ACTOR))).hasMessageContaining("ux_budget_one_approved");
            st.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY cst.mv_project_profitability");
            assertThat(scalar(st, "SELECT bac_krw::bigint FROM cst.mv_project_profitability WHERE project_id = '"
                    + project + "'")).isEqualTo(85_000_000L);
        }
    }
}
