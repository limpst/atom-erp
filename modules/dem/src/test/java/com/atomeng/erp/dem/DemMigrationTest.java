package com.atomeng.erp.dem;

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

/**
 * 10-IMPL Step 1 DemMigrationTest — 내장 PostgreSQL 16(Docker 없음, btree_gist 사용 · PostGIS 없음), 합성 데이터만.
 * 불변 트리거(TC-DEM-008 · 013 · 014) · 책임감리자 기간 EXCLUDE(TC-DEM-003) · append-only 를 DB 에서 직접 확인한다.
 */
class DemMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static final String UPPER_TEMPLATE = "0192d000-0000-7000-8000-000000000103";
    static UUID project;
    static UUID wp;
    static UUID employee;
    static UUID document;
    static int seq;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("dem_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fac", "hrq", "dem").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fac", "classpath:db/migration/hrq",
                        "classpath:db/migration/dem")
                .load().migrate().success).isTrue();
        project = UUID.randomUUID();
        employee = UUID.randomUUID();
        wp = UUID.randomUUID();
        document = UUID.randomUUID();
        UUID client = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) VALUES "
                    + "('%s', 'E-DEM-1', '합성감리자', DATE '2020-01-01', '%s', '%s')").formatted(employee, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-DEM', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-9101', '합성 해체감리', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, ACTOR, ACTOR));
            st.execute("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES ('%s', '합성 단지', '%s', '%s')"
                    .formatted(site, ACTOR, ACTOR));
            st.execute(("INSERT INTO fac.building(id, site_id, name, created_by, updated_by) VALUES "
                    + "('%s', '%s', '합성 해체 대상 동', '%s', '%s')").formatted(building, site, ACTOR, ACTOR));
            st.execute(("INSERT INTO dem.project(project_id, building_id, created_by, updated_by) VALUES "
                    + "('%s', '%s', '%s', '%s')").formatted(project, building, ACTOR, ACTOR));
            st.execute(("INSERT INTO dem.work_package(id, project_id, facility_id, package_type, created_by, updated_by) "
                    + "VALUES ('%s', '%s', '%s', 'DEM_SUPERVISION', '%s', '%s')").formatted(wp, project, building, ACTOR, ACTOR));
            st.execute(("INSERT INTO dem.document(id, work_package_id, doc_no, title, created_by) VALUES "
                    + "('%s', '%s', 'S-101', '합성 기초 평면도', '%s')").formatted(document, wp, ACTOR));
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

    static String sqlState(Throwable t) {
        return t instanceof SQLException se ? se.getSQLState() : null;
    }

    /** 합성 첨부 1건(체인 단위마다 새 scope) */
    static UUID attachment(Statement st) throws SQLException {
        UUID id = UUID.randomUUID();
        seq++;
        st.execute(("INSERT INTO core.attachment(id, bucket, object_key, file_name, mime_type, size_bytes, sha256, chain_hash, "
                + "owner_module, owner_type, owner_id, chain_scope, created_by) VALUES ('%s', 'drawings', 'dem/syn-%d.pdf', "
                + "'syn-%d.pdf', 'application/pdf', 10, '%s', '%s', 'DEM', 'Revision', '%s', '%s', '%s')")
                .formatted(id, seq, seq, "%064x".formatted(seq), "%064x".formatted(seq + 1000), id, UUID.randomUUID(), ACTOR));
        return id;
    }

    static UUID revision(Statement st, String revCode, String status, String issuedRev) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute(("INSERT INTO dem.document_revision(id, document_id, rev_code, issued_rev, status, file_attachment_id, "
                + "change_summary, issued_at, created_by) VALUES ('%s', '%s', '%s', %s, '%s', '%s', '합성 변경', %s, '%s')")
                .formatted(id, document, revCode, issuedRev == null ? "NULL" : "'" + issuedRev + "'", status,
                        attachment(st), issuedRev == null ? "NULL" : "now()", ACTOR));
        return id;
    }

    @Test
    @DisplayName("Step 1: dem 테이블 28개 · 점검표 5공정 17항목 · 규칙 8종 · 세부 권한 시드(⚠️ 예시 approved_by NULL)")
    void schemaAndSeeds() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'dem'"))
                    .isEqualTo(28L);
            assertThat(scalar(st, "SELECT count(*) FROM dem.checklist_template WHERE approved_by IS NULL")).isEqualTo(5L);
            assertThat(scalar(st, "SELECT count(*) FROM dem.checklist_template_item")).isEqualTo(17L);
            assertThat(scalar(st, "SELECT count(*) FROM dem.rule_param WHERE approved_by IS NULL")).isEqualTo(8L);
            assertThat(scalar(st, "SELECT value_json->>'chief' FROM dem.rule_param WHERE rule_key = 'MAX_CONCURRENT_SUPERVISION'"))
                    .isEqualTo("3");
            assertThat(scalar(st, "SELECT count(*) FROM core.role_permission WHERE role = 'PM' "
                    + "AND permission = 'dem:transmittal:issue'")).isEqualTo(1L);
            // 8.2 표 셀 리소스(supervision-log · setting)는 V0002 에만 — DEM 시드가 표 검사 리소스를 건드리지 않는다
            // PTL-01(V0042): CUSTOMER dem:supervision-log:read 삭제 → 15 → 14
            assertThat(scalar(st, "SELECT count(*) FROM core.role_permission WHERE permission LIKE 'dem:supervision-log:%'"))
                    .isEqualTo(14L);
        }
    }

    @Test
    @DisplayName("TC-DEM-013: ISSUED 리비전에 status=SUPERSEDED + change_summary 동시 변경 → DEM_IMMUTABLE, status 만 변경 → 성공")
    void tcDem013_supersedeOnlyStatus() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID rev = revision(st, "A", "ISSUED", "0");
            assertThatThrownBy(() -> st.execute("UPDATE dem.document_revision SET status = 'SUPERSEDED', "
                    + "change_summary = '몰래 바꿈' WHERE id = '" + rev + "'"))
                    .hasMessageContaining("DEM_IMMUTABLE")
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23001"));
            assertThat(st.executeUpdate("UPDATE dem.document_revision SET status = 'SUPERSEDED' WHERE id = '" + rev + "'"))
                    .isEqualTo(1);
            // SUPERSEDED 는 되돌릴 수도 고칠 수도 없다
            assertThatThrownBy(() -> st.execute("UPDATE dem.document_revision SET status = 'ISSUED' WHERE id = '" + rev + "'"))
                    .hasMessageContaining("DEM_IMMUTABLE");
            assertThat(scalar(st, "SELECT change_summary FROM dem.document_revision WHERE id = '" + rev + "'"))
                    .isEqualTo("합성 변경");
        }
    }

    @Test
    @DisplayName("TC-DEM-008: ISSUED 리비전 파일 교체(file_attachment_id UPDATE) → DEM_IMMUTABLE, WIP 리비전은 자유롭게 수정")
    void tcDem008_issuedFileReplaceBlocked() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID issued = revision(st, "B", "ISSUED", "1");
            UUID other = attachment(st);
            assertThatThrownBy(() -> st.execute("UPDATE dem.document_revision SET file_attachment_id = '" + other
                    + "' WHERE id = '" + issued + "'")).hasMessageContaining("DEM_IMMUTABLE");
            UUID wip = revision(st, "C", "WIP", null);
            assertThat(st.executeUpdate("UPDATE dem.document_revision SET file_attachment_id = '" + other
                    + "', status = 'INTERNAL_REVIEW' WHERE id = '" + wip + "'")).isEqualTo(1);
            // 한 도서에 현재 발행본은 1개(부분 유니크)
            assertThatThrownBy(() -> revision(st, "D", "ISSUED", "2"))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23505"));
        }
    }

    @Test
    @DisplayName("TC-DEM-014: ISSUED 리비전 · LOCKED 일지 · frozen 모델 DELETE → 3건 모두 DEM_IMMUTABLE(delete forbidden), TRUNCATE 금지")
    void tcDem014_deleteForbidden() throws Exception {
        UUID otherDoc = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO dem.document(id, work_package_id, doc_no, title, created_by) VALUES "
                    + "('%s', '%s', 'CAL-01', '합성 구조계산서', '%s')").formatted(otherDoc, wp, ACTOR));
            UUID rev = UUID.randomUUID();
            st.execute(("INSERT INTO dem.document_revision(id, document_id, rev_code, issued_rev, status, file_attachment_id, "
                    + "issued_at, created_by) VALUES ('%s', '%s', 'A', '0', 'ISSUED', '%s', now(), '%s')")
                    .formatted(rev, otherDoc, attachment(st), ACTOR));
            UUID log = UUID.randomUUID();
            st.execute(("INSERT INTO dem.daily_log(id, work_package_id, log_date, author_id, template_id, phase_code, status, "
                    + "locked_at, content_sha256, created_by, updated_by) VALUES ('%s', '%s', DATE '2026-10-15', '%s', '%s', "
                    + "'UPPER_DEMOLITION', 'LOCKED', now(), '%s', '%s', '%s')")
                    .formatted(log, wp, employee, UPPER_TEMPLATE, "a".repeat(64), ACTOR, ACTOR));
            UUID model = UUID.randomUUID();
            st.execute(("INSERT INTO dem.analysis_model_version(id, work_package_id, version_no, software, file_attachment_id, "
                    + "change_summary, frozen, created_by) VALUES ('%s', '%s', 1, 'MIDAS Gen (합성)', '%s', '최초', true, '%s')")
                    .formatted(model, wp, attachment(st), ACTOR));

            for (String sql : new String[] {
                    "DELETE FROM dem.document_revision WHERE id = '" + rev + "'",
                    "DELETE FROM dem.daily_log WHERE id = '" + log + "'",
                    "DELETE FROM dem.analysis_model_version WHERE id = '" + model + "'"}) {
                assertThatThrownBy(() -> st.execute(sql)).hasMessageContaining("DEM_IMMUTABLE")
                        .hasMessageContaining("delete forbidden");
            }
            // 잠금 일지 · 동결 모델은 UPDATE 도 금지
            assertThatThrownBy(() -> st.execute("UPDATE dem.daily_log SET remarks = 'x' WHERE id = '" + log + "'"))
                    .hasMessageContaining("DEM_IMMUTABLE");
            assertThatThrownBy(() -> st.execute("UPDATE dem.analysis_model_version SET change_summary = 'x' WHERE id = '"
                    + model + "'")).hasMessageContaining("DEM_IMMUTABLE");
            assertThatThrownBy(() -> st.execute("TRUNCATE dem.document_revision CASCADE"))
                    .hasMessageContaining("DEM_IMMUTABLE");
            assertThat(scalar(st, "SELECT count(*) FROM dem.document_revision WHERE id = '" + rev + "'")).isEqualTo(1L);
            // 미발행(WIP) 리비전은 삭제 가능
            UUID wip = UUID.randomUUID();
            st.execute(("INSERT INTO dem.document_revision(id, document_id, rev_code, status, file_attachment_id, created_by) "
                    + "VALUES ('%s', '%s', 'B', 'WIP', '%s', '%s')").formatted(wip, otherDoc, attachment(st), ACTOR));
            assertThat(st.executeUpdate("DELETE FROM dem.document_revision WHERE id = '" + wip + "'")).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("TC-DEM-003: 같은 업무 패키지에 책임감리자 기간이 겹치면 EXCLUDE 위반(23P01), 보조감리자 · 비겹침은 허용")
    void tcDem003_chiefOverlapExcluded() throws Exception {
        UUID wp2 = UUID.randomUUID();
        UUID e2 = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO dem.work_package(id, project_id, package_type, created_by, updated_by) "
                    + "VALUES ('%s', '%s', 'STR_SUPERVISION', '%s', '%s')").formatted(wp2, project, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) VALUES "
                    + "('%s', 'E-DEM-2', '합성감리자2', DATE '2020-01-01', '%s', '%s')").formatted(e2, ACTOR, ACTOR));
            String ins = "INSERT INTO dem.supervisor_assignment(id, work_package_id, employee_id, role, valid_from, valid_to, "
                    + "hrq_check_snapshot, created_by, updated_by) VALUES (gen_random_uuid(), '" + wp2 + "', '%s', '%s', "
                    + "DATE '%s', %s, '{}', '" + ACTOR + "', '" + ACTOR + "')";
            st.execute(ins.formatted(employee, "CHIEF", "2026-10-01", "DATE '2026-12-31'"));
            assertThatThrownBy(() -> st.execute(ins.formatted(e2, "CHIEF", "2026-12-01", "NULL")))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23P01"));
            st.execute(ins.formatted(e2, "ASSISTANT", "2026-12-01", "NULL"));
            st.execute(ins.formatted(e2, "CHIEF", "2027-01-01", "NULL"));
            assertThat(scalar(st, "SELECT count(*) FROM dem.supervisor_assignment WHERE work_package_id = '" + wp2 + "'"))
                    .isEqualTo(3L);
        }
    }

    @Test
    @DisplayName("append-only: 회신 · 정정 · 템플릿 항목 UPDATE/DELETE 거부, LOCKED 일지는 잠금 해시 없이 못 만든다(CHECK)")
    void appendOnlyAndChecks() throws Exception {
        try (var c = conn(); var st = c.createStatement()) {
            UUID rev = revision(st, "E", "WIP", null);
            UUID comment = UUID.randomUUID();
            st.execute(("INSERT INTO dem.review_comment(id, revision_id, comment_no, source, body, author_id) VALUES "
                    + "('%s', '%s', 'C-001', 'CLIENT', '합성 지적', '%s')").formatted(comment, rev, ACTOR));
            st.execute(("INSERT INTO dem.review_reply(id, comment_id, body, previous_status, resulting_status) VALUES "
                    + "(gen_random_uuid(), '%s', '합성 회신', 'OPEN', 'RESPONDED')").formatted(comment));
            assertThatThrownBy(() -> st.execute("UPDATE dem.review_reply SET body = 'x'"))
                    .hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.execute("DELETE FROM dem.checklist_template_item WHERE template_id = '"
                    + UPPER_TEMPLATE + "'")).hasMessageContaining("append-only");
            assertThatThrownBy(() -> st.execute(("INSERT INTO dem.daily_log(id, work_package_id, log_date, author_id, "
                    + "template_id, phase_code, status, created_by, updated_by) VALUES (gen_random_uuid(), '%s', "
                    + "DATE '2026-10-16', '%s', '%s', 'UPPER_DEMOLITION', 'LOCKED', '%s', '%s')")
                    .formatted(wp, employee, UPPER_TEMPLATE, ACTOR, ACTOR)))
                    .satisfies(e -> assertThat(sqlState(e)).isEqualTo("23514"));
        }
    }
}
