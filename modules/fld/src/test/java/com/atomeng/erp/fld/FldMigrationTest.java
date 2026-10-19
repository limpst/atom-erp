package com.atomeng.erp.fld;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 05-IMPL Step 2 FldImmutabilityIT 대응 — 내장 PostgreSQL 16(Docker 없음), 합성 데이터만.
 * defect_event UPDATE · DELETE · TRUNCATE 거부(설계 RULE 대신 트리거 예외), measurement append-only, 검증 사진 불변 · 삭제 금지,
 * 구역 잠금 CHECK, op 로그 판정 CHECK, HLC 형식 CHECK, 측정 장비 CHECK.
 */
class FldMigrationTest {

    static String url;
    static final UUID ACTOR = UUID.randomUUID();
    static UUID round;
    static UUID zone;

    @BeforeAll
    static void migrate() throws Exception {
        url = EmbeddedPg.newDatabaseUrl("fld_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "fac", "eqp", "fld").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/fac", "classpath:db/migration/eqp",
                        "classpath:db/migration/fld")
                .load().migrate().success).isTrue();
        UUID client = UUID.randomUUID();
        UUID project = UUID.randomUUID();
        UUID site = UUID.randomUUID();
        UUID building = UUID.randomUUID();
        round = UUID.randomUUID();
        zone = UUID.randomUUID();
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                    + "('%s', 'CL-FLD', '합성고객', 'PRIVATE', '%s', '%s')").formatted(client, ACTOR, ACTOR));
            st.execute(("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) "
                    + "VALUES ('%s', 'PRJ-2026-9501', '합성 현장조사', 'PRECISE_DIAG', '%s', '%s', '%s')")
                    .formatted(project, client, ACTOR, ACTOR));
            st.execute("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES ('%s', '합성 단지', '%s', '%s')"
                    .formatted(site, ACTOR, ACTOR));
            st.execute(("INSERT INTO fac.building(id, site_id, name, created_by, updated_by) VALUES "
                    + "('%s', '%s', '합성 본관', '%s', '%s')").formatted(building, site, ACTOR, ACTOR));
            st.execute(("INSERT INTO fld.survey_round(id, project_id, building_id, round_no, survey_type, created_by) "
                    + "VALUES ('%s', '%s', '%s', 1, 'PRECISE_INSPECTION', '%s')").formatted(round, project, building, ACTOR));
            st.execute(("INSERT INTO fld.zone_assignment(id, round_id, zone_name, assignee_id) "
                    + "VALUES ('%s', '%s', 'B2F-A', '%s')").formatted(zone, round, ACTOR));
            st.execute(("INSERT INTO fld.defect_event(op_id, defect_id, round_id, zone_id, event_type, payload, hlc, "
                    + "device_id, actor_id) VALUES ('%s', '%s', '%s', '%s', 'DefectCreated', '{}', "
                    + "'0001728950400123:0000:d7f3', '%s', '%s')").formatted(UUID.randomUUID(), UUID.randomUUID(), round,
                    zone, UUID.randomUUID(), ACTOR));
        }
    }

    static Connection conn() throws SQLException {
        return DriverManager.getConnection(url, EmbeddedPg.user(), EmbeddedPg.password());
    }

    static void exec(String sql) throws SQLException {
        try (var c = conn(); var st = c.createStatement()) {
            st.execute(sql);
        }
    }

    @Test
    @DisplayName("Step 2 불변성: defect_event UPDATE · DELETE · TRUNCATE 는 예외(조용히 0건이 아니라 실패)")
    void eventStoreImmutable() {
        assertThatThrownBy(() -> exec("UPDATE fld.defect_event SET payload = '{\"x\":1}'"))
                .hasMessageContaining("FLD-409-001");
        assertThatThrownBy(() -> exec("DELETE FROM fld.defect_event")).hasMessageContaining("FLD-409-001");
        assertThatThrownBy(() -> exec("TRUNCATE fld.defect_event")).hasMessageContaining("FLD-409-001");
    }

    @Test
    @DisplayName("HLC 형식 · op_id UNIQUE(멱등 키) CHECK")
    void hlcAndOpId() throws Exception {
        assertThatThrownBy(() -> exec(("INSERT INTO fld.defect_event(op_id, defect_id, round_id, event_type, payload, hlc, "
                + "device_id, actor_id) VALUES ('%s', '%s', '%s', 'DefectCreated', '{}', '1728950400123:3:d7f3', '%s', '%s')")
                .formatted(UUID.randomUUID(), UUID.randomUUID(), round, UUID.randomUUID(), ACTOR)))
                .hasMessageContaining("defect_event_hlc_check");
        UUID op = UUID.randomUUID();
        String ins = ("INSERT INTO fld.defect_event(op_id, defect_id, round_id, event_type, payload, hlc, device_id, actor_id) "
                + "VALUES ('%s', '%s', '%s', 'DefectRemoved', '{}', '0001728950400999:0000:a', '%s', '%s')")
                .formatted(op, UUID.randomUUID(), round, UUID.randomUUID(), ACTOR);
        exec(ins);
        assertThatThrownBy(() -> exec(ins)).hasMessageContaining("defect_event_op_id_key");
    }

    @Test
    @DisplayName("측정값 append-only · 장비 방법(INSTRUMENT)은 equipment_id 필수 · 무효 교정은 잠정이어야 함")
    void measurementRules() throws Exception {
        String base = "INSERT INTO fld.measurement(id, op_id, seq, defect_id, round_id, item, value, unit, method, "
                + "equipment_id, measured_at, calibration_status, provisional, recorded_at) VALUES ('%s', '%s', 1, '%s', '%s', "
                + "'width_mm', 0.3, 'mm', '%s', %s, now(), '%s', %s, now())";
        assertThatThrownBy(() -> exec(base.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), round,
                "INSTRUMENT", "NULL", "NOT_APPLICABLE", "false"))).hasMessageContaining("ck_measure_equipment");
        assertThatThrownBy(() -> exec(base.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), round,
                "GAUGE", "'" + UUID.randomUUID() + "'", "INVALID", "false"))).hasMessageContaining("ck_measure_valid");
        UUID id = UUID.randomUUID();
        exec(base.formatted(id, UUID.randomUUID(), UUID.randomUUID(), round, "VISUAL", "NULL", "NOT_APPLICABLE", "false"));
        assertThatThrownBy(() -> exec("UPDATE fld.measurement SET value = 0.1 WHERE id = '" + id + "'"))
                .hasMessageContaining("FLD-409-001");
    }

    @Test
    @DisplayName("BR-FLD-002 · 010: 검증된 사진 원본 · 해시 변경 불가, 사진 행 삭제 불가")
    void photoGuard() throws Exception {
        UUID photo = UUID.randomUUID();
        String sha = "a".repeat(64);
        exec(("INSERT INTO fld.photo(id, round_id, device_sha256, device_id, created_at, created_by) "
                + "VALUES ('%s', '%s', '%s', '%s', now(), '%s')").formatted(photo, round, sha, UUID.randomUUID(), ACTOR));
        // VERIFIED 는 첨부 · 서버 해시(=기기 해시) · 체인 해시가 있어야 함
        assertThatThrownBy(() -> exec("UPDATE fld.photo SET upload_state = 'VERIFIED' WHERE id = '" + photo + "'"))
                .hasMessageContaining("ck_photo_verified");
        assertThatThrownBy(() -> exec("DELETE FROM fld.photo WHERE id = '" + photo + "'"))
                .hasMessageContaining("FLD-409-001");
        exec("UPDATE fld.photo SET upload_state = 'REJECTED', reject_count = 1 WHERE id = '" + photo + "'");
    }

    @Test
    @DisplayName("BR-FLD-009 구역 잠금 CHECK(LOCKED ⇔ locked_at · locked_by) · 잠금 해제 감사는 사유 5자 이상 · append-only")
    void zoneLockRules() throws Exception {
        assertThatThrownBy(() -> exec("UPDATE fld.zone_assignment SET status = 'LOCKED' WHERE id = '" + zone + "'"))
                .hasMessageContaining("ck_zone_lock");
        assertThatThrownBy(() -> exec(("INSERT INTO fld.zone_lock_audit(id, zone_id, action, reason, actor_id, at) "
                + "VALUES ('%s', '%s', 'UNLOCK', '짧음', '%s', now())").formatted(UUID.randomUUID(), zone, ACTOR)))
                .hasMessageContaining("ck_unlock_reason");
        UUID a = UUID.randomUUID();
        exec(("INSERT INTO fld.zone_lock_audit(id, zone_id, action, actor_id, at) VALUES ('%s', '%s', 'LOCK', '%s', now())")
                .formatted(a, zone, ACTOR));
        assertThatThrownBy(() -> exec("DELETE FROM fld.zone_lock_audit WHERE id = '" + a + "'"))
                .hasMessageContaining("FLD-409-001");
    }

    @Test
    @DisplayName("EQP V0014 측정 추적 append-only")
    void traceAppendOnly() {
        assertThatThrownBy(() -> exec("TRUNCATE eqp.measurement_trace")).hasMessageContaining("append-only");
    }
}
