package com.atomeng.erp.qar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 07-IMPL Step 1 QarMigrationTest — 내장 PostgreSQL 16(Docker 없음), 합성 데이터만 */
class QarMigrationTest {

    static JdbcTemplate jdbc;
    static final UUID ACTOR = UUID.randomUUID();
    static UUID project;
    static final UUID RULESET_2026 = UUID.fromString("0192f070-0000-7000-8000-000000002026");

    @BeforeAll
    static void migrate() {
        String url = EmbeddedPg.newDatabaseUrl("qar_mig");
        assertThat(Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas("core", "qar").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration/qar")
                .load().migrate().success).isTrue();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, EmbeddedPg.user(), EmbeddedPg.password()));
        UUID client = UUID.randomUUID();
        project = UUID.randomUUID();
        jdbc.update("INSERT INTO core.client(id, client_no, name, client_type, created_by, updated_by) VALUES "
                + "(?, 'CL-QAR', '합성고객', 'PRIVATE', ?, ?)", client, ACTOR, ACTOR);
        jdbc.update("INSERT INTO core.project(id, project_no, name, service_type, client_id, created_by, updated_by) VALUES "
                + "(?, 'PRJ-2026-9701', '합성 QAR 프로젝트', 'PRECISE_DIAG', ?, ?, ?)", project, client, ACTOR, ACTOR);
    }

    /** 검토 1건 + 라운드 1 → round id */
    static UUID round() {
        UUID review = UUID.randomUUID();
        UUID round = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qar.review(id, report_id, project_id, report_type, workflow_def_id, status, created_by)
                VALUES (?, ?, ?, 'RT_PRC', '0192f070-0000-7000-8000-000000000002', 'SELF_CHECK', ?)""",
                review, UUID.randomUUID(), project, ACTOR);
        jdbc.update("INSERT INTO qar.review_round(id, review_id, round_no, revision_id, rev_no) VALUES (?, ?, 1, ?, 1)",
                round, review, UUID.randomUUID());
        return round;
    }

    static UUID result(UUID round, String severity) {
        UUID run = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qar.autocheck_run(id, round_id, rule_set_id, rule_version, as_of, content_hash, blocking_count,
                                              warning_count, info_count, duration_ms, ran_by)
                VALUES (?, ?, ?, '2026.1', DATE '2026-10-19', ?, 0, 0, 0, 1, ?)""", run, round, RULESET_2026,
                "c".repeat(64), ACTOR);
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO qar.autocheck_result(id, round_id, run_id, rule_id, severity, message) "
                + "VALUES (?, ?, ?, 'AC-TXT-002', ?, '합성 결과')", id, round, run, severity);
        return id;
    }

    @Test
    @DisplayName("Step 1: qar 테이블 18개 · 워크플로우 2종 · 규칙 세트 2개 × 규칙 15종 · 체크리스트 10문항 · 세부 권한")
    void schemaAndSeeds() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'qar' "
                + "AND table_type = 'BASE TABLE'", Long.class)).isEqualTo(18L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qar.workflow_def WHERE status = 'DEPLOYED'", Long.class))
                .isEqualTo(2L);
        assertThat(jdbc.queryForList("SELECT rs.version || ':' || count(d.*) FROM qar.autocheck_rule_set rs "
                + "JOIN qar.autocheck_rule_def d ON d.rule_set_id = rs.id GROUP BY rs.version ORDER BY rs.version",
                String.class)).containsExactly("2025.1:15", "2026.1:15");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qar.autocheck_rule_def WHERE rule_id = 'AC-HRQ-002'",
                Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qar.checklist_item", Long.class)).isEqualTo(10L);
        assertThat(jdbc.queryForObject("SELECT scope FROM core.role_permission WHERE role = 'PM' "
                + "AND permission = 'qar:approval:approve'", String.class)).isEqualTo("PROJECT");
        assertThat(jdbc.queryForList("SELECT role FROM core.role_permission WHERE permission = 'qar:suggestion:decide'",
                String.class)).containsExactly("QA");
    }

    @Test
    @DisplayName("BR-QAR-007: DEPLOYED 규칙 세트 유효기간 겹침 · 같은 유형 체크리스트 겹침은 EXCLUDE 위반")
    void effectiveDatedExclusion() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO qar.autocheck_rule_set(id, version, effective_from, status, created_by)
                VALUES (?, '2026.2', DATE '2026-06-01', 'DEPLOYED', ?)""", UUID.randomUUID(), ACTOR))
                .hasMessageContaining("ex_rs_overlap");
        jdbc.update("INSERT INTO qar.autocheck_rule_set(id, version, effective_from, status, created_by) "
                + "VALUES (?, '2027.1-draft', DATE '2027-01-01', 'DRAFT', ?)", UUID.randomUUID(), ACTOR);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO qar.checklist_template(id, report_type, version, effective_from, status)
                VALUES (?, 'RT_COMMON', '2026.9', DATE '2026-01-01', 'DEPLOYED')""", UUID.randomUUID()))
                .hasMessageContaining("ex_ct_overlap");
    }

    @Test
    @DisplayName("TC-QAR-013: comment_reply · autocheck_result 행 UPDATE/DELETE/TRUNCATE(DB 직접) → QAR_APPEND_ONLY, 행 불변")
    void tcQar013AppendOnly() {
        UUID round = round();
        UUID comment = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qar.review_comment(id, round_id, origin_round_no, author_id, severity, issue_code, anchor, body,
                                               status, created_by)
                VALUES (?, ?, 1, ?, 'MAJOR', 'QI-NUM', '{"type":"SECTION","sectionKey":"ch7.opinion"}', '합성 코멘트', 'OPEN', ?)""",
                comment, round, ACTOR, ACTOR);
        UUID reply = UUID.randomUUID();
        jdbc.update("INSERT INTO qar.comment_reply(id, comment_id, author_id, body, action, from_status, to_status) "
                + "VALUES (?, ?, ?, '원 답변', 'ANSWER', 'OPEN', 'ANSWERED')", reply, comment, ACTOR);
        assertThatThrownBy(() -> jdbc.update("UPDATE qar.comment_reply SET body = '변조' WHERE id = ?", reply))
                .hasMessageContaining("QAR_APPEND_ONLY");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM qar.comment_reply WHERE id = ?", reply))
                .hasMessageContaining("QAR_APPEND_ONLY");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE qar.comment_reply CASCADE")).hasMessageContaining("QAR_APPEND_ONLY");
        assertThat(jdbc.queryForObject("SELECT body FROM qar.comment_reply WHERE id = ?", String.class, reply))
                .isEqualTo("원 답변");

        UUID res = result(round, "BLOCKING");
        assertThatThrownBy(() -> jdbc.update("UPDATE qar.autocheck_result SET severity = 'INFO' WHERE id = ?", res))
                .hasMessageContaining("QAR_APPEND_ONLY");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM qar.autocheck_result WHERE id = ?", res))
                .hasMessageContaining("QAR_APPEND_ONLY");
        assertThat(jdbc.queryForObject("SELECT severity FROM qar.autocheck_result WHERE id = ?", String.class, res))
                .isEqualTo("BLOCKING");
    }

    @Test
    @DisplayName("TC-QAR-009(DB): BLOCKING · INFO 결과 waiver INSERT → QAR_WAIVER_NOT_ALLOWED(트리거), WARNING 은 PM · QA 다른 사람만 GRANTED")
    void tcQar009WaiverTrigger() {
        UUID round = round();
        for (String sev : new String[] {"BLOCKING", "INFO"}) {
            UUID r = result(round, sev);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO qar.waiver(id, autocheck_result_id, pm_id, pm_reason, status) "
                    + "VALUES (?, ?, ?, '사유', 'PENDING')", UUID.randomUUID(), r, ACTOR))
                    .hasMessageContaining("QAR_WAIVER_NOT_ALLOWED");
        }
        UUID warn = result(round, "WARNING");
        UUID same = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO qar.waiver(id, autocheck_result_id, pm_id, pm_reason, qa_id, qa_reason, status)
                VALUES (?, ?, ?, '사유', ?, '사유', 'GRANTED')""", UUID.randomUUID(), warn, same, same))
                .hasMessageContaining("ck_waiver_people");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO qar.waiver(id, autocheck_result_id, pm_id, pm_reason, status) "
                + "VALUES (?, ?, ?, '사유', 'GRANTED')", UUID.randomUUID(), warn, ACTOR))
                .hasMessageContaining("ck_waiver_granted");
        jdbc.update("""
                INSERT INTO qar.waiver(id, autocheck_result_id, pm_id, pm_reason, qa_id, qa_reason, status)
                VALUES (?, ?, ?, 'PM 사유', ?, 'QA 사유', 'GRANTED')""", UUID.randomUUID(), warn, UUID.randomUUID(),
                UUID.randomUUID());
    }

    @Test
    @DisplayName("ai_review_job 상태 5종 · deadline_at 필수, ai_finding 은 guard 통과분만 결정 가능 · 기각 사유 필수")
    void aiJobAndFindingConstraints() {
        UUID round = round();
        UUID job = UUID.randomUUID();
        jdbc.update("INSERT INTO qar.ai_review_job(id, round_id, status, requested_by, deadline_at) "
                + "VALUES (?, ?, 'QUEUED', 'AUTO', now() + interval '10 minutes')", job, round);
        assertThatThrownBy(() -> jdbc.update("UPDATE qar.ai_review_job SET status = 'LOST' WHERE id = ?", job))
                .hasMessageContaining("ai_review_job_status_check");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO qar.ai_review_job(id, round_id, status, requested_by) "
                + "VALUES (?, ?, 'QUEUED', 'AUTO')", UUID.randomUUID(), round)).hasMessageContaining("deadline_at");
        UUID hidden = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO qar.ai_finding(id, round_id, job_id, category, severity_suggested, statement, citations,
                                           guard_passed, guard_reason, model, prompt_hash)
                VALUES (?, ?, ?, 'LOGIC_GAP', 'MINOR', '합성', '[]', false, 'NO_CITATION', 'stub', ?)""",
                hidden, round, job, "h".repeat(64));
        assertThatThrownBy(() -> jdbc.update("UPDATE qar.ai_finding SET decision = 'ADOPTED' WHERE id = ?", hidden))
                .hasMessageContaining("ck_ai_decision_visible");
    }
}
