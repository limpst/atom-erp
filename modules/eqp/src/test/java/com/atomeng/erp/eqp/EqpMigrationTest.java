package com.atomeng.erp.eqp;

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

/** 04-IMPL Step 1 EqpMigrationTest — 내장 PostgreSQL 16(Docker 없음, btree_gist 사용 가능 · PostGIS 없음), 합성 데이터만 */
class EqpMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static final String RH_TYPE = "0192c000-0000-7000-8000-000000000001";
    static UUID project;
    static UUID eq;
    static int serial;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("eqp_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "eqp").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/eqp")
                .load().migrate().success).isTrue();
        project = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-EQP', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-9401', '합성 장비', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, ACTOR, ACTOR));
            eq = equipment(st);
        }
    }

    static UUID equipment(Statement st) throws SQLException {
        UUID id = UUID.randomUUID();
        serial++;
        st.execute(("INSERT INTO eqp.equipment(id, code, type_id, serial_no, qr_secret, status, created_by, updated_by) "
                + "VALUES ('%s', 'EQ-NDT-RH-9%03d', '%s', 'SYN-%d', decode(repeat('ab', 32), 'hex'), 'AVAILABLE', '%s', '%s')")
                .formatted(id, serial, RH_TYPE, serial, ACTOR, ACTOR));
        return id;
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

    static String sqlState(Throwable t) {
        return t instanceof SQLException se ? se.getSQLState() : null;
    }

    @Test
    @DisplayName("Step 1: eqp 테이블 · 유형 11종(A1.3 10종 + 키트 부속) · 규칙 · 세부 권한 시드, 규칙 기간 겹침은 EXCLUDE 거부")
    void schemaAndSeeds() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'eqp'"))
                    .isEqualTo(15L);   // 14 + V4_0_2 measurement_trace(Step 9)
            assertThat(scalar(st, "SELECT count(*) FROM eqp.equipment_type")).isEqualTo(11L);
            assertThat(scalar(st, "SELECT count(*) FROM eqp.calibration_rule")).isEqualTo(11L);
            assertThat(scalar(st, "SELECT pre_use_check->'anvil'->>'target' FROM eqp.calibration_rule r JOIN "
                    + "eqp.equipment_type t ON t.id = r.type_id WHERE t.code = 'NDT-RH'")).isEqualTo("80");
            assertThat((Long) scalar(st, "SELECT count(*) FROM core.role_permission WHERE permission LIKE 'eqp:%' "
                    + "AND split_part(permission, ':', 2) NOT IN ('equipment','checkout','setting')")).isEqualTo(31L);
            assertThatThrownBy(() -> st.execute("INSERT INTO eqp.calibration_rule(id, type_id, interval_months, legal_basis, "
                    + "effective_from) VALUES (gen_random_uuid(), '" + RH_TYPE + "', 6, '합성', DATE '2026-01-01')"))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
        }
    }

    @Test
    @DisplayName("TC-EQP-002(DB): no_double_booking EXCLUDE — 겹치는 기간 23P01, 경계 맞닿음 허용, 취소하면 슬롯 해제")
    void noDoubleBooking() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID e = equipment(st);
            String ins = "INSERT INTO eqp.reservation(id, equipment_id, project_id, period, status, requested_by) VALUES "
                    + "(gen_random_uuid(), '" + e + "', '" + project + "', daterange(DATE '%s', DATE '%s'), '%s', '" + ACTOR + "')";
            st.execute(ins.formatted("2026-11-01", "2026-11-04", "REQUESTED"));
            assertThatThrownBy(() -> st.execute(ins.formatted("2026-11-03", "2026-11-06", "REQUESTED")))
                    .satisfies(x -> assertThat(sqlState(x)).isEqualTo("23P01"));
            st.execute(ins.formatted("2026-11-04", "2026-11-06", "CONFIRMED"));   // [11-01,11-04) 와 맞닿음
            st.execute(ins.formatted("2026-11-02", "2026-11-03", "CANCELLED"));    // 비활성 상태는 제약 밖
            st.execute("UPDATE eqp.reservation SET status = 'CANCELLED' WHERE equipment_id = '" + e
                    + "' AND lower(period) = DATE '2026-11-01'");
            st.execute(ins.formatted("2026-11-02", "2026-11-04", "REQUESTED"));
            assertThatThrownBy(() -> st.execute(ins.formatted("2026-11-10", "2026-11-10", "REQUESTED")))
                    .isInstanceOf(SQLException.class);   // 빈 기간 CHECK
        }
    }

    @Test
    @DisplayName("BR-EQP-004: 활성 반출은 장비당 1건(ux_active_checkout), sync_conflict 행은 예외 · clientOpId 유니크")
    void activeCheckoutUnique() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID e = equipment(st);
            String ins = "INSERT INTO eqp.checkout(id, equipment_id, project_id, out_by, out_at, pre_check, client_op_id, "
                    + "sync_conflict) VALUES (gen_random_uuid(), '" + e + "', '" + project + "', '" + ACTOR
                    + "', now(), '{}', '%s', %s)";
            UUID op = UUID.randomUUID();
            st.execute(ins.formatted(op, "false"));
            assertThatThrownBy(() -> st.execute(ins.formatted(UUID.randomUUID(), "false")))
                    .satisfies(x -> assertThat(sqlState(x)).isEqualTo("23505"));
            st.execute(ins.formatted(UUID.randomUUID(), "true"));
            assertThatThrownBy(() -> st.execute(ins.formatted(op, "true")))
                    .satisfies(x -> assertThat(sqlState(x)).isEqualTo("23505"));
        }
    }

    @Test
    @DisplayName("TC-EQP-008(DB): Override 유효기간 8일 → CHECK 위반(23514), 7일 허용 · 사유 필수 · append-only")
    void overrideMax7Days() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            String ins = "INSERT INTO eqp.calibration_override(id, equipment_id, reason, valid_from, valid_to, approved_by) "
                    + "VALUES (gen_random_uuid(), '" + eq + "', '%s', DATE '2026-10-01', DATE '%s', '" + ACTOR + "')";
            assertThatThrownBy(() -> st.execute(ins.formatted("합성 예외 사유", "2026-10-08")))
                    .satisfies(x -> assertThat(sqlState(x)).isEqualTo("23514"));
            assertThatThrownBy(() -> st.execute(ins.formatted("짧음", "2026-10-03")))
                    .satisfies(x -> assertThat(sqlState(x)).isEqualTo("23514"));
            st.execute(ins.formatted("합성 예외 사유", "2026-10-07"));
            assertThatThrownBy(() -> st.execute("UPDATE eqp.calibration_override SET valid_to = DATE '2026-10-02'"))
                    .hasMessageContaining("append-only");
        }
    }

    @Test
    @DisplayName("NFR-EQP-03 · BR-EQP-008: 교정 이력 UPDATE/DELETE 거부, 장비 행 DELETE 거부(폐기는 상태), 차기일 ≤ 교정일 거부, 시리얼 중복 거부")
    void immutability() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute("INSERT INTO eqp.calibration(id, equipment_id, calibrated_on, next_due_on, result, created_by) VALUES "
                    + "(gen_random_uuid(), '" + eq + "', DATE '2026-03-10', DATE '2027-03-09', 'PASS', '" + ACTOR + "')");
            assertThatThrownBy(() -> st.execute("UPDATE eqp.calibration SET result = 'FAIL' WHERE equipment_id = '" + eq + "'"))
                    .hasMessageContaining("EQP-409-010");
            assertThatThrownBy(() -> st.execute("DELETE FROM eqp.calibration WHERE equipment_id = '" + eq + "'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.execute("DELETE FROM eqp.equipment WHERE id = '" + eq + "'"))
                    .hasMessageContaining("BR-EQP-008");
            assertThatThrownBy(() -> st.execute("INSERT INTO eqp.calibration(id, equipment_id, calibrated_on, next_due_on, "
                    + "result, created_by) VALUES (gen_random_uuid(), '" + eq + "', DATE '2026-03-10', DATE '2026-03-10', "
                    + "'PASS', '" + ACTOR + "')")).satisfies(x -> assertThat(sqlState(x)).isEqualTo("23514"));
            assertThatThrownBy(() -> st.execute(("INSERT INTO eqp.equipment(id, code, type_id, serial_no, qr_secret, "
                    + "created_by, updated_by) VALUES (gen_random_uuid(), 'EQ-DUP-0001', '%s', 'SYN-1', "
                    + "decode(repeat('cd', 32), 'hex'), '%s', '%s')").formatted(RH_TYPE, ACTOR, ACTOR)))
                    .satisfies(x -> assertThat(sqlState(x)).isEqualTo("23505"));
        }
    }
}
