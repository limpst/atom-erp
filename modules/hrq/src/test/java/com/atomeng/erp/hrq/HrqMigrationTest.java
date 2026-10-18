package com.atomeng.erp.hrq;

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

/** 03-IMPL Step 1 HrqMigrationTest — 내장 PostgreSQL 16 (btree_gist 포함, PostGIS 제외) */
class HrqMigrationTest {

    static String url;
    static UUID emp;
    static UUID project;
    static final UUID ACTOR = UUID.randomUUID();

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("hrq_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "hrq").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/hrq").load().migrate().success).isTrue();
        emp = UUID.randomUUID();
        project = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) VALUES "
                    + "('%s', 'E-MIG-1', '합성기술자', DATE '2020-01-01', '%s', '%s')").formatted(emp, ACTOR, ACTOR));
            st.execute(("INSERT INTO hrq.employee(id, emp_no, name, field, employment_type, hired_on, created_by, updated_by) "
                    + "VALUES ('%s', 'E-MIG-1', '합성기술자', 'ARCH', 'FULLTIME', DATE '2020-01-01', '%s', '%s')")
                    .formatted(emp, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-MIG', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-9001', '합성', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, ACTOR, ACTOR));
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

    static UUID insertTs(Statement st, String status, int rev, UUID key, UUID supersedes) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute(("INSERT INTO hrq.timesheet_entry(id, employee_id, project_id, work_date, work_type, hours, status, "
                + "timesheet_key, revision_no, supersedes_id, correction_reason, posting_period, created_by, updated_by) "
                + "VALUES ('%s', '%s', '%s', DATE '2026-09-10', 'FIELD', 8, '%s', '%s', %d, %s, %s, '202609', '%s', '%s')")
                .formatted(id, emp, project, status, key == null ? id : key, rev,
                        supersedes == null ? "NULL" : "'" + supersedes + "'", rev == 1 ? "NULL" : "'정정'", ACTOR, ACTOR));
        return id;
    }

    @Test
    @DisplayName("Step1: hrq 테이블 생성 + 규칙 시드(⚠️ 예시, 미승인)")
    void schemaAndSeed() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat((Long) scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'hrq'"))
                    .isEqualTo(15L);
            assertThat(scalar(st, "SELECT count(*) FROM hrq.registration_rule WHERE legal_reference NOT LIKE '%⚠️ 예시%'"))
                    .isEqualTo(0L);
            assertThat(scalar(st, "SELECT data_type FROM information_schema.columns WHERE table_schema = 'hrq' "
                    + "AND table_name = 'month_close' AND column_name = 'year_month'")).isEqualTo("character");
            assertThatThrownBy(() -> st.execute("INSERT INTO hrq.month_close(year_month, status) VALUES ('202613', 'OPEN')"))
                    .hasMessageContaining("check");
        }
    }

    @Test
    @DisplayName("Step1: 같은 기술자 등급 이력 기간 겹침 → EXCLUDE 위반, 연속 기간은 허용")
    void gradeHistoryNoOverlap() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            String ins = "INSERT INTO hrq.grade_history(id, employee_id, grade, valid_from, valid_to, created_by) "
                    + "VALUES (gen_random_uuid(), '" + emp + "', '%s', DATE '%s', %s, '" + ACTOR + "')";
            st.execute(ins.formatted("INTER", "2020-01-01", "DATE '2026-11-01'"));
            st.execute(ins.formatted("SENIOR", "2026-11-01", "NULL"));
            assertThatThrownBy(() -> st.execute(ins.formatted("MASTER", "2026-06-01", "NULL")))
                    .hasMessageContaining("grade_history_no_overlap");
        }
    }

    @Test
    @DisplayName("BR-HRQ-014: 확정 타임시트는 값 UPDATE·DELETE 금지(restrict_violation), 상태 전이만 허용, 현재 리비전 1개")
    void confirmedTimesheetIsAppendOnly() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID draft = insertTs(st, "DRAFT", 1, null, null);
            assertThat(st.executeUpdate("UPDATE hrq.timesheet_entry SET hours = 6 WHERE id = '" + draft + "'")).isEqualTo(1);
            assertThat(st.executeUpdate("DELETE FROM hrq.timesheet_entry WHERE id = '" + draft + "'")).isEqualTo(1);

            UUID approved = insertTs(st, "APPROVED", 1, null, null);
            assertThatThrownBy(() -> st.executeUpdate("UPDATE hrq.timesheet_entry SET hours = 6 WHERE id = '" + approved + "'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23001"));
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM hrq.timesheet_entry WHERE id = '" + approved + "'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.executeUpdate("UPDATE hrq.timesheet_entry SET status = 'DRAFT' WHERE id = '"
                    + approved + "'")).hasMessageContaining("not allowed");
            // 현재 리비전은 키당 1개: SUPERSEDED 처리 전 새 리비전 → ux_ts_current 위반
            assertThatThrownBy(() -> insertTs(st, "APPROVED", 2, approved, approved)).hasMessageContaining("ux_ts_current");
            st.executeUpdate("UPDATE hrq.timesheet_entry SET status = 'SUPERSEDED' WHERE id = '" + approved + "'");
            insertTs(st, "APPROVED", 2, approved, approved);
            assertThatThrownBy(() -> st.executeUpdate("UPDATE hrq.timesheet_entry SET status = 'APPROVED' WHERE id = '"
                    + approved + "'")).hasMessageContaining("not allowed");
            // 리비전 2 이상은 정정 사유 필수
            assertThatThrownBy(() -> st.execute(("INSERT INTO hrq.timesheet_entry(id, employee_id, project_id, work_date, "
                    + "work_type, hours, status, timesheet_key, revision_no, supersedes_id, created_by, updated_by) VALUES "
                    + "(gen_random_uuid(), '%s', '%s', DATE '2026-09-11', 'FIELD', 1, 'APPROVED', gen_random_uuid(), 2, "
                    + "'%s', '%s', '%s')").formatted(emp, project, approved, ACTOR, ACTOR)))
                    .hasMessageContaining("ck_ts_correction");
        }
    }
}
