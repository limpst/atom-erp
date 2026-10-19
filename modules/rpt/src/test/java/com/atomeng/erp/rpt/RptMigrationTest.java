package com.atomeng.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.application.RuleTableService;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.grade.RuleTables;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 06-IMPL Step 1 · 2 RptMigrationTest — 내장 PostgreSQL 16(Docker 없음), 합성 데이터만 */
class RptMigrationTest {

    static JdbcTemplate jdbc;
    static final UUID ACTOR = UUID.randomUUID();
    static UUID project;
    static final UUID TEMPLATE_VERSION = UUID.fromString("0192e000-0000-7000-8000-000000002026");

    @BeforeAll
    static void migrate() {
        String url = EmbeddedPg.newDatabaseUrl("rpt_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "rpt").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/rpt")
                .load().migrate().success).isTrue();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, EmbeddedPg.user(), EmbeddedPg.password()));
        UUID client = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                + "(?, 'CL-RPT', '합성고객', 'PRIVATE', ?, ?)", client, ACTOR, ACTOR);
        jdbc.update("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) VALUES "
                + "(?, 'PRJ-2026-9601', '합성 RPT 프로젝트', 'PRECISE_DIAG', ?, ?, ?)", project, client, ACTOR, ACTOR);
    }

    static UUID report() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rpt.report(id, project_id, facility_id, report_type, template_version_id, title, inspection_date,
                                       status, created_by) VALUES (?, ?, ?, 'RT_PRC', ?, '합성 보고서', DATE '2026-09-30', 'DRAFT', ?)""",
                id, project, UUID.randomUUID(), TEMPLATE_VERSION, ACTOR);
        return id;
    }

    static UUID revision(UUID report, int revNo, String status) {
        UUID id = UUID.randomUUID();
        boolean frozen = status.equals("PUBLISHED") || status.equals("DELIVERED");
        jdbc.update("""
                INSERT INTO rpt.report_revision(id, report_id, rev_no, status, pdf_sha256, published_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, report, revNo, status, frozen ? "a".repeat(64) : null,
                frozen ? java.sql.Timestamp.from(java.time.Instant.now()) : null, ACTOR);
        return id;
    }

    @Test
    @DisplayName("Step 1: rpt 테이블 17개 · 템플릿 1종 · rule 6세트(3종 × 2025.1/2026.1) · 세부 권한 시드")
    void schemaAndSeeds() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'rpt' "
                + "AND table_type = 'BASE TABLE'", Long.class)).isEqualTo(18L);   // 17 + V0022 field_survey_completion
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rpt.rule_set WHERE status = 'DEPLOYED'", Long.class))
                .isEqualTo(6L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rpt.template_version WHERE status = 'DEPLOYED'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForList("SELECT role FROM core.role_permission WHERE permission = 'rpt:delivery:confirm' "
                + "ORDER BY role", String.class)).containsExactly("ADMIN", "PM");
        assertThat(jdbc.queryForObject("SELECT scope FROM core.role_permission WHERE role = 'PM' "
                + "AND permission = 'rpt:grade:decide'", String.class)).isEqualTo("PROJECT");
    }

    @Test
    @DisplayName("Step 2: 시드 rule 본문(DB 조립) = 골든 픽스처 JSON — 2025.1 · 2026.1, 기준일 해석")
    void seedEqualsFixture() throws Exception {
        RuleTableService svc = new RuleTableService(new RptJdbc(jdbc));
        for (String v : new String[] {"2025.1", "2026.1"}) {
            try (InputStream in = getClass().getResourceAsStream("/golden/rules/BLD_RC-" + v + ".json")) {
                RuleTables fixture = RuleTables.parse(RptJson.MAPPER.readTree(in));
                LocalDate asOf = v.equals("2025.1") ? LocalDate.of(2025, 7, 1) : LocalDate.of(2026, 7, 1);
                RuleTableService.Resolved r = svc.resolve("BLD_RC", asOf);
                assertThat(r.tables()).isEqualTo(fixture);
                assertThat(r.sets()).allMatch(s -> s.version().equals(v));
                assertThat(svc.load(RptJson.tree(fixture.pinned())).tables()).isEqualTo(fixture);
            }
        }
        assertThat(svc.resolve("BLD_RC", LocalDate.of(2025, 12, 31)).tables().versions().get("GRADE_BAND")).isEqualTo("2025.1");
        assertThat(svc.resolve("BLD_RC", LocalDate.of(2026, 1, 1)).tables().versions().get("GRADE_BAND")).isEqualTo("2026.1");
        assertThatThrownBy(() -> svc.resolve("BLD_RC", LocalDate.of(2024, 12, 31))).hasMessageContaining("rule table");
    }

    @Test
    @DisplayName("Step 1: 같은 종류 · 분류의 DEPLOYED rule 유효기간 겹침 → RPT-409-011 (EXCLUDE 대체 트리거)")
    void ruleOverlap() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rpt.rule_set(id, rule_kind, facility_class, version, effective_from, status, created_by)
                VALUES (?, 'COND_RATING', 'BLD_RC', '2026.9', DATE '2026-06-01', 'DEPLOYED', ?)""", UUID.randomUUID(), ACTOR))
                .hasMessageContaining("RPT-409-011");
        // DRAFT 는 겹쳐도 됨, 다른 분류도 됨
        jdbc.update("""
                INSERT INTO rpt.rule_set(id, rule_kind, facility_class, version, effective_from, status, created_by)
                VALUES (?, 'COND_RATING', 'BLD_RC', '2026.9', DATE '2026-06-01', 'DRAFT', ?)""", UUID.randomUUID(), ACTOR);
        jdbc.update("""
                INSERT INTO rpt.rule_set(id, rule_kind, facility_class, version, effective_from, status, created_by)
                VALUES (?, 'COND_RATING', 'BLD_STEEL', '2026.1', DATE '2026-01-01', 'DEPLOYED', ?)""", UUID.randomUUID(), ACTOR);
    }

    @Test
    @DisplayName("TC-RPT-007(DB): 발행 리비전 pdf_sha256 수정 · 삭제 · TRUNCATE → RPT-409-010, PUBLISHED→DELIVERED→SUPERSEDED 만 허용")
    void publishedRevisionImmutable() {
        UUID r = report();
        UUID pub = revision(r, 1, "PUBLISHED");
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.report_revision SET pdf_sha256 = ? WHERE id = ?", "b".repeat(64), pub))
                .hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM rpt.report_revision WHERE id = ?", pub))
                .hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.report_revision SET status = 'COMPOSING' WHERE id = ?", pub))
                .hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE rpt.report_revision CASCADE")).hasMessageContaining("RPT-409-010");
        jdbc.update("UPDATE rpt.report_revision SET status = 'DELIVERED', delivered_at = now() WHERE id = ?", pub);
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.report_revision SET delivered_at = now() - interval '1 day' "
                + "WHERE id = ?", pub)).hasMessageContaining("RPT-409-010");
        UUID rev2 = revision(r, 2, "DRAFT");
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.report_revision SET status = 'SUPERSEDED' WHERE id = ?", pub))
                .hasMessageContaining("ck_rev_superseded");   // superseded_by 없이 SUPERSEDED 불가
        jdbc.update("UPDATE rpt.report_revision SET status = 'SUPERSEDED', superseded_by = ?, superseded_at = now() "
                + "WHERE id = ?", rev2, pub);
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.report_revision SET superseded_by = NULL WHERE id = ?", pub))
                .hasMessageContaining("RPT-409-010");
        // 초안 리비전은 자유롭게 수정 · 삭제
        jdbc.update("UPDATE rpt.report_revision SET change_note = '정정' WHERE id = ?", rev2);
    }

    @Test
    @DisplayName("BR-RPT-008: 발행 리비전의 섹션 · 블록 · 등급 · 앵커 추가/수정 거부, 스냅샷 · 편집 이력 · 납품 확인 append-only")
    void childRowsImmutable() {
        UUID r = report();
        UUID draft = revision(r, 1, "COMPOSING");
        UUID sec = UUID.randomUUID();
        jdbc.update("INSERT INTO rpt.section(id, revision_id, section_key, order_no, title, source, created_by) "
                + "VALUES (?, ?, 'ch1', 1, '개요', 'AUTO', ?)", sec, draft, ACTOR);
        UUID gid = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rpt.grade_result(id, revision_id, rule_set_versions, scope_tree, weighted_score, computed_grade,
                                             result_sha256) VALUES (?, ?, '{}', '{}', 2.5, 'C', ?)""", gid, draft, "c".repeat(64));
        // BR-RPT-005: 산정과 다른 확정은 사유 100자 + 근거 필요
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.grade_result SET final_grade = 'B', override_reason = '짧음' "
                + "WHERE id = ?", gid)).hasMessageContaining("ck_override");
        jdbc.update("UPDATE rpt.grade_result SET final_grade = 'C' WHERE id = ?", gid);
        jdbc.update("UPDATE rpt.report_revision SET status = 'PUBLISHED', pdf_sha256 = ?, published_at = now() WHERE id = ?",
                "d".repeat(64), draft);
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.section SET title = 'x' WHERE id = ?", sec))
                .hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rpt.section(id, revision_id, section_key, order_no, title, source, "
                + "created_by) VALUES (?, ?, 'ch2', 2, '추가', 'HUMAN', ?)", UUID.randomUUID(), draft, ACTOR))
                .hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.grade_result SET final_grade = 'D' WHERE id = ?", gid))
                .hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO rpt.member_rating(id, grade_result_id, building_code, floor_code, member_type, member_count,
                                              rating, score, weight, evidence) VALUES (?, ?, 'B01', '1F', 'COLUMN', 1, 'a', 1, 0.25, '[]')""",
                UUID.randomUUID(), gid)).hasMessageContaining("RPT-409-010");
        UUID dc = UUID.randomUUID();
        jdbc.update("INSERT INTO rpt.delivery_confirmation(id, revision_id, delivered_at, recipient_name, created_by) "
                + "VALUES (?, ?, now(), '합성 담당자', ?)", dc, draft, ACTOR);
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.delivery_confirmation SET recipient_name = 'x' WHERE id = ?", dc))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO rpt.delivery_confirmation(id, revision_id, delivered_at, "
                + "recipient_name, created_by) VALUES (?, ?, now(), '두번째', ?)", UUID.randomUUID(), draft, ACTOR))
                .hasMessageContaining("duplicate key");
        jdbc.update("INSERT INTO rpt.block_edit(revision_id, section_key, block_id, action, source, editor_id) "
                + "VALUES (?, 'ch1', ?, 'CREATE', 'AUTO', ?)", draft, UUID.randomUUID(), ACTOR);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM rpt.block_edit WHERE revision_id = ?", draft))
                .hasMessageContaining("append-only");
    }
}
