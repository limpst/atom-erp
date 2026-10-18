package com.atomeng.erp.fac;

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

/** 02-IMPL Step 1 FacMigrationTest — 내장 PostgreSQL 16 (ltree·pg_trgm·btree_gist 포함, PostGIS 제외) */
class FacMigrationTest {

    static String url;
    static UUID buildingId;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("fac_mig");
        var result = Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fac").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fac").load().migrate();
        assertThat(result.success).isTrue();
        try (var c = conn(); var st = c.createStatement()) {
            UUID site = UUID.randomUUID();
            buildingId = UUID.randomUUID();
            UUID actor = UUID.randomUUID();
            st.execute("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES ('%s', '합성 단지', '%s', '%s')"
                    .formatted(site, actor, actor));
            st.execute(("INSERT INTO fac.building(id, site_id, name, dong_name, pnu, created_by, updated_by) "
                    + "VALUES ('%s', '%s', '합성 본관', '본관', '1100000000000000001', '%s', '%s')")
                    .formatted(buildingId, site, actor, actor));
            st.execute(("INSERT INTO fac.grade_history(id, building_id, grade, inspection_type, performed_on, source, "
                    + "recorded_by) VALUES ('%s', '%s', 'B', 'DETAILED', DATE '2024-03-14', 'MIGRATION', '%s')")
                    .formatted(UUID.randomUUID(), buildingId, actor));
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
    @DisplayName("Step1: fac 테이블 15개(+node_defect_ref) + 시드 규칙(⚠️ 예시) 적재")
    void schemaAndSeed() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'fac'"))
                    .isEqualTo(15L);
            assertThat((Long) scalar(st, "SELECT count(*) FROM fac.inspection_cycle_rule")).isGreaterThanOrEqualTo(30L);
            assertThat(scalar(st, "SELECT count(*) FROM fac.inspection_cycle_rule WHERE legal_reference NOT LIKE '%⚠️ 예시%'"))
                    .as("모든 법령값은 예시 표시").isEqualTo(0L);
            assertThat(scalar(st, "SELECT count(*) FROM fac.inspection_cycle_rule WHERE approved_by IS NOT NULL"))
                    .as("QA 승인 전").isEqualTo(0L);
        }
    }

    @Test
    @DisplayName("TC-FAC-007: grade_history UPDATE·DELETE·TRUNCATE → 예외(restrict_violation 23001), 행 변경 없음")
    void tcFac007_gradeHistoryIsAppendOnly() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThatThrownBy(() -> st.executeUpdate("UPDATE fac.grade_history SET grade = 'A'"))
                    .isInstanceOfSatisfying(SQLException.class, e -> {
                        assertThat(e.getSQLState()).isEqualTo("23001");
                        assertThat(e.getMessage()).contains("append-only");
                    });
            assertThatThrownBy(() -> st.executeUpdate("DELETE FROM fac.grade_history"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23001"));
            assertThatThrownBy(() -> st.execute("TRUNCATE fac.grade_history"))
                    .isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23001"));
            assertThat(scalar(st, "SELECT grade FROM fac.grade_history WHERE building_id = '" + buildingId + "'"))
                    .isEqualTo("B");
        }
    }

    @Test
    @DisplayName("BR-FAC-006: 같은 (법령·종별·등급·유형) 규칙의 시행 기간 중복 → EXCLUDE 위반, 연속 기간은 허용")
    void cycleRulePeriodsMustNotOverlap() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            String ins = "INSERT INTO fac.inspection_cycle_rule(id, rule_code, law_code, facility_class, grade, "
                    + "inspection_type, cycle_months, effective_from, effective_to, legal_reference) VALUES "
                    + "(gen_random_uuid(), '%s', 'TEST', 'CLASS1', 'B', 'DETAILED', %d, DATE '%s', %s, '⚠️ 예시 테스트')";
            st.execute(ins.formatted("T-1", 36, "2020-01-01", "DATE '2027-01-01'"));
            st.execute(ins.formatted("T-2", 30, "2027-01-01", "NULL")); // 연속 — 허용
            assertThatThrownBy(() -> st.execute(ins.formatted("T-3", 24, "2026-06-01", "NULL")))
                    .hasMessageContaining("inspection_cycle_rule_excl");
        }
    }

    @Test
    @DisplayName("BR-FAC-002: 활성 시설물의 PNU+동명 유일")
    void pnuDongUnique() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID site = (UUID) scalar(st, "SELECT site_id FROM fac.building WHERE id = '" + buildingId + "'");
            UUID a = UUID.randomUUID();
            assertThatThrownBy(() -> st.execute(("INSERT INTO fac.building(id, site_id, name, dong_name, pnu, created_by, "
                    + "updated_by) VALUES ('%s', '%s', '중복', '본관', '1100000000000000001', '%s', '%s')")
                    .formatted(UUID.randomUUID(), site, a, a))).hasMessageContaining("ux_building_pnu_dong");
        }
    }

    @Test
    @DisplayName("설계 v0.2: repair_target 대리키 — node/defect 중 NULL 허용, (repair,node,defect) 유일, 둘 다 NULL 거부")
    void repairTargetSurrogateKey() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID actor = UUID.randomUUID();
            UUID repair = UUID.randomUUID();
            st.execute(("INSERT INTO fac.repair_history(id, building_id, method, created_by, updated_by) "
                    + "VALUES ('%s', '%s', '에폭시 주입(합성)', '%s', '%s')").formatted(repair, buildingId, actor, actor));
            UUID defect = UUID.randomUUID();
            String ins = "INSERT INTO fac.repair_target(id, repair_id, node_id, defect_id) VALUES (gen_random_uuid(), '%s', NULL, %s)";
            st.execute(ins.formatted(repair, "'" + defect + "'"));
            assertThatThrownBy(() -> st.execute(ins.formatted(repair, "'" + defect + "'")))
                    .hasMessageContaining("ux_repair_target");
            assertThatThrownBy(() -> st.execute(ins.formatted(repair, "NULL"))).hasMessageContaining("check");
        }
    }
}
