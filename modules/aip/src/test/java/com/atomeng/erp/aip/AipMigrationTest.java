package com.atomeng.erp.aip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * aip 스키마 마이그레이션 시험 — 내장 PostgreSQL 16, 합성 데이터만.
 * TC-AIP-017(감사 월 파티션 · PK (job_id, created_at) · append-only), BR-AIP-012(DB 트리거), BR-AIP-002(폭 ↔ 스케일 CHECK),
 * 배포 별칭 기간 겹침 금지(btree_gist EXCLUDE), 시드 레지스트리.
 */
class AipMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static UUID project;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("aip_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "aip").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/aip")
                .load().migrate().success).isTrue();
        try (Connection c = conn(); Statement st = c.createStatement()) {
            UUID client = UUID.randomUUID();
            project = UUID.randomUUID();
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-AIP-1', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-AIP-0001', '합성 프로젝트', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, ACTOR, ACTOR));
        }
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(url, EmbeddedPg.user(), EmbeddedPg.password());
    }

    static String scalar(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    static UUID job(Statement st) throws SQLException {
        return UUID.fromString(scalar(st, ("INSERT INTO aip.job(service_code, requested_by, input_sha256, vram_budget_mb, "
                + "project_id) VALUES ('CRACK_DETECT', '%s', '%s', 1200, '%s') RETURNING id").formatted(ACTOR, "a".repeat(64),
                project)));
    }

    static UUID attachment(Statement st) throws SQLException {
        UUID id = UUID.randomUUID();
        st.execute(("INSERT INTO core.attachment(id, bucket, object_key, file_name, mime_type, size_bytes, sha256, chain_hash, "
                + "owner_module, owner_type, owner_id, chain_scope, created_by) VALUES ('%s', 'photos', 'k/%s', 'syn.png', "
                + "'image/png', 10, '%s', '%s', 'AIP', 'Synthetic', '%s', '%s', '%s')").formatted(id, id, "c".repeat(64),
                "d".repeat(64), id, UUID.randomUUID(), ACTOR));
        return id;
    }

    static String partitionName(LocalDate month) {
        return "aip.inference_audit_" + month.format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    @Test
    @DisplayName("TC-AIP-017: 현재월 · 다음달 INSERT → 해당 월 파티션(3개월 선생성), 같은 (job_id, created_at) 재삽입 → PK 위반, 수정 · 삭제 금지")
    void auditPartitions() throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            LocalDate now = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
            for (int m = 0; m <= 3; m++) {
                assertThat(scalar(st, "SELECT to_regclass('" + partitionName(now.plusMonths(m)) + "')")).isNotNull();
            }
            UUID j1 = job(st);
            String cur = now.withDayOfMonth(15) + "T03:00:00Z";
            String next = now.plusMonths(1).withDayOfMonth(2) + "T03:00:00Z";
            st.execute(("INSERT INTO aip.inference_audit(job_id, created_at, service_code, input_sha256) VALUES "
                    + "('%s', '%s', 'CRACK_DETECT', '%s'), ('%s', '%s', 'CRACK_DETECT', '%s')")
                    .formatted(j1, cur, "a".repeat(64), j1, next, "a".repeat(64)));
            assertThat(scalar(st, "SELECT tableoid::regclass::text FROM aip.inference_audit WHERE job_id = '" + j1
                    + "' AND created_at = '" + cur + "'")).isEqualTo(partitionName(now));
            assertThat(scalar(st, "SELECT tableoid::regclass::text FROM aip.inference_audit WHERE job_id = '" + j1
                    + "' AND created_at = '" + next + "'")).isEqualTo(partitionName(now.plusMonths(1)));
            assertThatThrownBy(() -> st.execute(("INSERT INTO aip.inference_audit(job_id, created_at, service_code, "
                    + "input_sha256) VALUES ('%s', '%s', 'CRACK_DETECT', '%s')").formatted(j1, cur, "a".repeat(64))))
                    .isInstanceOf(SQLException.class).hasMessageContaining("duplicate key");
            assertThatThrownBy(() -> st.execute("UPDATE aip.inference_audit SET latency_ms = 1 WHERE job_id = '" + j1 + "'"))
                    .hasMessageContaining("AIP_APPEND_ONLY");
            assertThatThrownBy(() -> st.execute("DELETE FROM aip.inference_audit WHERE job_id = '" + j1 + "'"))
                    .hasMessageContaining("AIP_APPEND_ONLY");
            // 선생성 범위 밖(+6개월) — 파티션이 없으면 실패, ensure 후 성공(배치 보장)
            String far = now.plusMonths(6).withDayOfMonth(3) + "T00:00:00Z";
            assertThatThrownBy(() -> st.execute(("INSERT INTO aip.inference_audit(job_id, created_at, service_code, "
                    + "input_sha256) VALUES ('%s', '%s', 'X', '%s')").formatted(j1, far, "a".repeat(64))))
                    .hasMessageContaining("no partition");
            assertThat(scalar(st, "SELECT aip.ensure_audit_partition('" + now.plusMonths(6) + "')")).isEqualTo("t");
            assertThat(scalar(st, "SELECT aip.ensure_audit_partition('" + now.plusMonths(6) + "')")).isEqualTo("f");
            st.execute(("INSERT INTO aip.inference_audit(job_id, created_at, service_code, input_sha256) VALUES "
                    + "('%s', '%s', 'X', '%s')").formatted(j1, far, "a".repeat(64)));
        }
    }

    @Test
    @DisplayName("BR-AIP-012 DB 가드: 결정 시점 true 거부 · 데이터셋 커밋 없이 true 전환 거부 · 커밋 후 되돌리기 거부")
    void trainingEligibleGuard() throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            UUID j = job(st);
            UUID photo = attachment(st);
            UUID s1 = UUID.fromString(scalar(st, ("INSERT INTO aip.suggestion(job_id, project_id, photo_attachment_id, "
                    + "defect_type, geometry, confidence) VALUES ('%s', '%s', '%s', 'CRACK', '{}', 0.9) RETURNING id")
                    .formatted(j, project, photo)));
            assertThatThrownBy(() -> st.execute(("INSERT INTO aip.feedback(suggestion_id, decision, decided_by, "
                    + "training_eligible) VALUES ('%s', 'ACCEPTED', '%s', true)").formatted(s1, ACTOR)))
                    .hasMessageContaining("AIP_TRAINING_ELIGIBLE_AT_DECISION");
            UUID fb = UUID.fromString(scalar(st, ("INSERT INTO aip.feedback(suggestion_id, decision, decided_by) VALUES "
                    + "('%s', 'ACCEPTED', '%s') RETURNING id").formatted(s1, ACTOR)));
            assertThatThrownBy(() -> st.execute("UPDATE aip.feedback SET training_eligible = true WHERE id = '" + fb + "'"))
                    .hasMessageContaining("AIP_TRAINING_ELIGIBLE_WITHOUT_COMMIT");
            UUID task = UUID.fromString(scalar(st, ("INSERT INTO aip.label_task(source, feedback_id, image_attachment_id, "
                    + "image_sha256, priority_score, guideline_version, status) VALUES ('FEEDBACK', '%s', '%s', '%s', 0.1, "
                    + "'g1.0', 'DONE') RETURNING id").formatted(fb, photo, "c".repeat(64))));
            UUID ds = UUID.fromString(scalar(st, ("INSERT INTO aip.dataset_version(name, lakefs_repo, lakefs_commit, tag, "
                    + "split_manifest, data_card, committed_by) VALUES ('crack-seg', 'crack-seg', '%s', 'v-test', '{}', '{}', "
                    + "'%s') RETURNING id").formatted("e".repeat(64), ACTOR)));
            st.execute(("INSERT INTO aip.dataset_item(dataset_version_id, feedback_id, label_task_id, image_sha256, split) "
                    + "VALUES ('%s', '%s', '%s', '%s', 'train')").formatted(ds, fb, task, "c".repeat(64)));
            st.execute(("UPDATE aip.feedback SET committed_dataset_id = '%s', training_eligible = true WHERE id = '%s'")
                    .formatted(ds, fb));
            assertThatThrownBy(() -> st.execute("UPDATE aip.feedback SET training_eligible = false WHERE id = '" + fb + "'"))
                    .hasMessageContaining("AIP_TRAINING_ELIGIBLE_REVOKE");
            assertThatThrownBy(() -> st.execute("UPDATE aip.feedback SET decision = 'REJECTED', reason_code = 'X' WHERE id = '"
                    + fb + "'")).hasMessageContaining("AIP_FEEDBACK_IMMUTABLE");
            st.execute("INSERT INTO aip.job_event(job_id, kind, payload) VALUES ('" + j + "', 'STATE', '{}')");
            assertThatThrownBy(() -> st.execute("UPDATE aip.job_event SET kind = 'LOG' WHERE job_id = '" + j + "'"))
                    .hasMessageContaining("AIP_APPEND_ONLY");
        }
    }

    @Test
    @DisplayName("BR-AIP-002 CHECK: 스케일 없는(NONE) 제안에 폭 수치 금지")
    void widthNeedsScale() throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            UUID j = job(st);
            UUID photo = attachment(st);
            assertThatThrownBy(() -> st.execute(("INSERT INTO aip.suggestion(job_id, photo_attachment_id, defect_type, geometry, "
                    + "confidence, width_max_mm, scale_source) VALUES ('%s', '%s', 'CRACK', '{}', 0.8, 0.3, 'NONE')")
                    .formatted(j, photo))).hasMessageContaining("ck_width_needs_scale");
            st.execute(("INSERT INTO aip.suggestion(job_id, photo_attachment_id, defect_type, geometry, confidence, "
                    + "width_max_mm, scale_source) VALUES ('%s', '%s', 'CRACK', '{}', 0.8, 0.3, 'ARUCO')").formatted(j, photo));
        }
    }

    @Test
    @DisplayName("배포 별칭: 같은 서비스 · 별칭 활성 기간 겹침 거부(EXCLUDE), 시드 production 5건")
    void deploymentOverlap() throws Exception {
        try (Connection c = conn(); Statement st = c.createStatement()) {
            assertThat(scalar(st, "SELECT count(*) FROM aip.deployment WHERE alias = 'production' AND active_to IS NULL"))
                    .isEqualTo("5");
            assertThatThrownBy(() -> st.execute("INSERT INTO aip.deployment(service_code, alias, model_version_id, traffic_pct) "
                    + "VALUES ('CRACK_DETECT', 'production', '01920000-0000-7000-8000-00000000a101', 100)"))
                    .hasMessageContaining("ex_deployment_alias_period");
            st.execute("INSERT INTO aip.deployment(service_code, alias, model_version_id, traffic_pct) "
                    + "VALUES ('CRACK_DETECT', 'canary', '01920000-0000-7000-8000-00000000a101', 10)");
            assertThat(scalar(st, "SELECT count(*) FROM core.role_permission WHERE permission LIKE 'aip:suggestion:%'"))
                    .isEqualTo("6");
        }
    }
}
