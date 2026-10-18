package com.atomeng.erp.sal;

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

/** 01-IMPL Step 1 SalMigrationTest — 내장 PostgreSQL 16(Docker 없음), 합성 데이터만 */
class SalMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static final String RULE_2026 = "0192d000-0000-7000-8000-000000002026";
    static UUID client;
    static UUID opp;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("sal_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "sal").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/sal")
                .load().migrate().success).isTrue();
        client = UUID.randomUUID();
        opp = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-SAL', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO sal.opportunity(id, opp_no, source, client_id, service_type, title, created_by, updated_by) "
                    + "VALUES ('%s', 'OPP-2026-9001', 'INQUIRY', '%s', 'PRECISE_DIAG', '합성 기회', '%s', '%s')")
                    .formatted(opp, client, ACTOR, ACTOR));
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

    static UUID estimate(Statement st, String no, int ver, String status, String discount) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute(("INSERT INTO sal.estimate(id, estimate_no, version_no, opportunity_id, service_type, as_of_date, "
                + "fee_rule_set_id, rule_version, rule_snapshot, facility_snapshot, direct_labor, direct_expense, "
                + "overhead_rate, overhead, tech_fee_rate, tech_fee, subtotal, discount_rate, offer_amount, vat, valid_until, "
                + "status, created_by, updated_by) VALUES ('%s', '%s', %d, '%s', 'PRECISE_DIAG', DATE '2026-10-15', '%s', "
                + "'2026.1', '{}', '{}', 1000000, 0, 1.1, 1100000, 0.2, 420000, 2520000, %s, 2394000, 239400, "
                + "DATE '2026-11-14', '%s', '%s', '%s')").formatted(id, no, ver, opp, RULE_2026, discount, status, ACTOR,
                ACTOR));
        st.execute(("INSERT INTO sal.estimate_line(id, estimate_id, seq, line_type, work_item, grade, man_days, unit_price, "
                + "amount) VALUES ('%s', '%s', 1, 'LABOR', 'FIELD', 'ADVANCED', 2.98, 336000, 1001280)")
                .formatted(UUID.randomUUID(), id));
        return id;
    }

    static String sqlMessage(Throwable t) {
        return t.getMessage();
    }

    @Test
    @DisplayName("Step 1: sal 테이블 18개 · 예시 규칙 2건(2025.1 · 2026.1) · 적격심사 기준 · 세부 권한 시드")
    void schemaAndSeeds() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'sal' "
                    + "AND table_type = 'BASE TABLE'")).isEqualTo(18L);
            assertThat(scalar(st, "SELECT string_agg(rule_version, ',' ORDER BY valid_from) FROM sal.fee_rule_set "
                    + "WHERE status = 'ACTIVE'")).isEqualTo("2025.1,2026.1");
            assertThat(scalar(st, "SELECT body->'laborUnitPrices'->>'SPECIAL' FROM sal.fee_rule_set "
                    + "WHERE rule_version = '2026.1'")).isEqualTo("418000");
            assertThat(scalar(st, "SELECT count(*) FROM sal.qual_criteria WHERE status = 'ACTIVE'")).isEqualTo(1L);
            assertThat(scalar(st, "SELECT string_agg(role, ',' ORDER BY role) FROM core.role_permission "
                    + "WHERE permission = 'sal:discount:approve'")).isEqualTo("EXEC");
        }
    }

    @Test
    @DisplayName("FR-SAL-010: ACTIVE 대가 규칙 기간 겹침은 EXCLUDE 로 거부(DRAFT 는 허용)")
    void activeRuleOverlap() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            String ins = "INSERT INTO sal.fee_rule_set(id, rule_version, valid_from, status, body, legal_basis, created_by) "
                    + "VALUES ('%s', '%s', DATE '2026-06-01', '%s', '{}', '합성', '%s')";
            st.execute(ins.formatted(UUID.randomUUID(), "2026.D", "DRAFT", ACTOR));
            assertThatThrownBy(() -> st.execute(ins.formatted(UUID.randomUUID(), "2026.X", "ACTIVE", ACTOR)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("ex_fee_rule_active");
        }
    }

    @Test
    @DisplayName("TC-SAL-014 / NFR-SAL-05: ISSUED 견적 금액 UPDATE · 라인 INSERT/UPDATE/DELETE · 견적 DELETE → SAL-409-010, 상태 전이는 허용")
    void issuedEstimateImmutable() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID e = estimate(st, "EST-2026-9001", 1, "DRAFT", "0.05");
            st.execute("UPDATE sal.estimate SET status = 'ISSUED' WHERE id = '" + e + "'");
            assertThatThrownBy(() -> st.execute("UPDATE sal.estimate SET subtotal = subtotal + 1 WHERE id = '" + e + "'"))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> st.execute("UPDATE sal.estimate SET discount_rate = 0.01 WHERE id = '" + e + "'"))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> st.execute("UPDATE sal.estimate_line SET amount = 1 WHERE estimate_id = '" + e + "'"))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> st.execute("DELETE FROM sal.estimate_line WHERE estimate_id = '" + e + "'"))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> st.execute(("INSERT INTO sal.estimate_line(id, estimate_id, seq, line_type, work_item, "
                    + "amount) VALUES ('%s', '%s', 9, 'EXPENSE', 'TRAVEL', 1)").formatted(UUID.randomUUID(), e)))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> st.execute("DELETE FROM sal.estimate WHERE id = '" + e + "'"))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> st.execute("TRUNCATE sal.estimate_line"))
                    .hasMessageContaining("SAL-409-010");
            // 허용: 상태 전이 · 문서 · version
            st.execute("UPDATE sal.estimate SET status = 'SENT', version = version + 1 WHERE id = '" + e + "'");
            assertThat(scalar(st, "SELECT subtotal FROM sal.estimate WHERE id = '" + e + "'").toString()).isEqualTo("2520000");
        }
    }

    @Test
    @DisplayName("BR-SAL-006: EXEC 승인(approved_by) 후 PENDING 견적도 금액 · 할인 불변(새 버전으로만), DB CHECK 할인율 < 50%")
    void approvedPendingFrozenAndDiscountCap() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID e = estimate(st, "EST-2026-9002", 1, "PENDING_APPROVAL", "0.12");
            st.execute("UPDATE sal.estimate SET approved_by = '" + ACTOR + "' WHERE id = '" + e + "'");
            assertThatThrownBy(() -> st.execute("UPDATE sal.estimate SET discount_rate = 0.30 WHERE id = '" + e + "'"))
                    .hasMessageContaining("SAL-409-010");
            assertThatThrownBy(() -> estimate(st, "EST-2026-9003", 1, "DRAFT", "0.50"))
                    .hasMessageContaining("ck_discount");
            assertThat(estimate(st, "EST-2026-9004", 1, "DRAFT", "0.49")).isNotNull();
            assertThatThrownBy(() -> estimate(st, "EST-2026-9004", 1, "DRAFT", "0.10"))
                    .hasMessageContaining("ux_estimate_ver");
        }
    }

    @Test
    @DisplayName("BR-SAL-007: 같은 시설물 · 점검종류 · 도래일 DUE_SOON 리드 2번째 INSERT → ux_opp_due, LOST 사유 · 담당 CHECK")
    void dueLeadUniqueAndChecks() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID fac = UUID.randomUUID();
            String ins = ("INSERT INTO sal.opportunity(id, opp_no, source, facility_id, due_inspection_type, due_date, "
                    + "service_type, title, created_by, updated_by) VALUES ('%s', '%s', 'DUE_SOON', '" + fac
                    + "', 'PRECISE_INSP', DATE '2027-05-01', 'PRECISE_INSP', '합성 도래', '" + ACTOR + "', '" + ACTOR + "')");
            st.execute(ins.formatted(UUID.randomUUID(), "OPP-2026-9101"));
            assertThatThrownBy(() -> st.execute(ins.formatted(UUID.randomUUID(), "OPP-2026-9102")))
                    .hasMessageContaining("ux_opp_due");
            assertThatThrownBy(() -> st.execute("UPDATE sal.opportunity SET stage = 'QUALIFYING' WHERE opp_no = 'OPP-2026-9101'"))
                    .hasMessageContaining("ck_opp_owner");
            UUID emp = UUID.randomUUID();
            st.execute(("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) VALUES "
                    + "('%s', 'S-%s', '합성영업', DATE '2025-01-01', '%s', '%s')").formatted(emp,
                    emp.toString().substring(0, 8), ACTOR, ACTOR));
            assertThatThrownBy(() -> st.execute(("UPDATE sal.opportunity SET stage = 'LOST', owner_id = '%s' "
                    + "WHERE opp_no = 'OPP-2026-9101'").formatted(emp))).hasMessageContaining("ck_lost_reason");
        }
    }

    @Test
    @DisplayName("TC-SAL-041(DB): 실적증명서 번호 중복 → UNIQUE 위반, 지분율 0 · 100 초과 거부 / TC-SAL-032(DB): 수의계약 사유 누락 CHECK")
    void performanceAndContractChecks() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            String ins = "INSERT INTO sal.performance_record(id, cert_no, service_type, client_name, amount_krw, joint_share, "
                    + "start_date, end_date, source, created_by) VALUES ('%s', '%s', 'PRECISE_DIAG', '합성 발주처', 100000000, "
                    + "%s, DATE '2023-01-01', DATE '2023-06-30', 'MIGRATED', '" + ACTOR + "')";
            st.execute(ins.formatted(UUID.randomUUID(), "CERT-SYN-0001", "40"));
            assertThatThrownBy(() -> st.execute(ins.formatted(UUID.randomUUID(), "CERT-SYN-0001", "100")))
                    .hasMessageContaining("performance_record_cert_no_key");
            assertThatThrownBy(() -> st.execute(ins.formatted(UUID.randomUUID(), "CERT-SYN-0002", "0")))
                    .hasMessageContaining("joint_share");
            assertThatThrownBy(() -> st.execute(("INSERT INTO sal.contract(id, contract_no, client_id, title, service_type, "
                    + "contract_method, amount, amount_krw, sign_date, start_date, end_date, created_by, updated_by) VALUES "
                    + "('%s', 'CTR-2026-9001', '%s', '합성 수의', 'PRECISE_DIAG', 'PRIVATE_NEGOTIATED', 1000, 1000, "
                    + "DATE '2026-10-01', DATE '2026-10-02', DATE '2026-12-31', '%s', '%s')")
                    .formatted(UUID.randomUUID(), client, ACTOR, ACTOR))).hasMessageContaining("ck_negotiated");
        }
    }
}
