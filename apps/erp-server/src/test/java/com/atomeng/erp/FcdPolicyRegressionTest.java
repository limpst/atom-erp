package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.fcd.application.CaseService;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 보안 검토 2026-10-19 §2 FCD 정책 결정(권장안) 회귀 시험 — FCD-10 · 11 · 12 · 13 · 14 · 17 · 18.
 * 다른 FCD 시험과 겹치지 않게 거래는 2026-09(검토 큐는 실행하지 않음), 검토 큐 실행은 거래 없는 2031년 달을 쓴다.
 * 카드번호 · 가맹점 · 금액 · 사람은 전부 합성.
 */
class FcdPolicyRegressionTest extends IntegrationTestBase {

    UUID fin;
    UUID fin2;
    UUID auditor;
    UUID exec;

    @BeforeEach
    void setUp() {
        fin = actor();
        fin2 = actor();
        auditor = actor();
        exec = actor();
        clock.reset();
    }

    // ------------------------------------------------------------------ helpers

    private UUID card(String pan, UUID holder) throws Exception {
        String body = holder == null
                ? """
                {"cardCompany":"ACARD","pan":"%s","cardType":"SHARED","dept":"진단1팀","limitPerTxn":300000,
                 "limitDaily":450000,"limitMonthly":5000000}""".formatted(pan)
                : """
                {"cardCompany":"ACARD","pan":"%s","cardType":"PERSONAL_ASSIGNED","holderEmployeeId":"%s","dept":"진단1팀",
                 "limitPerTxn":300000,"limitDaily":450000,"limitMonthly":5000000}""".formatted(pan, holder);
        MvcResult r = mvc.perform(as(postJson("/api/v1/fcd/cards", body), fin, "FIN")).andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private String upload(byte[] data, String name) throws Exception {
        MvcResult r = mvc.perform(as(multipart("/api/v1/fcd/imports").file(new MockMultipartFile("file", name,
                "application/octet-stream", data)), fin, "FIN")).andExpect(status().isCreated()).andReturn();
        String id = json(r).get("id").asText();
        mvc.perform(as(postJson("/api/v1/fcd/imports/" + id + ":commit", "{}"), fin, "FIN")).andExpect(status().isOk());
        return id;
    }

    private List<Map<String, Object>> txns(UUID card) {
        return jdbc.queryForList("SELECT * FROM fcd.txn WHERE card_id = ? AND removed_at IS NULL ORDER BY approved_at, seq",
                card);
    }

    private UUID project() throws Exception {
        UUID client = createClient(actor(), "합성발주처-fcdpol-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 FCD 정책 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), actor(), "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private void member(UUID emp, UUID prj, String from, String to) {
        member(emp, prj, "PART", from, to);
    }

    private void member(UUID emp, UUID prj, String role, String from, String to) {
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성직원', DATE '2020-01-01', ?, ?) ON CONFLICT (id) DO NOTHING",
                emp, "FP-" + emp.toString().substring(24), emp, emp);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, ?, ?::date, ?::date)", prj, emp, role, from, to);
    }

    /** 판정 · 조사 상태 케이스를 직접 만든다(검토 큐 실행 없이 — 월 전체 거래에 영향 주지 않게) */
    private UUID caseFor(UUID txn, UUID subject, String status, UUID resolvedBy, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fcd.fcd_case(id, case_no, status, grade, score, subject_employee_id, opened_reason, due_on, label,
                                         resolved_by, resolved_at)
                VALUES (?, ?, ?, 'HIGH', 85, ?, ?, DATE '2026-10-30', ?, ?, CASE WHEN ?::text IS NULL THEN NULL ELSE now() END)""",
                id, "FCD-T-" + id.toString().substring(24), status, subject, reason,
                resolvedBy == null ? null : "LEGIT", resolvedBy, resolvedBy == null ? null : resolvedBy.toString());
        jdbc.update("INSERT INTO fcd.case_txn(case_id, txn_id) VALUES (?, ?)", id, txn);
        jdbc.update("INSERT INTO fcd.case_event(id, case_id, from_status, event, to_status, actor_id, note) "
                + "VALUES (?, ?, NULL, 'open', 'OPEN', ?, ?)", UUID.randomUUID(), id, UUID.randomUUID(), reason);
        return id;
    }

    private MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static ResultMatcher code(String c) {
        return jsonPath("$.code").value(c);
    }

    private static String expense(UUID prj, String wbs, long amount) {
        long supply = Math.round(amount / 1.1);
        return """
                {"accountCode":"SUPPLIES","purpose":"현장 안전용품(합성)",%s%s"supplyAmount":%d,"vatAmount":%d,"vatDeductible":true}"""
                .formatted(prj == null ? "" : "\"projectId\":\"" + prj + "\",", wbs == null ? "" : "\"wbsCode\":\"" + wbs + "\",",
                        supply, amount - supply);
    }

    // ------------------------------------------------------------------ FCD-10

    @Test
    @DisplayName("FCD-10: 정산 라인 프로젝트는 거래일 기준 투입(project_member)된 곳만 — 비투입 · 기간 밖 403, WBS 만 지정 422, 투입 시 200")
    void fcd10_expenseProjectRequiresAssignment() throws Exception {
        UUID emp = actor();
        UUID prj = project();
        UUID card = card(FcdIntegrationTest.syntheticPan(), emp);
        String p4 = jdbc.queryForObject("SELECT last4 FROM fcd.card WHERE id = ?", String.class, card);
        upload(FcdIntegrationTest.acardCsv(p4, List.<String[]>of(
                new String[] {"2026.09.08", "11:00", "91000001", "(예시)정책철물", "999-81-00004", "철물공구", "22,000"})),
                "fcd10.csv");
        UUID txn = (UUID) txns(card).get(0).get("id");
        String url = "/api/v1/fcd/transactions/" + txn + "/expense";
        mvc.perform(as(putJson(url, expense(prj, null, 22_000)), emp, "EMPLOYEE"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-005"));             // 비투입
        mvc.perform(as(putJson(url, expense(null, "WBS-1.1", 22_000)), emp, "EMPLOYEE"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-005"));    // WBS 만
        member(emp, prj, "2026-09-10", "2026-12-31");                                          // 사용일(9/8) 이후 투입
        mvc.perform(as(putJson(url, expense(prj, "WBS-1.1", 22_000)), emp, "EMPLOYEE"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-005"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.expense_line WHERE txn_id = ?", Integer.class, txn)).isZero();
        member(emp, prj, "SUPPORT", "2026-09-01", "2026-09-09");                                // 사용일 포함 투입
        mvc.perform(as(putJson(url, expense(prj, "WBS-1.1", 22_000)), emp, "EMPLOYEE")).andExpect(status().isOk());
        mvc.perform(as(putJson(url, expense(null, null, 22_000)), emp, "EMPLOYEE")).andExpect(status().isOk()); // 귀속 없음 허용
    }

    // ------------------------------------------------------------------ FCD-11

    @Test
    @DisplayName("FCD-11: 중복 확인 큐 — 카드 소지자 본인 결정 403, 미결 케이스 · 정산 라인이 걸린 거래 MERGE 409, 해소 후 다른 FIN 병합 200")
    void fcd11_mergeSodAndBlockers() throws Exception {
        UUID holder = actor();
        String pan = FcdIntegrationTest.syntheticPan();
        UUID card = card(pan, holder);
        String p4 = pan.substring(pan.length() - 4);
        upload(FcdIntegrationTest.acardCsv(p4, List.<String[]>of(
                new String[] {"2026.09.15", "12:00", "91100001", "(예시)정책칼국수", "", "한식", "8,000"})), "fcd11.csv");
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet ws = wb.createSheet("매입내역");
        String[] head = {"이용일자", "카드번호", "승인번호", "가맹점명", "매출금액", "매입일자", "구분"};
        Row h = ws.createRow(0);
        for (int i = 0; i < head.length; i++) {
            h.createCell(i).setCellValue(head[i]);
        }
        Row x = ws.createRow(1);
        x.createCell(0).setCellValue("2026-09-15");
        x.createCell(1).setCellValue("999999******" + p4);
        x.createCell(3).setCellValue("정책칼국수본점");
        x.createCell(4).setCellValue(8000d);
        x.createCell(5).setCellValue("2026-09-17");
        x.createCell(6).setCellValue("매입");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        upload(out.toByteArray(), "fcd11.xlsx");
        Map<String, Object> cand = jdbc.queryForMap("SELECT id, txn_id, new_txn_id FROM fcd.dedup_candidate WHERE decision IS NULL "
                + "AND txn_id IN (SELECT id FROM fcd.txn WHERE card_id = ?)", card);
        String url = "/api/v1/fcd/dedup-candidates/" + cand.get("id") + ":decide";
        String merge = "{\"decision\":\"MERGE\"}";
        // 직무 분리: 카드 소지자가 FIN 이어도 본인 카드 거래는 결정 불가(MERGE · SEPARATE 모두)
        mvc.perform(as(postJson(url, merge), holder, "FIN")).andExpect(status().isForbidden()).andExpect(code("FCD-403-004"));
        mvc.perform(as(postJson(url, "{\"decision\":\"SEPARATE\"}"), holder, "FIN"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-004"));
        // 정산 라인이 걸린 새 거래
        UUID rep = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        jdbc.update("INSERT INTO fcd.expense_report(id, employee_id, period, status) VALUES (?, ?, '2026-09', 'DRAFT')", rep, holder);
        jdbc.update("""
                INSERT INTO fcd.expense_line(id, report_id, txn_id, account_code, cost_element, purpose, supply_amount, vat_amount,
                                             vat_deductible) VALUES (?, ?, ?, 'MEAL', 'OTHER', '중식', 7273, 727, true)""",
                line, rep, cand.get("new_txn_id"));
        mvc.perform(as(postJson(url, merge), fin, "FIN")).andExpect(status().isConflict()).andExpect(code("FCD-409-007"));
        jdbc.update("DELETE FROM fcd.expense_line WHERE id = ?", line);
        // 미결(소명 요청) 케이스가 걸린 기존 거래
        UUID c = caseFor((UUID) cand.get("txn_id"), holder, "EXPLANATION_REQUESTED", null, "확인 필요");
        mvc.perform(as(postJson(url, merge), fin, "FIN")).andExpect(status().isConflict()).andExpect(code("FCD-409-007"));
        assertThat(txns(card)).hasSize(2);
        jdbc.update("UPDATE fcd.fcd_case SET status = 'RESOLVED_LEGIT', label = 'LEGIT', resolved_by = ?, resolved_at = now() "
                + "WHERE id = ?", auditor, c);
        mvc.perform(as(postJson(url, merge), fin, "FIN")).andExpect(status().isOk());
        assertThat(txns(card)).hasSize(1);
    }

    // ------------------------------------------------------------------ FCD-12

    @Test
    @DisplayName("FCD-12: 배치 롤백 — 조사 중 케이스 거래는 KEEP_OPEN_CASE 로 유지, FIN 요청 → AUDITOR 승인(요청자 ≠ 승인자) 때만 실행")
    void fcd12_rollbackKeepsOpenCaseAndNeedsFourEyes() throws Exception {
        UUID emp = actor();
        String pan = FcdIntegrationTest.syntheticPan();
        UUID card = card(pan, emp);
        String b = upload(FcdIntegrationTest.acardCsv(pan.substring(pan.length() - 4), List.<String[]>of(
                new String[] {"2026.09.21", "12:00", "91200001", "(예시)정책백반", "", "한식", "9,000"},
                new String[] {"2026.09.22", "21:30", "91200002", "(예시)정책라운지", "", "유흥주점", "120,000"})),
                "fcd12.csv");
        List<Map<String, Object>> t = txns(card);
        UUID lounge = (UUID) t.stream().filter(x -> String.valueOf(x.get("merchant_name")).contains("라운지")).findFirst()
                .orElseThrow().get("id");
        caseFor(lounge, emp, "ESCALATED", null, "확인 필요");
        JsonNode impact = json(mvc.perform(as(get("/api/v1/fcd/imports/" + b + "/rollback-impact"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andReturn());
        assertThat(impact.get("KEEP_OPEN_CASE").asInt()).isEqualTo(1);
        assertThat(impact.get("DELETE_TXN").asInt()).isEqualTo(1);

        String rb = "/api/v1/fcd/imports/" + b;
        mvc.perform(as(postJson(rb + ":approve-rollback", "{}"), auditor, "AUDITOR"))
                .andExpect(status().isConflict()).andExpect(code("FCD-409-008"));             // 요청 없음
        JsonNode req = json(mvc.perform(as(postJson(rb + ":rollback", "{\"reason\":\"합성 오업로드\"}"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(req.get("status").asText()).isEqualTo("COMMITTED");                         // 아직 실행 안 됨
        assertThat(req.get("rollbackImpact").get("KEEP_OPEN_CASE").asInt()).isEqualTo(1);
        assertThat(txns(card)).hasSize(2);
        mvc.perform(as(postJson(rb + ":rollback", "{\"reason\":\"중복 요청\"}"), fin, "FIN"))
                .andExpect(status().isConflict()).andExpect(code("FCD-409-008"));
        mvc.perform(as(postJson(rb + ":approve-rollback", "{}"), fin2, "FIN")).andExpect(status().isForbidden()); // 권한 없음
        mvc.perform(as(postJson(rb + ":approve-rollback", "{}"), fin, "AUDITOR"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-002"));             // 요청자 본인
        JsonNode done = json(mvc.perform(as(postJson(rb + ":approve-rollback", "{}"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andReturn());
        assertThat(done.get("status").asText()).isEqualTo("PARTIALLY_ROLLED_BACK");
        assertThat(done.get("rollbackRequest").get("status").asText()).isEqualTo("APPROVED");
        assertThat(txns(card)).extracting(x -> x.get("id")).containsExactly(lounge);         // 조사 중 거래는 남는다
        assertThat(jdbc.queryForObject("SELECT rolled_back_by FROM fcd.import_batch WHERE id = ?::uuid", UUID.class, b))
                .isEqualTo(auditor);
    }

    // ------------------------------------------------------------------ FCD-13

    @Test
    @DisplayName("FCD-13: 검토 실행 — policy enum · 범위 밖(minScore 101 · k 0) 422, FIN 재정의 403, AUDITOR 사유 없으면 422 · 사유와 함께 200(기록)")
    void fcd13_reviewRunParameterGuard() throws Exception {
        String url = "/api/v1/fcd/review-queue/2031-01:run";
        mvc.perform(as(postJson(url, "{\"policy\":\"ANYTHING\"}"), fin, "FIN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-006"));
        mvc.perform(as(postJson(url, "{\"policy\":\"MONTHLY_BUDGET\",\"minScore\":101,\"reason\":\"x\"}"), auditor, "AUDITOR"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-006"));
        mvc.perform(as(postJson(url, "{\"policy\":\"MONTHLY_BUDGET\",\"kPer1000\":0,\"reason\":\"x\"}"), auditor, "AUDITOR"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-006"));
        mvc.perform(as(postJson(url, "{\"policy\":\"MONTHLY_BUDGET\",\"kPer1000\":50}"), fin, "FIN"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-006"));
        mvc.perform(as(postJson(url, "{\"policy\":\"THRESHOLD\",\"minScore\":40}"), fin, "FIN"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-006"));
        mvc.perform(as(postJson(url, "{\"policy\":\"MONTHLY_BUDGET\",\"kPer1000\":50}"), auditor, "AUDITOR"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-006"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.review_run WHERE period_ym = '2031-01'", Integer.class)).isZero();
        mvc.perform(as(postJson(url, "{\"policy\":\"THRESHOLD\"}"), fin, "FIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.override").value(false)).andExpect(jsonPath("$.minScore").value(60));
        mvc.perform(as(postJson(url, "{\"policy\":\"MONTHLY_BUDGET\",\"kPer1000\":50,\"reason\":\"합성 월말 집중 점검\"}"),
                auditor, "AUDITOR")).andExpect(status().isOk()).andExpect(jsonPath("$.override").value(true));
        Map<String, Object> last = jdbc.queryForMap("SELECT policy, override_reason, overridden_by FROM fcd.review_run "
                + "WHERE period_ym = '2031-01' AND override_reason IS NOT NULL");
        assertThat(last.get("override_reason")).isEqualTo("합성 월말 집중 점검");
        assertThat(last.get("overridden_by")).isEqualTo(auditor);
    }

    // ------------------------------------------------------------------ FCD-14

    @Test
    @DisplayName("FCD-14: 공용카드 SELF 대여는 오늘 이후 시작 · ACTIVE 카드만 — 본인 소급 422, FIN 소급 등록 201(본인 소급은 403), 정지 카드 409")
    void fcd14_sharedCardSelfAssignment() throws Exception {
        UUID card = card(FcdIntegrationTest.syntheticPan(), null);
        UUID emp = actor();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String url = "/api/v1/fcd/cards/" + card + "/assignments";
        java.util.function.BiFunction<UUID, LocalDate, String> body = (who, start) -> """
                {"employeeId":"%s","start":"%sT09:00:00","end":"%sT18:00:00","purpose":"현장 출장(합성)"}"""
                .formatted(who, start, start);
        mvc.perform(as(postJson(url, body.apply(emp, today.minusDays(3))), emp, "EMPLOYEE"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-007"));
        mvc.perform(as(postJson(url, body.apply(emp, today.plusDays(1))), emp, "EMPLOYEE")).andExpect(status().isCreated());
        mvc.perform(as(postJson(url, body.apply(fin, today.minusDays(4))), fin, "FIN"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-002"));
        mvc.perform(as(postJson(url, body.apply(emp, today.minusDays(5))), actor(), "ADMIN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(code("FCD-422-007"));  // 소급은 FIN 만
        mvc.perform(as(postJson(url, body.apply(emp, today.minusDays(5))), fin, "FIN")).andExpect(status().isCreated());
        jdbc.update("UPDATE fcd.card SET status = 'SUSPENDED' WHERE id = ?", card);
        mvc.perform(as(postJson(url, body.apply(emp, today.plusDays(2))), emp, "EMPLOYEE"))
                .andExpect(status().isConflict()).andExpect(code("FCD-409-009"));
    }

    // ------------------------------------------------------------------ FCD-17

    @Test
    @DisplayName("FCD-17: 정산서 거래의 케이스를 판정한 사람(EXEC)은 그 정산서 결재 403, 다른 결재자(FIN) 200")
    void fcd17_adjudicatorCannotApproveExpenseReport() throws Exception {
        UUID emp = actor();
        UUID prj = project();
        member(emp, prj, "2026-09-01", "2026-09-30");
        String pan = FcdIntegrationTest.syntheticPan();
        UUID card = card(pan, emp);
        upload(FcdIntegrationTest.acardCsv(pan.substring(pan.length() - 4), List.<String[]>of(
                new String[] {"2026.09.24", "14:00", "91700001", "(예시)정책공구", "999-81-00004", "철물공구", "33,000"})),
                "fcd17.csv");
        UUID txn = (UUID) txns(card).get(0).get("id");
        caseFor(txn, emp, "RESOLVED_LEGIT", exec, "확인 필요");
        mvc.perform(as(putJson("/api/v1/fcd/transactions/" + txn + "/expense", expense(prj, null, 33_000)), emp, "EMPLOYEE"))
                .andExpect(status().isOk());
        String report = json(mvc.perform(as(postJson("/api/v1/fcd/expense-reports/2026-09:submit", "{}"), emp, "EMPLOYEE"))
                .andExpect(status().isOk()).andReturn()).get("reportId").asText();
        mvc.perform(as(postJson("/api/v1/fcd/expense-reports/" + report + ":approve", "{}"), exec, "EXEC"))
                .andExpect(status().isForbidden()).andExpect(code("FCD-403-007"));
        mvc.perform(as(postJson("/api/v1/fcd/expense-reports/" + report + ":approve", "{}"), fin, "FIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    // ------------------------------------------------------------------ FCD-18

    @Test
    @DisplayName("FCD-18: 직원(SELF)은 본인 거래 룰 적중 요약만(코드 · 메시지 · 임계값 없음) · 케이스 사유도 요약, FIN · AUDITOR 는 상세")
    void fcd18_selfGetsRuleHitSummaryOnly() throws Exception {
        UUID emp = actor();
        String pan = FcdIntegrationTest.syntheticPan();
        UUID card = card(pan, emp);
        upload(FcdIntegrationTest.acardCsv(pan.substring(pan.length() - 4), List.<String[]>of(
                new String[] {"2026.09.25", "21:10", "91800001", "(예시)정책룸", "", "유흥주점", "150,000"})), "fcd18.csv");
        UUID txn = (UUID) txns(card).get(0).get("id");
        String url = "/api/v1/fcd/transactions/" + txn + "/risk";
        String full = mvc.perform(as(get(url), auditor, "AUDITOR")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(full).contains("R-CAT-BLOCK").contains("\"hits\"");
        JsonNode self = json(mvc.perform(as(get(url), emp, "EMPLOYEE")).andExpect(status().isOk()).andReturn());
        assertThat(self).isNotEmpty();
        assertThat(self.get(0).get("summary").asBoolean()).isTrue();
        assertThat(self.get(0).get("hit_count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(self.get(0).has("hits")).isFalse();
        assertThat(self.get(0).has("reasons")).isFalse();
        assertThat(self.toString()).doesNotContain("R-CAT-BLOCK").doesNotContain("150,000");

        String reason = "한도 초과 — 1회 150,000/100,000원 (합성)";
        UUID c = caseFor(txn, emp, "EXPLANATION_REQUESTED", null, reason);
        JsonNode mine = json(mvc.perform(as(get("/api/v1/fcd/cases/" + c), emp, "EMPLOYEE")).andExpect(status().isOk()).andReturn());
        assertThat(mine.get("opened_reason").asText()).isEqualTo(CaseService.SUBJECT_REASON);
        assertThat(mine.toString()).doesNotContain("100,000");
        JsonNode audit = json(mvc.perform(as(get("/api/v1/fcd/cases/" + c), auditor, "AUDITOR")).andExpect(status().isOk())
                .andReturn());
        assertThat(audit.get("opened_reason").asText()).isEqualTo(reason);
    }
}
