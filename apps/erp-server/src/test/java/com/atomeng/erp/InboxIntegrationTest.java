package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 웹 "내 할 일" 수신함 읽기 API(4차) — 모듈마다 자기 몫만, 실패하는 쪽으로 닫힘(호출자가 실제로 처리할 수 있는 항목만).
 * HRQ: RESP 프로젝트 · 본인 제외 / SAL: 작성 · 발행자 본인 제외 / CST: 결재 가능 상태 · 작성 · 수정자 본인 제외 / QAR: 나에게 배정 · 범위.
 * 모두 합성 데이터.
 */
class InboxIntegrationTest extends IntegrationTestBase {

    UUID admin;
    LocalDate today;

    @BeforeEach
    void setUp() {
        clock.reset();
        admin = actor();
        today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    private JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String roles, int expect) throws Exception {
        MvcResult r = mvc.perform(as(b, actor, roles)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as("%s → %s", r.getRequest().getRequestURI(), body).isEqualTo(expect);
        return body.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1] instanceof UUID u ? u.toString() : kv[i + 1]);
        }
        return m;
    }

    private JsonNode post(String url, Object body, UUID actor, String roles, int expect, Long ifMatch) throws Exception {
        MockHttpServletRequestBuilder b = postJson(url, objectMapper.writeValueAsString(body))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (ifMatch != null) {
            b.header("If-Match", IfMatch.etagOf(ifMatch));
        }
        return call(b, actor, roles, expect);
    }

    private UUID employee(String empNo) throws Exception {
        UUID id = UUID.randomUUID();
        call(postJson("/api/v1/hrq/employees", """
                {"id":"%s","empNo":"%s","name":"합성 %s","field":"ARCH","employmentType":"FULLTIME",
                 "registeredField":true,"hiredOn":"2020-03-02","initialGrade":"INTER","gradeFrom":"2020-03-02"}"""
                .formatted(id, empNo, empNo)), admin, "ADMIN", 201);
        return id;
    }

    private UUID project(UUID pm, String name) throws Exception {
        UUID client = createClient(actor(), "합성 수신함 고객 " + UUID.randomUUID());
        return UUID.fromString(post("/api/v1/core/projects", map("name", name, "serviceType", "PRECISE_DIAG",
                "clientId", client), pm, "PM", 201, null).get("id").asText());
    }

    private void member(UUID project, UUID emp, String role) throws Exception {
        post("/api/v1/core/projects/" + project + "/members", map("employeeId", emp, "roleInProject", role,
                "fromDate", today.minusMonths(1).toString(), "toDate", today.plusMonths(3).toString()), actor(), "EXEC",
                201, null);
    }

    private void submit(UUID emp, UUID project, double hours) throws Exception {
        String week = "%d-W%02d".formatted(today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        call(put("/api/v1/hrq/timesheets/" + emp + "/weeks/" + week).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(map("submit", true, "entries", List.of(map("projectId", project,
                        "workDate", today.toString(), "workType", "FIELD", "hours", hours))))), emp, "ENG", 200);
    }

    @Test
    @DisplayName("HRQ GET /inbox/timesheet-approvals — 그 프로젝트 RESP 만 보고 PART · 다른 프로젝트 PM 은 빈 목록, 본인 것 제외, 권한 없음 403")
    void hrqTimesheetInbox() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        UUID resp = employee("IBR-" + s);
        UUID part = employee("IBP-" + s);
        UUID eng = employee("IBE-" + s);
        UUID p = project(resp, "합성 수신함 HRQ " + s);
        member(p, resp, "RESP");
        member(p, part, "PART");
        member(p, eng, "PART");
        submit(eng, p, 7.5);
        submit(resp, p, 4);   // 책임자 본인 타임시트 — 본인 수신함에는 안 나온다

        JsonNode mine = call(get("/api/v1/hrq/inbox/timesheet-approvals"), resp, "PM", 200);
        assertThat(mine.findValuesAsText("employeeId")).containsExactly(eng.toString());
        JsonNode row = mine.get(0);
        assertThat(row.get("projectId").asText()).isEqualTo(p.toString());
        assertThat(row.get("entries").asInt()).isEqualTo(1);
        assertThat(row.get("hours").decimalValue()).isEqualByComparingTo("7.5");
        assertThat(row.get("isoWeek").asText()).matches("\\d{4}-W\\d{2}");
        assertThat(row.get("employeeName").asText()).isEqualTo("합성 IBE-" + s);
        // PART(같은 프로젝트) · 투입 안 된 PM → 빈 목록(실패하는 쪽으로 닫힘)
        assertThat(call(get("/api/v1/hrq/inbox/timesheet-approvals"), part, "PM", 200)).isEmpty();
        assertThat(call(get("/api/v1/hrq/inbox/timesheet-approvals"), actor(), "PM", 200)).isEmpty();
        assertThat(call(get("/api/v1/hrq/inbox/timesheet-approvals"), eng, "ENG", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }

    @Test
    @DisplayName("SAL GET /inbox/discount-approvals — 승인 대기 견적, 작성자(SALES+EXEC 겸직)는 자기 견적이 안 보임, SALES 403")
    void salDiscountInbox() throws Exception {
        UUID author = actor();
        UUID exec = actor();
        for (UUID e : List.of(author, exec)) {
            jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                    + "VALUES (?, ?, ?, DATE '2024-01-01', ?, ?)", e, "IB-" + e.toString().substring(0, 12), "합성", e, e);
        }
        UUID client = createClient(author, "합성 수신함 SAL " + UUID.randomUUID());
        JsonNode o = post("/api/v1/sal/opportunities", map("source", "INQUIRY", "clientId", client, "serviceType",
                "PRECISE_DIAG", "title", "합성 수신함 할인", "ownerId", author), author, "SALES", 201, null);
        UUID opp = UUID.fromString(o.get("id").asText());
        long v = post("/api/v1/sal/opportunities/" + opp + ":move", map("toStage", "QUALIFYING"), author, "SALES", 200,
                o.get("version").asLong()).get("version").asLong();
        post("/api/v1/sal/opportunities/" + opp + ":move", map("toStage", "PROPOSING"), author, "SALES", 200, v);
        JsonNode est = post("/api/v1/sal/opportunities/" + opp + "/estimates", map("asOfDate", "2026-10-15",
                "serviceType", "PRECISE_DIAG", "facility", map("facilityClass", "BUILDING", "grossAreaM2", 18420,
                        "structure", "RC", "basementFloors", 1, "floors", 8, "completionYear", 1994, "inUse", false),
                "discountRate", new BigDecimal("0.08")), author, "SALES", 201, null);
        String estId = est.get("id").asText();
        assertThat(post("/api/v1/sal/estimates/" + estId + ":issue", Map.of(), author, "SALES", 200, null)
                .get("status").asText()).isEqualTo("PENDING_APPROVAL");

        JsonNode list = call(get("/api/v1/sal/inbox/discount-approvals"), exec, "EXEC", 200);
        assertThat(list.findValuesAsText("estimateId")).contains(estId);
        JsonNode row = null;
        for (JsonNode r : list) {
            if (r.get("estimateId").asText().equals(estId)) {
                row = r;
            }
        }
        assertThat(row.get("opportunityTitle").asText()).isEqualTo("합성 수신함 할인");
        assertThat(row.get("discountRate").decimalValue()).isEqualByComparingTo("0.08");
        assertThat(row.get("createdBy").asText()).isEqualTo(author.toString());
        // SAL-06: 작성자가 EXEC 역할을 함께 가져도 자기 견적은 수신함에 없다
        assertThat(call(get("/api/v1/sal/inbox/discount-approvals"), author, "SALES,EXEC", 200)
                .findValuesAsText("estimateId")).doesNotContain(estId);
        assertThat(call(get("/api/v1/sal/inbox/discount-approvals"), author, "SALES", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }

    @Test
    @DisplayName("CST GET /inbox/budget-approvals — 결재 가능 상태(SUBMITTED) · 라인 있는 판만, 작성 · 수정 · 상신자 본인 제외, PM 403")
    void cstBudgetInbox() throws Exception {
        UUID pm = actor();
        UUID p = project(pm, "합성 수신함 CST " + UUID.randomUUID().toString().substring(0, 6));
        UUID author = actor();
        UUID wbs = UUID.randomUUID();
        jdbc.update("INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, sort_order) VALUES (?, ?, 'IB-10', "
                + "'합성 현장', 100, 1)", wbs, p);
        UUID withLines = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID approved = UUID.randomUUID();
        jdbc.update("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, created_by) "
                + "VALUES (?, ?, 0, 'APPROVED', 1000000, 2000000, ?)", approved, p, author);
        jdbc.update("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, created_by) "
                + "VALUES (?, ?, 1, 'SUBMITTED', 1200000, 2000000, ?)", withLines, p, author);
        jdbc.update("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, created_by) "
                + "VALUES (?, ?, 2, 'SUBMITTED', 0, 2000000, ?)", empty, p, author);
        // CST-01: 상신(SUBMITTED) 전 DRAFT 는 결재 대상이 아니다
        UUID draft = UUID.randomUUID();
        jdbc.update("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, created_by) "
                + "VALUES (?, ?, 3, 'DRAFT', 1200000, 2000000, ?)", draft, p, author);
        jdbc.update("INSERT INTO cst.budget_line(id, revision_id, wbs_id, cost_element, qty, unit_cost_krw) "
                + "VALUES (?, ?, ?, 'LABOR', 4, 300000)", UUID.randomUUID(), draft, wbs);
        jdbc.update("INSERT INTO cst.budget_line(id, revision_id, wbs_id, cost_element, qty, unit_cost_krw) "
                + "VALUES (?, ?, ?, 'LABOR', 4, 300000)", UUID.randomUUID(), withLines, wbs);

        JsonNode list = call(get("/api/v1/cst/inbox/budget-approvals"), actor(), "EXEC", 200);
        assertThat(list.findValuesAsText("revisionId")).contains(withLines.toString())
                .doesNotContain(empty.toString(), approved.toString(), draft.toString());
        // 상신자 본인에게도 안 보인다(CST-01 maker-checker)
        UUID submitter = actor();
        jdbc.update("UPDATE cst.budget_revision SET submitted_by = ?, submitted_at = now() WHERE id = ?", submitter, withLines);
        assertThat(call(get("/api/v1/cst/inbox/budget-approvals"), submitter, "EXEC", 200).findValuesAsText("revisionId"))
                .doesNotContain(withLines.toString());
        // maker-checker: 작성자(ADMIN 겸) 본인 · 마지막 수정자 본인에게는 안 보인다
        assertThat(call(get("/api/v1/cst/inbox/budget-approvals"), author, "ADMIN", 200).findValuesAsText("revisionId"))
                .doesNotContain(withLines.toString());
        UUID editor = actor();
        jdbc.update("UPDATE cst.budget_revision SET updated_by = ?, updated_at = now() WHERE id = ?", editor, withLines);
        assertThat(call(get("/api/v1/cst/inbox/budget-approvals"), editor, "FIN", 200).findValuesAsText("revisionId"))
                .doesNotContain(withLines.toString());
        assertThat(call(get("/api/v1/cst/inbox/budget-approvals"), pm, "PM", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }

    @Test
    @DisplayName("QAR GET /inbox/assignments — 나에게 배정된 열린 단계만, 끝난 배정 · 다른 사람 배정 제외, PROJECT 범위 PM 은 투입 프로젝트만")
    void qarAssignmentInbox() throws Exception {
        UUID pm = actor();
        UUID qa = actor();
        UUID p = project(pm, "합성 수신함 QAR " + UUID.randomUUID().toString().substring(0, 6));
        UUID wf = jdbc.queryForObject("SELECT id FROM qar.workflow_def WHERE code = 'WF_REG_BASIC'", UUID.class);
        UUID review = UUID.randomUUID();
        UUID round = UUID.randomUUID();
        UUID report = UUID.randomUUID();
        jdbc.update("INSERT INTO qar.review(id, report_id, project_id, report_type, workflow_def_id, status, created_by) "
                + "VALUES (?, ?, ?, 'RT_REG', ?, 'QA_REVIEWING', ?)", review, report, p, wf, pm);
        jdbc.update("INSERT INTO qar.review_round(id, review_id, round_no, revision_id, rev_no) VALUES (?, ?, 1, ?, 1)",
                round, review, UUID.randomUUID());
        UUID open = UUID.randomUUID();
        jdbc.update("INSERT INTO qar.review_assignment(id, round_id, step_code, assignee_id, assigned_by) "
                + "VALUES (?, ?, 'QA_REVIEW', ?, 'MANUAL')", open, round, qa);
        jdbc.update("INSERT INTO qar.review_assignment(id, round_id, step_code, assignee_id, assigned_by, completed_at, decision) "
                + "VALUES (?, ?, 'PEER_REVIEW', ?, 'MANUAL', now(), 'PASS')", UUID.randomUUID(), round, qa);
        UUID pmStep = UUID.randomUUID();
        jdbc.update("INSERT INTO qar.review_assignment(id, round_id, step_code, assignee_id, assigned_by) "
                + "VALUES (?, ?, 'FINAL_APPROVAL', ?, 'MANUAL')", pmStep, round, pm);

        JsonNode mine = call(get("/api/v1/qar/inbox/assignments"), qa, "QA", 200);
        assertThat(mine.findValuesAsText("assignmentId")).containsExactly(open.toString());
        assertThat(mine.get(0).get("reportId").asText()).isEqualTo(report.toString());
        assertThat(mine.get(0).get("stepCode").asText()).isEqualTo("QA_REVIEW");
        // PM(PROJECT 범위): 투입되지 않았으면 배정이 있어도 보이지 않는다 → 투입 후 보인다
        assertThat(call(get("/api/v1/qar/inbox/assignments"), pm, "PM", 200)).isEmpty();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성 PM', DATE '2024-01-01', ?, ?) ON CONFLICT DO NOTHING", pm,
                "IBQ-" + pm.toString().substring(0, 12), pm, pm);
        member(p, pm, "RESP");
        assertThat(call(get("/api/v1/qar/inbox/assignments"), pm, "PM", 200).findValuesAsText("assignmentId"))
                .containsExactly(pmStep.toString());
        assertThat(call(get("/api/v1/qar/inbox/assignments"), actor(), "SALES", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }
}
