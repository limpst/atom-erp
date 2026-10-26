package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.fcd.domain.ingest.PanTools;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 12 FCD 통합 테스트 — 합성 명세서 업로드 → 채널 간 중복 제거 → L1 채점 → 검토 큐 · 케이스(소명 · 판정) → 정산 →
 * {@code fcd.expense.posted} → CST 원가 원장 → 더존(스텁) → {@code cst.voucher.synced} → 정산서 POSTED (E2E).
 * TC-FCD-001 · 007 · 009 · 013 · 014 · 015 · 016 · 017 · 024 · 025 · 026 · 027 · 030, hrq.employee.terminated.
 * 다른 모듈 테스트와 겹치지 않게 2026년 5–8월을 테스트별로 쓴다(룰셋 2026.5.0 시행 범위). 카드번호 · 가맹점 · 금액 · 사람은 전부 합성.
 */
class FcdIntegrationTest extends IntegrationTestBase {

    static final Charset CP949 = Charset.forName("x-windows-949");
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 1_000_000));

    @Autowired
    OutboxRelay relay;

    UUID fin;
    UUID auditor;
    UUID admin;
    UUID pm;

    @BeforeEach
    void setUp() {
        fin = actor();
        auditor = actor();
        admin = actor();
        pm = actor();
        clock.reset();
    }

    // ------------------------------------------------------------------ helpers

    /** 가상 BIN 999999 + 무작위 → Luhn 유효 합성 PAN */
    static String syntheticPan() {
        return PanTools.luhnComplete("999999" + String.format("%09d", SEQ.incrementAndGet() % 1_000_000_000L));
    }

    static String last4(String pan) {
        return pan.substring(pan.length() - 4);
    }

    private UUID registerCard(String pan, UUID holder, long perTxn) throws Exception {
        String body = """
                {"cardCompany":"ACARD","pan":"%s","cardType":"PERSONAL_ASSIGNED","holderEmployeeId":"%s","dept":"진단1팀",
                 "limitPerTxn":%d,"limitDaily":%d,"limitMonthly":5000000}""".formatted(pan, holder, perTxn, perTxn * 3 / 2);
        MvcResult r = mvc.perform(as(postJson("/api/v1/fcd/cards", body), fin, "FIN")).andExpect(status().isCreated())
                .andReturn();
        String res = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(res).doesNotContain(pan).contains("999999******" + last4(pan));   // TC-FCD-001 응답에 PAN 없음
        return UUID.fromString(json(r).get("id").asText());
    }

    /** 에이카드 승인내역 CSV(CP949 · 제목 2행 · 합계 행) */
    static byte[] acardCsv(String pan4, List<String[]> rows) {
        StringBuilder sb = new StringBuilder("(가상)에이카드 법인카드 이용내역(승인)\r\n조회기간,합성,출력일,2026.10.19\r\n");
        sb.append("이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고\r\n");
        long total = 0;
        for (String[] r : rows) {
            sb.append(String.join(",", r[0], r[1], "999999******" + pan4, r[2], r[3], r[4], r[5], "\"" + r[6] + "\"", "KRW",
                    "", r.length > 7 ? r[7] : "")).append("\r\n");
            total += Long.parseLong(r[6].replace(",", ""));
        }
        sb.append("합계,,,,,,,\"").append(String.format("%,d", total)).append("\",,,\r\n");
        return sb.toString().getBytes(CP949);
    }

    private JsonNode upload(byte[] data, String name, String profileCode, int expectStatus) throws Exception {
        MockMultipartFile f = new MockMultipartFile("file", name, "application/octet-stream", data);
        var req = multipart("/api/v1/fcd/imports").file(f);
        if (profileCode != null) {
            req.param("profileCode", profileCode);
        }
        MvcResult r = mvc.perform(as(req, fin, "FIN")).andExpect(status().is(expectStatus)).andReturn();
        return json(r);
    }

    private JsonNode commit(String batchId) throws Exception {
        return json(mvc.perform(as(postJson("/api/v1/fcd/imports/" + batchId + ":commit", "{}"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
    }

    private UUID project() throws Exception {
        UUID client = createClient(actor(), "합성발주처-fcd-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 FCD 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    /** FCD-10: 정산 라인 프로젝트는 거래일 기준 투입(core.project_member) 필요 — 시험 직원을 직원 대장 · 투입에 등록 */
    void assignToProject(UUID emp, UUID prj, String from, String to) {
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성직원', DATE '2020-01-01', ?, ?) ON CONFLICT (id) DO NOTHING", emp, "FP-" + emp.toString().substring(24),
                emp, emp);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, 'PART', ?::date, ?::date)", prj, emp, from, to);
    }

    private UUID hrqEmployee() throws Exception {
        UUID id = UUID.randomUUID();
        String empNo = "F-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성직원', DATE '2020-01-01', ?, ?)", id, empNo, id, id);
        mvc.perform(as(postJson("/api/v1/hrq/employees", """
                        {"id":"%s","empNo":"%s","name":"합성직원","field":"ARCH","employmentType":"FULLTIME",
                         "registeredField":false,"hiredOn":"2020-01-01","initialGrade":"INTER","gradeFrom":"2020-01-01"}"""
                        .formatted(id, empNo)), admin, "ADMIN"))
                .andExpect(status().isCreated());
        return id;
    }

    private List<Map<String, Object>> txns(UUID card) {
        return jdbc.queryForList("SELECT * FROM fcd.txn WHERE card_id = ? AND removed_at IS NULL ORDER BY approved_at, seq",
                card);
    }

    private Map<String, Object> txnBy(UUID card, String merchantLike) {
        return txns(card).stream().filter(t -> String.valueOf(t.get("merchant_name")).contains(merchantLike)).findFirst()
                .orElseThrow();
    }

    private void drain() {
        for (int i = 0; i < 5; i++) {
            relay.pollOnce();
        }
    }

    private MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ------------------------------------------------------------------ E2E

    @Test
    @DisplayName("E2E: 합성 CSV(CP949) + XLSX 업로드 → 채널 간 병합 · 취소 → L1(쪼개기 · 금지업종) → 케이스 · 소명 · 판정(이해충돌) → "
            + "정산 게이트 → 전표 → CST 원가 원장 → cst.voucher.synced → POSTED")
    void endToEnd() throws Exception {
        UUID emp = actor();
        UUID prj = project();
        assignToProject(emp, prj, "2026-05-01", "2026-05-31");   // FCD-10 투입 기간 안의 사용만 그 프로젝트 원가
        String pan = syntheticPan();
        UUID card = registerCard(pan, emp, 300_000);
        String p4 = last4(pan);

        // 1) 승인내역 CSV — 점심 · 금지업종(유흥주점) · 쪼개기 2건(40분, 280k+270k > 1회 한도 300k) · 커피(나중 취소)
        byte[] csv = acardCsv(p4, List.of(
                new String[] {"2026.05.05", "12:05", "31000001", "(예시)한강설렁탕 하남점", "999-81-00001", "한식", "12,000"},
                new String[] {"2026.05.06", "21:10", "31000002", "(예시)룸라운지", "", "유흥주점", "150,000"},
                new String[] {"2026.05.07", "14:00", "31000003", "(예시)대성공구상사", "999-81-00004", "철물공구", "280,000"},
                new String[] {"2026.05.07", "14:40", "31000004", "(예시)대성공구상사", "999-81-00004", "철물공구", "270,000"},
                new String[] {"2026.05.08", "09:10", "31000005", "(예시)바른커피", "", "커피전문점", "4,500"}));
        JsonNode b1 = upload(csv, "acard_2026-05.csv", null, 201);
        assertThat(b1.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(b1.get("detected_encoding").asText()).isEqualTo("CP949");
        assertThat(b1.get("detected_header_row").asInt()).isEqualTo(2);
        assertThat(b1.get("profile_code").asText()).isEqualTo("P-ACARD-APPROVAL-CSV-1");
        assertThat(b1.get("counts").get("NEW").asInt()).isEqualTo(5);
        commit(b1.get("id").asText());
        assertThat(txns(card)).hasSize(5);
        // TC-FCD-013 동일 파일 재업로드 → 409
        mvc.perform(as(multipart("/api/v1/fcd/imports").file(new MockMultipartFile("file", "again.csv", "text/csv", csv)),
                fin, "FIN")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FCD-409-002"));

        // 2) 매입내역 XLSX — 점심 매입(같은 승인번호 · 날짜만) = 채널 간 병합, 커피 취소행, 미등록 카드 행 = 행 오류
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet ws = wb.createSheet("매입내역");
        String[] head = {"이용일자", "카드번호", "승인번호", "가맹점명", "매출금액", "매입일자", "구분"};
        Row h = ws.createRow(0);
        for (int i = 0; i < head.length; i++) {
            h.createCell(i).setCellValue(head[i]);
        }
        CellStyle date = wb.createCellStyle();
        date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
        CellStyle zeros = wb.createCellStyle();
        zeros.setDataFormat(wb.createDataFormat().getFormat("00000000"));
        Object[][] rows = {
                {LocalDateTime.of(2026, 5, 5, 0, 0), "999999******" + p4, 31000001d, "한강설렁탕하남", 12000d,
                        LocalDateTime.of(2026, 5, 7, 0, 0), "매입"},
                {LocalDateTime.of(2026, 5, 8, 0, 0), "999999******" + p4, 31000005d, "(예시)바른커피", 4500d,
                        LocalDateTime.of(2026, 5, 9, 0, 0), "취소"},
                {LocalDateTime.of(2026, 5, 8, 0, 0), "999999******0000", 39999999d, "(예시)미등록", 1000d,
                        LocalDateTime.of(2026, 5, 9, 0, 0), "매입"}};
        for (int i = 0; i < rows.length; i++) {
            Row x = ws.createRow(i + 1);
            var c0 = x.createCell(0);
            c0.setCellValue((LocalDateTime) rows[i][0]);
            c0.setCellStyle(date);
            x.createCell(1).setCellValue((String) rows[i][1]);
            var c2 = x.createCell(2);
            c2.setCellValue((Double) rows[i][2]);
            c2.setCellStyle(zeros);
            x.createCell(3).setCellValue((String) rows[i][3]);
            x.createCell(4).setCellValue((Double) rows[i][4]);
            var c5 = x.createCell(5);
            c5.setCellValue((LocalDateTime) rows[i][5]);
            c5.setCellStyle(date);
            x.createCell(6).setCellValue((String) rows[i][6]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        JsonNode b2 = upload(out.toByteArray(), "acard_purchase_2026-05.xlsx", null, 201);
        assertThat(b2.get("profile_code").asText()).isEqualTo("P-ACARD-PURCHASE-XLSX-1");
        assertThat(b2.get("counts").get("MERGED").asInt()).isEqualTo(1);
        assertThat(b2.get("counts").get("CANCEL").asInt()).isEqualTo(1);
        assertThat(b2.get("counts").get("ERROR").asInt()).isEqualTo(1);
        String errCsv = mvc.perform(as(get("/api/v1/fcd/imports/" + b2.get("id").asText() + "/errors.csv"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(errCsv).startsWith("﻿행번호").contains("E-CARD-UNKNOWN");   // TC-FCD-015
        commit(b2.get("id").asText());
        List<Map<String, Object>> all = txns(card);
        assertThat(all).hasSize(5);                                                     // 병합: 거래 수 그대로
        Map<String, Object> lunch = txnBy(card, "한강설렁탕");
        assertThat(lunch.get("status")).isEqualTo("PURCHASED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.txn_source WHERE txn_id = ? AND NOT detached", Integer.class,
                lunch.get("id"))).isEqualTo(2);                                         // TC-FCD-009 txn 1 · source 2
        assertThat(jdbc.queryForObject("SELECT match_method FROM fcd.txn_source WHERE txn_id = ? AND channel = 'EXCEL'",
                String.class, lunch.get("id"))).isEqualTo("APPROVAL_NO");
        assertThat(txnBy(card, "바른커피").get("status")).isEqualTo("CANCELLED");      // TC-FCD-007

        // 3) L1 채점 결과: 금지업종 ≥ 85(TC-FCD-017), 쪼개기 ≥ 70(TC-FCD-016)
        Map<String, Object> club = txnBy(card, "룸라운지");
        assertThat(((Number) club.get("risk_score")).intValue()).isGreaterThanOrEqualTo(85);
        assertThat(club.get("risk_grade")).isEqualTo("HIGH");
        List<Map<String, Object>> split = all.stream().filter(t -> String.valueOf(t.get("merchant_name")).contains("대성공구"))
                .toList();
        assertThat(split).allMatch(t -> ((Number) t.get("risk_score")).intValue() >= 70);
        String risk = mvc.perform(as(get("/api/v1/fcd/transactions/" + club.get("id") + "/risk"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(risk).contains("R-CAT-BLOCK").contains("2026.5.0");   // 2026-05 거래 → 시행일 기준 룰셋
        mvc.perform(as(get("/api/v1/fcd/transactions/" + club.get("id") + "/risk"), actor(), "EMPLOYEE"))
                .andExpect(status().isForbidden());                            // 남의 거래

        // 4) 검토 큐(임계 60) → 케이스: 금지업종 1 + 쪼개기 묶음 1
        JsonNode run = json(mvc.perform(as(postJson("/api/v1/fcd/review-queue/2026-05:run", "{\"policy\":\"THRESHOLD\"}"),
                fin, "FIN")).andExpect(status().isOk()).andReturn());
        assertThat(run.get("casesOpened")).hasSize(2);
        UUID clubCase = jdbc.queryForObject("SELECT case_id FROM fcd.case_txn WHERE txn_id = ?", UUID.class, club.get("id"));
        UUID splitCase = jdbc.queryForObject("SELECT case_id FROM fcd.case_txn WHERE txn_id = ?", UUID.class,
                split.get(0).get("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.case_txn WHERE case_id = ?", Integer.class, splitCase))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT opened_reason FROM fcd.fcd_case WHERE id = ?", String.class, clubCase))
                .doesNotContain("부정");                                       // F12 판정 전 단정 표현 금지
        for (UUID c : List.of(clubCase, splitCase)) {
            mvc.perform(as(postJson("/api/v1/fcd/cases/" + c + ":request-explanation", "{}"), fin, "FIN"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("확인이 필요한 사용이 있습니다. 앱에서 소명해 주세요."));
        }

        // 5) 정산 입력 → 상신: 미소명 케이스가 있으면 거절(TC-FCD-026)
        mvc.perform(as(putJson("/api/v1/fcd/transactions/" + lunch.get("id") + "/expense", """
                {"accountCode":"MEAL","purpose":"현장 중식","projectId":"%s","supplyAmount":10909,"vatAmount":1091,
                 "vatDeductible":true}""".formatted(prj)), emp, "EMPLOYEE")).andExpect(status().isOk());
        for (Map<String, Object> t : split) {
            long amt = ((BigDecimal) t.get("amount_krw")).longValue();
            long supply = Math.round(amt / 1.1);
            mvc.perform(as(putJson("/api/v1/fcd/transactions/" + t.get("id") + "/expense", """
                    {"accountCode":"SUPPLIES","purpose":"현장 안전용품","projectId":"%s","supplyAmount":%d,"vatAmount":%d,
                     "vatDeductible":true}""".formatted(prj, supply, amt - supply)), emp, "EMPLOYEE")).andExpect(status().isOk());
        }
        mvc.perform(as(putJson("/api/v1/fcd/transactions/" + lunch.get("id") + "/expense", """
                {"accountCode":"MEAL","purpose":"x","supplyAmount":1,"vatAmount":0,"vatDeductible":true}"""), actor(),
                "EMPLOYEE")).andExpect(status().isForbidden());                // 남의 거래 정산 금지
        mvc.perform(as(postJson("/api/v1/fcd/expense-reports/2026-05:submit", "{}"), emp, "EMPLOYEE"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FCD-409-005"));

        // 6) 소명 제출(본인만) → 판정: 본인 판정은 이해충돌(TC-FCD-025), 감사가 판정
        mvc.perform(as(postJson("/api/v1/fcd/cases/" + splitCase + "/explanations",
                "{\"purpose\":\"BUSINESS\",\"text\":\"현장 긴급 안전용품을 두 번에 나눠 구매\"}"), actor(), "EMPLOYEE"))
                .andExpect(status().isForbidden());
        for (UUID c : List.of(clubCase, splitCase)) {
            mvc.perform(as(postJson("/api/v1/fcd/cases/" + c + "/explanations",
                    "{\"purpose\":\"BUSINESS\",\"text\":\"합성 소명\",\"attendees\":[{\"type\":\"INTERNAL\"}]}"), emp, "EMPLOYEE"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(postJson("/api/v1/fcd/cases/" + splitCase + ":resolve",
                "{\"label\":\"LEGIT\",\"reason\":\"셀프 판정\"}"), emp, "AUDITOR"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FCD-403-001"));
        mvc.perform(as(postJson("/api/v1/fcd/cases/" + splitCase + ":resolve",
                "{\"label\":\"LEGIT\",\"reason\":\"긴급 구매 증빙 확인\"}"), auditor, "AUDITOR")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED_LEGIT"));
        mvc.perform(as(postJson("/api/v1/fcd/cases/" + clubCase + ":resolve",
                "{\"label\":\"MISUSE\",\"reason\":\"업무 무관\",\"recoveryAmount\":150000}"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED_MISUSE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.feedback WHERE case_id IN (?, ?)", Integer.class, clubCase,
                splitCase)).isEqualTo(3);

        // 7) 상신 → 결재(본인 결재 금지) → 전표
        JsonNode sub = json(mvc.perform(as(postJson("/api/v1/fcd/expense-reports/2026-05:submit", "{}"), emp, "EMPLOYEE"))
                .andExpect(status().isOk()).andReturn());
        String report = sub.get("reportId").asText();
        // SEC-FCD-002 · 003: 다른 직원(SELF 범위)은 남의 정산서 · 정산 게이트 조회 403, 본인 · 결재권자(FIN)는 200
        UUID other = actor();
        mvc.perform(as(get("/api/v1/fcd/expense-reports/" + report), other, "EMPLOYEE")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/fcd/expense-reports/" + report), emp, "EMPLOYEE")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/fcd/expense-reports/" + report), fin, "FIN")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/fcd/expense-reports/" + UUID.randomUUID()), fin, "FIN")).andExpect(status().isNotFound());
        mvc.perform(as(get("/api/v1/fcd/employees/" + emp + "/expense-gate"), other, "EMPLOYEE"))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/fcd/employees/" + emp + "/expense-gate"), emp, "EMPLOYEE")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/fcd/employees/" + emp + "/expense-gate"), auditor, "AUDITOR")).andExpect(status().isOk());
        mvc.perform(as(postJson("/api/v1/fcd/expense-reports/" + report + ":approve", "{}"), emp, "FIN"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/fcd/expense-reports/" + report + ":approve", "{}"), fin, "FIN"))
                .andExpect(status().isOk());
        JsonNode posted = json(mvc.perform(as(postJson("/api/v1/fcd/expense-reports/" + report + ":post", "{}"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(posted.get("postedLines")).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'fcd.expense.posted' "
                + "AND aggregate_id = ?::uuid", Integer.class, report)).isEqualTo(3);  // TC-FCD-027 라인당 1회

        // 8) Relay → CST 구독(원장 EXPENSE 행 · 더존 스텁 전표) → cst.voucher.synced → FCD 라인 SYNCED · 정산서 POSTED
        drain();
        List<Map<String, Object>> ledger = jdbc.queryForList("""
                SELECT e.source_type, e.cost_element, e.amount_krw, e.period_ym FROM cst.cost_ledger_entry e
                  JOIN fcd.expense_line l ON l.id = e.source_id WHERE l.report_id = ?::uuid ORDER BY e.amount_krw""", report);
        assertThat(ledger).hasSize(3).allMatch(r -> "EXPENSE".equals(r.get("source_type")) && "202605".equals(r.get("period_ym")));
        assertThat(ledger.stream().map(r -> ((BigDecimal) r.get("amount_krw")).longValue()).toList())
                .contains(10_909L);                                                     // 공제 → 공급가만 원가
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'cst.voucher.synced' "
                + "AND aggregate_id = ?::uuid", Integer.class, report)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT sync_status, voucher_no FROM fcd.expense_line WHERE report_id = ?::uuid", report))
                .allMatch(l -> "SYNCED".equals(l.get("sync_status")) && String.valueOf(l.get("voucher_no")).startsWith("DZ-STUB-"));
        assertThat(jdbc.queryForObject("SELECT status FROM fcd.expense_report WHERE id = ?::uuid", String.class, report))
                .isEqualTo("POSTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_dead", Integer.class)).isZero();

        // 9) TC-FCD-030: PAN 스캐너 — FCD 테이블 · 감사 로그 · outbox 에 Luhn 통과 13–19자리 0건
        for (String q : List.of("SELECT row_to_json(x)::text FROM fcd.card x", "SELECT row_to_json(x)::text FROM fcd.txn x",
                "SELECT row_to_json(x)::text FROM fcd.txn_source x", "SELECT row_to_json(x)::text FROM fcd.import_staging x",
                "SELECT row_to_json(x)::text FROM fcd.import_row_error x", "SELECT row_to_json(x)::text FROM fcd.risk_score x",
                "SELECT row_to_json(x)::text FROM fcd.import_batch x",
                "SELECT row_to_json(x)::text FROM core.audit_log x WHERE module = 'FCD'",
                "SELECT row_to_json(x)::text FROM core.outbox_event x WHERE event_type LIKE 'fcd.%'")) {
            String dump = String.join(" | ", jdbc.queryForList(q, String.class));
            String scrubbed = dump.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
                    .replaceAll("[0-9a-f]{32,}", "<hex>");   // UUID · 토큰 · 해시(16진)는 PAN 후보에서 제외
            assertThat(PanTools.scanPans(scrubbed)).as(q).isEmpty();
            assertThat(dump).as(q).doesNotContain(pan);
        }
    }

    // ------------------------------------------------------------------ 롤백 · 중복 확인 큐

    @Test
    @DisplayName("TC-FCD-014 · BR-FCD-005: 롤백 영향 미리보기 → 전표 반영 건 유지(PARTIALLY_ROLLED_BACK) · 나머지 원장 제외 · 병합 원천만 분리, "
            + "롤백 후 같은 파일 재업로드 허용 / TC-FCD-012 확인 큐 MERGE")
    void rollbackAndReviewQueue() throws Exception {
        UUID emp = actor();
        String pan = syntheticPan();
        UUID card = registerCard(pan, emp, 300_000);
        byte[] csv = acardCsv(last4(pan), List.of(
                new String[] {"2026.06.02", "12:00", "52000001", "(예시)순대국집 수원점", "", "한식", "9,000"},
                new String[] {"2026.06.03", "12:00", "52000002", "(예시)순대국집 수원점", "", "한식", "9,500"},
                new String[] {"2026.06.04", "12:00", "52000003", "(예시)칼국수마을", "", "한식", "8,000"}));
        String b1 = upload(csv, "may.csv", null, 201).get("id").asText();
        commit(b1);
        assertThat(txns(card)).hasSize(3);
        // 두 번째 배치(XLSX 아님, 같은 양식 다른 파일): 1건은 기존 거래와 병합(다른 채널 아님 → 같은 채널 · 종류면 신규), 1건 신규
        // → 병합 시나리오는 확인 큐로: 날짜만 있는 매입 행(승인번호 없음) · 가맹점 유사
        XSSFWorkbook wb = new XSSFWorkbook();
        Sheet ws = wb.createSheet("매입내역");
        Row h = ws.createRow(0);
        String[] head = {"이용일자", "카드번호", "승인번호", "가맹점명", "매출금액", "매입일자", "구분"};
        for (int i = 0; i < head.length; i++) {
            h.createCell(i).setCellValue(head[i]);
        }
        Row x = ws.createRow(1);
        x.createCell(0).setCellValue("2026-06-04");
        x.createCell(1).setCellValue("999999******" + last4(pan));
        x.createCell(3).setCellValue("칼국수마을본점");
        x.createCell(4).setCellValue(8000d);
        x.createCell(5).setCellValue("2026-06-06");
        x.createCell(6).setCellValue("매입");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        JsonNode b2 = upload(out.toByteArray(), "may-purchase.xlsx", null, 201);
        assertThat(b2.get("counts").get("REVIEW").asInt()).isEqualTo(1);
        commit(b2.get("id").asText());
        List<Map<String, Object>> cands = jdbc.queryForList("SELECT id, score FROM fcd.dedup_candidate WHERE decision IS NULL "
                + "AND txn_id IN (SELECT id FROM fcd.txn WHERE card_id = ?)", card);
        assertThat(cands).hasSize(1);
        assertThat(((BigDecimal) cands.get(0).get("score")).doubleValue()).isBetween(0.75, 0.92);
        assertThat(txns(card)).hasSize(4);
        mvc.perform(as(postJson("/api/v1/fcd/dedup-candidates/" + cands.get(0).get("id") + ":decide",
                "{\"decision\":\"MERGE\"}"), fin, "FIN")).andExpect(status().isOk());
        assertThat(txns(card)).hasSize(3);
        assertThat(txnBy(card, "칼국수").get("status")).isEqualTo("PURCHASED");

        // 전표 반영 표시(1건) → 롤백: 그 건은 유지, 나머지 신규는 제외, 병합 거래의 다른 배치 원천은 남음
        Map<String, Object> posted = txnBy(card, "순대국");
        UUID rep = UUID.randomUUID();
        jdbc.update("INSERT INTO fcd.expense_report(id, employee_id, period, status) VALUES (?, ?, '2026-06', 'POSTED')", rep, emp);
        jdbc.update("""
                INSERT INTO fcd.expense_line(id, report_id, txn_id, account_code, cost_element, purpose, supply_amount, vat_amount,
                                             vat_deductible, sync_status) VALUES (?, ?, ?, 'MEAL', 'OTHER', '중식', 8182, 818, true,
                                             'SYNCED')""", UUID.randomUUID(), rep, posted.get("id"));
        JsonNode impact = json(mvc.perform(as(get("/api/v1/fcd/imports/" + b1 + "/rollback-impact"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(impact.get("KEEP_POSTED").asInt()).isEqualTo(1);
        assertThat(impact.get("DELETE_TXN").asInt()).isEqualTo(1);
        assertThat(impact.get("DETACH_SOURCE").asInt()).isEqualTo(1);    // 칼국수: XLSX 원천이 남아 있음
        mvc.perform(as(postJson("/api/v1/fcd/imports/" + b1 + ":rollback", "{\"reason\":\"\"}"), fin, "FIN"))
                .andExpect(status().isUnprocessableEntity());
        // FCD-12 4-eyes: FIN 요청 → (배치 그대로) → AUDITOR 승인 때 실행
        JsonNode req = json(mvc.perform(as(postJson("/api/v1/fcd/imports/" + b1 + ":rollback", "{\"reason\":\"합성 오업로드\"}"),
                fin, "FIN")).andExpect(status().isOk()).andReturn());
        assertThat(req.get("status").asText()).isEqualTo("COMMITTED");
        assertThat(req.get("rollbackRequest").get("status").asText()).isEqualTo("PENDING");
        JsonNode rb = json(mvc.perform(as(postJson("/api/v1/fcd/imports/" + b1 + ":approve-rollback", "{}"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andReturn());
        assertThat(rb.get("status").asText()).isEqualTo("PARTIALLY_ROLLED_BACK");
        List<Map<String, Object>> left = txns(card);
        assertThat(left).hasSize(2);
        assertThat(left).extracting(t -> String.valueOf(t.get("merchant_name"))).anyMatch(n -> n.contains("칼국수"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'fcd.import.rollback-completed' "
                + "AND aggregate_id = ?::uuid", Integer.class, b1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE target_type = 'import_batch' "
                + "AND target_id = ?::uuid", Integer.class, b1)).isGreaterThanOrEqualTo(3);   // 업로드 · 확정 · 롤백
    }

    // ------------------------------------------------------------------ HRQ 퇴사

    @Test
    @DisplayName("hrq.employee.terminated → 카드 '정지 검토' 표시만(상태 ACTIVE 유지 · 결제 자동 차단 없음) → 퇴사일 이후 사용은 R-CARD-INACTIVE")
    void employeeTerminated() throws Exception {
        UUID emp = hrqEmployee();
        String pan = syntheticPan();
        UUID card = registerCard(pan, emp, 300_000);
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + ":terminate",
                "{\"resignedOn\":\"2026-07-10\",\"source\":\"MANUAL\"}"), admin, "ADMIN")).andExpect(status().isOk());
        drain();
        Map<String, Object> c = jdbc.queryForMap("SELECT status, deactivation_flag, deactivation_reason FROM fcd.card WHERE id = ?",
                card);
        assertThat(c.get("status")).isEqualTo("ACTIVE");
        assertThat(c.get("deactivation_flag")).isEqualTo(true);
        assertThat((String) c.get("deactivation_reason")).contains("자동 차단 없음");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'fcd.card.deactivation-flagged' "
                + "AND aggregate_id = ?", Integer.class, card)).isEqualTo(1);
        byte[] csv = acardCsv(last4(pan), List.of(
                new String[] {"2026.07.05", "12:00", "60000001", "(예시)백반나라", "", "한식", "9,000"},
                new String[] {"2026.07.15", "12:00", "60000002", "(예시)백반나라", "", "한식", "9,000"}));
        commit(upload(csv, "june.csv", null, 201).get("id").asText());
        List<Map<String, Object>> t = txns(card);
        assertThat(t).hasSize(2);
        assertThat(((Number) t.get(0).get("risk_score")).intValue()).isLessThan(60);
        assertThat(((Number) t.get(1).get("risk_score")).intValue()).isGreaterThanOrEqualTo(90);
        assertThat(jdbc.queryForObject("SELECT hits::text FROM fcd.risk_score WHERE txn_id = ? ORDER BY scored_at DESC LIMIT 1",
                String.class, t.get(1).get("id"))).contains("R-CARD-INACTIVE");
    }

    // ------------------------------------------------------------------ 룰셋 4-eyes · 월 예산 큐

    @Test
    @DisplayName("TC-FCD-024: 룰셋 초안(같은 YAML 포맷) — 작성자 본인 승인 403(FCD-403-002), 다른 감사인 승인 → ACTIVE, 직원 업로드 403 / "
            + "시드 룰셋 8개(초안 2개는 DRAFT)")
    void rulesetFourEyes() throws Exception {
        String yaml = new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/fcd/rulesets/fcd-2026.5.0.yaml"))
                .readAllBytes(), StandardCharsets.UTF_8)
                .replace("version: 2026.5.0", "version: 2099.1.0").replace("effective_from: '2026-04-01'",
                        "effective_from: '2099-01-01'");
        java.util.function.Supplier<MockHttpServletRequestBuilder> draftReq = () -> org.springframework.test.web.servlet.request
                .MockMvcRequestBuilders.post("/api/v1/fcd/rule-sets").contentType("application/yaml").content(yaml);
        mvc.perform(as(draftReq.get(), actor(), "EMPLOYEE")).andExpect(status().isForbidden());
        mvc.perform(as(draftReq.get(), fin, "FIN")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"));
        mvc.perform(as(postJson("/api/v1/fcd/rule-sets/2099.1.0:approve", "{}"), fin, "AUDITOR"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FCD-403-002"));
        mvc.perform(as(postJson("/api/v1/fcd/rule-sets/2099.1.0:approve", "{}"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        JsonNode list = json(mvc.perform(as(get("/api/v1/fcd/rule-sets"), auditor, "AUDITOR")).andReturn());
        long drafts = 0;
        for (JsonNode n : list) {
            if (n.get("version").asText().contains("-cal.")) {
                assertThat(n.get("status").asText()).isEqualTo("DRAFT");
                drafts++;
            }
        }
        assertThat(drafts).isEqualTo(2);
        assertThat(list.size()).isGreaterThanOrEqualTo(9);
    }

    @Test
    @DisplayName("SEC-FCD-004: 같은 버전 · 다른 이름 초안 2개 — 버전만으로 승인하면 409(모호), name 지정 시 그 행만 ACTIVE")
    void rulesetApproveTargetsSingleRow() throws Exception {
        String base = new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/fcd/rulesets/fcd-2026.5.0.yaml"))
                .readAllBytes(), StandardCharsets.UTF_8)
                .replace("version: 2026.5.0", "version: 2099.2.0").replace("effective_from: '2026-04-01'",
                        "effective_from: '2099-02-01'");
        String evil = base.replace("ruleset: fcd", "ruleset: fcd-shadow");
        for (String y : List.of(base, evil)) {
            mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/fcd/rule-sets")
                    .contentType("application/yaml").content(y), fin, "FIN")).andExpect(status().isCreated());
        }
        mvc.perform(as(postJson("/api/v1/fcd/rule-sets/2099.2.0:approve", "{}"), auditor, "AUDITOR"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.rule_set WHERE version = '2099.2.0' AND status = 'ACTIVE'",
                Integer.class)).isZero();
        mvc.perform(as(postJson("/api/v1/fcd/rule-sets/2099.2.0:approve?name=fcd", "{}"), auditor, "AUDITOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForList("SELECT name, status FROM fcd.rule_set WHERE version = '2099.2.0' ORDER BY name"))
                .extracting(r -> r.get("name") + ":" + r.get("status"))
                .containsExactly("fcd:ACTIVE", "fcd-shadow:DRAFT");
    }

    @Test
    @DisplayName("월 예산 검토 큐(top-k / 1,000건): 30건 · k=100 → 예산 3건, 점수 상위만 케이스 · 실행 기록")
    void monthlyBudgetQueue() throws Exception {
        UUID emp = actor();
        String pan = syntheticPan();
        UUID card = registerCard(pan, emp, 100_000);
        List<String[]> rows = new java.util.ArrayList<>();
        for (int i = 0; i < 27; i++) {
            rows.add(new String[] {"2026.08.%02d".formatted(1 + i % 25), "12:%02d".formatted(i), "%08d".formatted(70000000 + i),
                    "(예시)국밥한그릇", "", "한식", "8,000"});
        }
        rows.add(new String[] {"2026.08.26", "23:30", "70000100", "(예시)골목포차", "", "일반주점", "99,000"});
        rows.add(new String[] {"2026.08.27", "22:00", "70000101", "(예시)나이트클럽", "", "유흥주점", "90,000"});
        rows.add(new String[] {"2026.08.29", "13:00", "70000102", "(예시)대형마트", "", "대형마트", "180,000"});
        commit(upload(acardCsv(last4(pan), rows), "july.csv", null, 201).get("id").asText());
        // FCD-13: 기본값(k=20)과 다른 k=100 은 재정의 — AUDITOR 가 사유와 함께
        JsonNode run = json(mvc.perform(as(postJson("/api/v1/fcd/review-queue/2026-08:run",
                "{\"policy\":\"MONTHLY_BUDGET\",\"kPer1000\":100,\"minScore\":30,\"reason\":\"합성 소량 월 점검\"}"),
                auditor, "AUDITOR")).andExpect(status().isOk()).andReturn());
        int n = run.get("nTxn").asInt();
        assertThat(run.get("budget").asInt()).isEqualTo((int) Math.rint(n * 100 / 1000.0));
        assertThat(run.get("selected").size()).isLessThanOrEqualTo(run.get("budget").asInt());
        String club = txnBy(card, "나이트클럽").get("id").toString();
        assertThat(run.get("selected").get(0).asText()).isEqualTo(club);             // 최고점(금지업종)이 첫 번째
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fcd.review_run WHERE period_ym = '2026-08'", Integer.class))
                .isEqualTo(1);
    }
}
