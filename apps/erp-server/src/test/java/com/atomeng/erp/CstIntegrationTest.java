package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 08 CST 통합 테스트 — HRQ 이벤트 → outbox Relay → CST 리스너 → 원가 원장 (계약 테스트의 E2E 판).
 * TC-CST-04·05·10·11(일부)·13·14, TC-HRQ-010·011·012(CST 측), BR-HRQ-013, FCD·EQP 구독 스텁, cst.voucher.synced.
 * 회계월은 다른 테스트(HRQ 2026년)와 겹치지 않게 2025년 월을 테스트별로 따로 쓴다. 단가·금액은 전부 합성값.
 */
class CstIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));
    /** 합성 표준 노임 일 단가(⚠️ 실제 노임단가 아님) */
    private static final Map<String, Long> STD = Map.of("JUNIOR", 200_000L, "INTER", 250_000L, "SENIOR", 300_000L,
            "MASTER", 380_000L, "UNGRADED", 150_000L);

    @Autowired
    OutboxRelay relay;

    UUID admin;
    UUID pm;

    @BeforeEach
    void setUp() {
        admin = actor();
        pm = actor();
        clock.reset();
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
        STD.forEach((grade, v) -> jdbc.update("""
                INSERT INTO cst.rate_table(id, rate_kind, key_code, value, valid_from, source_ref, created_by)
                SELECT gen_random_uuid(), 'STD_LABOR', ?, ?, DATE '2000-01-01', '합성 테스트 단가', gen_random_uuid()
                 WHERE NOT EXISTS (SELECT 1 FROM cst.rate_table WHERE rate_kind = 'STD_LABOR' AND key_code = ?)""",
                grade, v, grade));
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    // ------------------------------------------------------------------ helpers

    private UUID employee(String grade) throws Exception {
        UUID id = UUID.randomUUID();
        String empNo = "C-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성기술자', DATE '2020-01-01', ?, ?)", id, empNo, id, id);
        mvc.perform(as(postJson("/api/v1/hrq/employees", """
                        {"id":"%s","empNo":"%s","name":"합성기술자","field":"ARCH","employmentType":"FULLTIME",
                         "registeredField":false,"hiredOn":"2020-01-01","initialGrade":"%s","gradeFrom":"2020-01-01"}"""
                        .formatted(id, empNo, grade)), admin, "ADMIN"))
                .andExpect(status().isCreated());
        return id;
    }

    private UUID project() throws Exception {
        UUID client = createClient(actor(), "합성발주처-cst-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 CST 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String submit(UUID emp, String week, UUID project, String date, int hours) throws Exception {
        var r = mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + emp + "/weeks/" + week,
                "{\"entries\":[{\"projectId\":\"%s\",\"workDate\":\"%s\",\"workType\":\"FIELD\",\"hours\":%d}],\"submit\":true}"
                        .formatted(project, date, hours)), emp, "ENG")).andExpect(status().isOk()).andReturn();
        return json(r).get(0).get("id").asText();
    }

    private void approve(String id) throws Exception {
        mvc.perform(as(postJson("/api/v1/hrq/timesheets/approvals", "{\"entryIds\":[\"" + id + "\"]}"), pm, "PM"))
                .andExpect(status().isOk());
    }

    private void correct(String id, String body, UUID by, String role) throws Exception {
        mvc.perform(as(postJson("/api/v1/hrq/timesheets/" + id + ":correct", body)
                        .header("Idempotency-Key", UUID.randomUUID().toString()), by, role))
                .andExpect(status().isCreated());
    }

    private void hrqClose(String ym) throws Exception {
        mvc.perform(as(postJson("/api/v1/hrq/month-close/" + ym, "{}"), admin, "ADMIN")).andExpect(status().isOk());
    }

    private List<Map<String, Object>> ledger(UUID project) {
        return jdbc.queryForList("SELECT * FROM cst.cost_ledger_entry WHERE project_id = ? ORDER BY created_at, id",
                project);
    }

    private BigDecimal sum(UUID project, String col, String ym) {
        return jdbc.queryForObject("SELECT coalesce(sum(" + col + "), 0) FROM cst.cost_ledger_entry WHERE project_id = ?"
                + (ym == null ? "" : " AND period_ym = '" + ym + "'"), BigDecimal.class, project);
    }

    /** 같은 payload 를 새 eventId 로 다시 넣는다(at-least-once 재전달 모사) */
    private void redeliver(String type, UUID aggregate) {
        Timestamp now = Timestamp.from(clock.instant());
        int n = jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, event_version, payload, headers,
                                              occurred_at, next_attempt_at)
                SELECT gen_random_uuid(), aggregate_type, aggregate_id, event_type, event_version, payload, headers, ?, ?
                  FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?
                 ORDER BY occurred_at DESC LIMIT 1""", now, now, type, aggregate);
        assertThat(n).isEqualTo(1);
    }

    private UUID publishRaw(String type, String payload) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, event_version, payload, headers,
                                              occurred_at, next_attempt_at)
                VALUES (?, 'Stub', ?, ?, 1, ?::jsonb, '{}'::jsonb, ?, ?)""", id, UUID.randomUUID(), type, payload, now, now);
        return id;
    }

    private String tsKey(String entryId) {
        return jdbc.queryForObject("SELECT timesheet_key::text FROM hrq.timesheet_entry WHERE id = ?::uuid", String.class,
                entryId);
    }

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("TC-CST-13·14(중복) + TC-HRQ-011: confirmed 8h 전기(표준·실단가) → 8h→6h 정정 → REVERSAL −8h + TIMESHEET 6h, "
            + "같은 이벤트 재전달 → 원장 불변 / TC-HRQ-010: HRQ 마감 인·일 = CST 100% 일치, manday.closed 는 원장 미기록")
    void tcCst13_14_correctionReversalAndRepost() throws Exception {
        UUID emp = employee("SENIOR");
        UUID prj = project();
        jdbc.update("INSERT INTO cst.employee_cost_rate(employee_id, period_ym, daily_cost_krw, created_by) "
                + "VALUES (?, '202506', 400000, ?)", emp, admin);   // 합성 실단가
        String e1 = submit(emp, "2025-W24", prj, "2025-06-10", 8);
        approve(e1);
        relay.pollOnce();

        var rows = ledger(prj);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("source_type", "TIMESHEET").containsEntry("period_ym", "202506")
                .containsEntry("grade_code", "SENIOR").containsEntry("work_type", "FIELD");
        assertThat((BigDecimal) rows.get(0).get("amount_std_krw")).isEqualByComparingTo("300000");
        assertThat((BigDecimal) rows.get(0).get("amount_krw")).isEqualByComparingTo("400000");
        assertThat((BigDecimal) rows.get(0).get("qty")).isEqualByComparingTo("1");

        // 재전달(새 eventId, 같은 payload) → 행 수 불변
        redeliver("hrq.timesheet.confirmed", emp);
        relay.pollOnce();
        assertThat(ledger(prj)).hasSize(1);

        // TC-CST-13: 8h → 6h 정정
        correct(e1, "{\"hours\":6,\"reason\":\"현장 조기 종료(합성)\"}", pm, "PM");
        relay.pollOnce();
        rows = ledger(prj);
        assertThat(rows).hasSize(3);
        var rev = rows.get(1);
        var repost = rows.get(2);
        assertThat(rev).containsEntry("source_type", "REVERSAL").containsEntry("reversal_of", rows.get(0).get("id"))
                .containsEntry("period_ym", "202506");
        assertThat((BigDecimal) rev.get("hours")).isEqualByComparingTo("-8");
        assertThat((BigDecimal) rev.get("amount_std_krw")).isEqualByComparingTo("-300000");
        assertThat((BigDecimal) rev.get("amount_krw")).isEqualByComparingTo("-400000");
        assertThat(repost).containsEntry("source_type", "TIMESHEET").containsEntry("source_ref", tsKey(e1) + ":2");
        assertThat((BigDecimal) repost.get("hours")).isEqualByComparingTo("6");
        assertThat((BigDecimal) repost.get("amount_std_krw")).isEqualByComparingTo("225000");
        // 프로젝트 AC 순증감 = −2h 원가
        assertThat(sum(prj, "amount_std_krw", null)).isEqualByComparingTo("225000");
        assertThat(sum(prj, "amount_krw", null)).isEqualByComparingTo("300000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'cst.ledger.corrected' "
                + "AND aggregate_id = ?", Integer.class, prj)).isEqualTo(1);

        // TC-CST-14: 같은 corrected 이벤트 재전달 → 원장 3행 유지(역분개·재전기 각 1건)
        redeliver("hrq.timesheet.corrected", UUID.fromString(tsKey(e1)));
        relay.pollOnce();
        assertThat(ledger(prj)).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'hrq.timesheet.corrected' "
                + "AND published_at IS NULL", Integer.class)).isZero();

        // TC-HRQ-010: HRQ 월 마감 인·일 합계 vs CST 수신 합계 100% 일치, 대사는 원장에 쓰지 않는다(BR-HRQ-013)
        hrqClose("202506");
        relay.pollOnce();
        assertThat(ledger(prj)).hasSize(3);
        mvc.perform(as(get("/api/v1/cst/periods/202506"), admin, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reconciliation.status").value("MATCHED"))
                .andExpect(jsonPath("$.reconciliation.hrq_total_mandays").value(0.75))
                .andExpect(jsonPath("$.reconciliation.cst_total_mandays").value(0.75))
                .andExpect(jsonPath("$.hrqClosedAt").isNotEmpty());
        // 같은 manday.closed 재전달 → 대사 기록도 1건(멱등)
        redeliver("hrq.manday.closed", UUID.nameUUIDFromBytes("hrq-month:202506".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)));
        relay.pollOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.manday_reconciliation WHERE period_ym = '202506'",
                Integer.class)).isEqualTo(1);
        assertThat(ledger(prj)).hasSize(3);

        // CST 월 마감: 체크리스트 통과 → CLOSED + cst.period.closed
        mvc.perform(as(postJson("/api/v1/cst/periods/202506:close", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'cst.period.closed'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-CST-14(순서 역전): corrected 가 원 confirmed 보다 먼저 도착 → CST-409-003 재시도 보류 → 원 행 전기 후 정상 처리")
    void tcCst14_correctionBeforeOriginal() throws Exception {
        UUID emp = employee("INTER");
        UUID prj = project();
        String e1 = submit(emp, "2025-W28", prj, "2025-07-08", 8);
        approve(e1);
        correct(e1, "{\"hours\":5,\"reason\":\"순서 역전 시험(합성)\"}", pm, "PM");
        // 정정 이벤트를 원 이벤트보다 앞으로 당겨 순서 역전 모사
        jdbc.update("UPDATE core.outbox_event SET occurred_at = occurred_at - interval '1 hour' "
                + "WHERE event_type = 'hrq.timesheet.corrected' AND aggregate_id = ?::uuid", tsKey(e1));

        var poll = relay.pollOnce();
        assertThat(poll.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM core.outbox_event WHERE event_type = 'hrq.timesheet.corrected' "
                + "AND aggregate_id = ?::uuid", String.class, tsKey(e1))).contains("CST-409-003");
        var rows = ledger(prj);
        assertThat(rows).hasSize(1);   // 원 confirmed 만 전기, 정정은 보류(롤백)
        assertThat((BigDecimal) rows.get(0).get("amount_std_krw")).isEqualByComparingTo("250000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event WHERE consumer = 'cst.labor-corrector'"
                + " AND event_id = (SELECT id FROM core.outbox_event WHERE event_type = 'hrq.timesheet.corrected'"
                + " AND aggregate_id = ?::uuid)", Integer.class, tsKey(e1))).isZero();

        clock.advance(Duration.ofSeconds(11));   // 1차 백오프 10초 경과
        relay.pollOnce();
        rows = ledger(prj);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).containsEntry("source_type", "REVERSAL");
        assertThat((BigDecimal) rows.get(2).get("hours")).isEqualByComparingTo("5");
        assertThat(sum(prj, "amount_std_krw", null)).isEqualByComparingTo("156250");   // 5/8 × 250,000
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_dead", Integer.class)).isZero();
    }

    @Test
    @DisplayName("TC-CST-05 + TC-HRQ-012: 마감된 9월 정정(ADMIN adjustment) → 10월 귀속 역분개+재기록, 9월 합계 불변, "
            + "9월 직접 INSERT 는 CST-409-002 / TC-HRQ-010(조정분): 10월 대사 일치, SYSADMIN 재오픈")
    void tcCst05_closedMonthCorrection() throws Exception {
        UUID emp = employee("SENIOR");
        UUID prj = project();
        String e1 = submit(emp, "2025-W38", prj, "2025-09-16", 8);
        approve(e1);
        relay.pollOnce();
        BigDecimal sept = sum(prj, "amount_std_krw", "202509");
        assertThat(sept).isEqualByComparingTo("300000");

        // 체크리스트 미충족 → 마감 차단
        mvc.perform(as(postJson("/api/v1/cst/periods/202509:close", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(false))
                .andExpect(jsonPath("$.blockers[0]").value("HRQ_MONTH_NOT_CLOSED"));
        hrqClose("202509");
        relay.pollOnce();
        mvc.perform(as(postJson("/api/v1/cst/periods/202509:close", "{}"), actor(), "PM"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/cst/periods/202509:close", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.closed").value(true));

        // HRQ ADMIN 조정 정정(8h→6h) → posting_period 202510
        correct(e1, "{\"hours\":6,\"reason\":\"마감 후 조정(합성)\",\"adjustment\":true}", admin, "ADMIN");
        relay.pollOnce();
        var rows = ledger(prj);
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1)).containsEntry("source_type", "REVERSAL").containsEntry("period_ym", "202510");
        assertThat(rows.get(2)).containsEntry("source_type", "TIMESHEET").containsEntry("period_ym", "202510");
        assertThat(rows.get(2).get("posting_date").toString()).isEqualTo("2025-09-16");   // 원 근무일 보존
        assertThat(sum(prj, "amount_std_krw", "202509")).isEqualByComparingTo(sept);      // 9월 합계 불변
        assertThat(sum(prj, "amount_std_krw", "202510")).isEqualByComparingTo("-75000");  // −300,000 + 225,000

        // 마감 월 직접 INSERT → DB 거부 (NFR-CST-05)
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO cst.cost_ledger_entry(id, project_id, posting_date, period_ym, source_type, source_id,
                       cost_element, amount_krw, amount_std_krw, orig_amount, created_by)
                VALUES (gen_random_uuid(), ?, DATE '2025-09-30', '202509', 'ADJUST', gen_random_uuid(), 'OTHER', 1, 1, 1,
                        gen_random_uuid())""", prj)).hasMessageContaining("CST-409-002");
        // TC-CST-04: 원장 UPDATE 시도 → DB 예외
        assertThatThrownBy(() -> jdbc.update("UPDATE cst.cost_ledger_entry SET amount_krw = 0 WHERE project_id = ?", prj))
                .hasMessageContaining("CST-409-001");

        // 10월 HRQ 마감 대사: HRQ 는 현재 리비전 6h, CST 는 다른 월 원본의 조정 역분개 제외 → 일치
        hrqClose("202510");
        relay.pollOnce();
        mvc.perform(as(get("/api/v1/cst/periods/202510"), admin, "ADMIN"))
                .andExpect(jsonPath("$.reconciliation.status").value("MATCHED"))
                .andExpect(jsonPath("$.reconciliation.cst_total_mandays").value(0.75));

        // 재오픈은 SYSADMIN + 사유
        mvc.perform(as(postJson("/api/v1/cst/periods/202509:reopen", "{\"reason\":\"합성 재오픈\"}"), admin, "ADMIN"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/cst/periods/202509:reopen", "{\"reason\":\"합성 재오픈\"}"), actor(), "SYSADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REOPENED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'CST' "
                + "AND target_type = 'AccountingPeriod'", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("FCD 스텁: fcd.expense.posted → EXPENSE 행 + cst.voucher.synced(SYNCED), 재전달 멱등, reversed → 역분개 / "
            + "EQP 스텁: eqp.equipment.returned → 일수 × EQUIP_DAILY / 요율 API 권한·구간 검증")
    void fcdAndEqpStubs() throws Exception {
        UUID prj = project();
        UUID report = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        String posted = """
                {"expenseReportId":"%s","lineId":"%s","projectId":"%s","wbsCode":null,"costElement":"LODGING",
                 "amountKrw":70000,"vatKrw":7000,"txnDate":"2025-11-14","cardTxnKey":"SYN-CARD-0001"}"""
                .formatted(report, line, prj);
        // 원 행보다 먼저 온 취소 → 재시도 보류
        publishRaw("fcd.expense.reversed", "{\"expenseReportId\":\"%s\",\"lineId\":\"%s\",\"reason\":\"합성\",\"reversedOn\":\"2025-11-20\"}"
                .formatted(report, line));
        assertThat(relay.pollOnce().failed()).isEqualTo(1);
        publishRaw("fcd.expense.posted", posted);
        relay.pollOnce();
        var rows = ledger(prj);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("source_type", "EXPENSE").containsEntry("cost_element", "LODGING")
                .containsEntry("period_ym", "202511");
        assertThat((BigDecimal) rows.get(0).get("amount_krw")).isEqualByComparingTo("70000");   // 부가세 제외
        JsonNode synced = objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event "
                + "WHERE event_type = 'cst.voucher.synced' AND aggregate_id = ?", String.class, report));
        assertThat(synced.get("status").asText()).isEqualTo("SYNCED");
        assertThat(synced.get("kind").asText()).isEqualTo("POSTED");
        assertThat(synced.get("douzoneDocNo").asText()).startsWith("DZ-STUB-202511-");
        assertThat(synced.get("lineIds").get(0).asText()).isEqualTo(line.toString());

        publishRaw("fcd.expense.posted", posted);   // 재전달(새 eventId)
        relay.pollOnce();
        assertThat(ledger(prj)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'cst.voucher.synced' "
                + "AND aggregate_id = ?", Integer.class, report)).isEqualTo(1);

        clock.advance(Duration.ofSeconds(11));
        relay.pollOnce();   // 보류된 취소 재시도
        rows = ledger(prj);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("source_type", "REVERSAL").containsEntry("reversal_of", rows.get(0).get("id"));
        assertThat(sum(prj, "amount_krw", null)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.voucher_sync WHERE expense_report_id = ? AND kind = "
                + "'REVERSED'", Integer.class, report)).isEqualTo(1);

        // 요율 API: PM 403, ADMIN 등록, 같은 키 이전 시작일 422, 이후 시작일이면 직전 행 자동 마감
        String code = "EQ-SYN-" + SEQ.incrementAndGet();
        String body = "{\"rateKind\":\"EQUIP_DAILY\",\"keyCode\":\"%s\",\"value\":%s,\"validFrom\":\"%s\",\"sourceRef\":\"합성 손료\"}";
        mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(code, 150000, "2025-01-01")), pm, "PM"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(code, 150000, "2025-01-01")), admin, "ADMIN"))
                .andExpect(status().isCreated());
        mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(code, 160000, "2024-12-01")), admin, "ADMIN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CST-422-001"));
        mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(code, 170000, "2025-12-01")), admin, "ADMIN"))
                .andExpect(status().isCreated());
        mvc.perform(as(get("/api/v1/cst/rates?kind=EQUIP_DAILY"), admin, "ADMIN"))
                .andExpect(jsonPath("$[?(@.keyCode == '" + code + "' && @.validFrom == '2025-01-01')].validTo")
                        .value(org.hamcrest.Matchers.contains("2025-12-01")));

        UUID rental = UUID.randomUUID();
        publishRaw("eqp.equipment.returned", ("{\"rentalId\":\"%s\",\"equipmentId\":\"%s\",\"equipmentCode\":\"%s\","
                + "\"projectId\":\"%s\",\"days\":3,\"returnedOn\":\"2025-11-20\"}").formatted(rental, UUID.randomUUID(),
                code, prj));
        relay.pollOnce();
        var eq = jdbc.queryForMap("SELECT * FROM cst.cost_ledger_entry WHERE source_id = ?", rental);
        assertThat(eq).containsEntry("source_type", "EQUIP").containsEntry("cost_element", "EQUIP_USE");
        assertThat((BigDecimal) eq.get("amount_krw")).isEqualByComparingTo("450000");
    }

    @Test
    @DisplayName("TC-CST-10(API): Baseline·진척·원장으로 EVM → CPI 0.971·SPI 0.987·YELLOW(WBS_OVERRUN), 스냅샷 저장 / "
            + "TC-CST-11(일부): PM 은 표준단가 기준·실단가 열 마스킹 / BR-CST-013 진척 ±15%p")
    void tcCst10_evmApiAndMasking() throws Exception {
        UUID prj = project();
        UUID pmEmp = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) VALUES "
                + "(?, ?, '합성PM', DATE '2020-01-01', ?, ?)", pmEmp, "C-PM-" + SEQ.incrementAndGet(), pmEmp, pmEmp);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, 'RESP', DATE '2020-01-01', DATE '2099-12-31')", prj, pmEmp);
        UUID rev = UUID.randomUUID();
        jdbc.update("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, created_by) "
                + "VALUES (?, ?, 0, 'APPROVED', 148000000, 185000000, ?)", rev, prj, admin);
        java.time.LocalDate s = java.time.LocalDate.of(2025, 3, 1);
        java.time.LocalDate asOf = s.plusDays(50);
        Map<String, UUID> wbs = new java.util.LinkedHashMap<>();
        int order = 0;
        for (String code : List.of("10-FIELD", "20-MTEST", "30-ANALYSIS")) {
            UUID id = UUID.randomUUID();
            wbs.put(code, id);
            jdbc.update("INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, sort_order) VALUES "
                    + "(?, ?, ?, ?, 0, ?)", id, prj, code, code, ++order);
        }
        String lineSql = "INSERT INTO cst.budget_line(id, revision_id, wbs_id, cost_element, qty, unit_cost_krw, "
                + "planned_start, planned_end) VALUES (gen_random_uuid(), ?, ?, 'LABOR', 1, ?, ?, ?)";
        jdbc.update(lineSql, rev, wbs.get("10-FIELD"), 100_000_000, s, s.plusDays(100));
        jdbc.update(lineSql, rev, wbs.get("20-MTEST"), 40_000_000, s, s.plusDays(100));
        jdbc.update(lineSql, rev, wbs.get("30-ANALYSIS"), 200_000, s, s.plusDays(100));
        jdbc.update(lineSql, rev, wbs.get("30-ANALYSIS"), 7_800_000, asOf.plusDays(1), asOf.plusDays(30));
        String led = "INSERT INTO cst.cost_ledger_entry(id, project_id, wbs_id, posting_date, period_ym, source_type, "
                + "source_id, cost_element, amount_krw, amount_std_krw, orig_amount, created_by) VALUES "
                + "(gen_random_uuid(), ?, ?, ?, '202504', 'ADJUST', gen_random_uuid(), 'LABOR', ?, ?, ?, gen_random_uuid())";
        jdbc.update(led, prj, wbs.get("10-FIELD"), asOf.minusDays(1), 52_000_000, 46_800_000, 52_000_000);
        jdbc.update(led, prj, wbs.get("20-MTEST"), asOf.minusDays(1), 9_860_000, 8_874_000, 9_860_000);
        jdbc.update(led, prj, wbs.get("30-ANALYSIS"), asOf.minusDays(1), 9_440_000, 8_496_000, 9_440_000);
        jdbc.update(led, prj, null, asOf.plusDays(5), 1_000_000, 900_000, 1_000_000);   // asOf 이후 → AC 제외

        String progress = "{\"wbsId\":\"%s\",\"pctComplete\":%s,\"asOf\":\"%s\",\"reason\":\"합성 진척\"}";
        UUID exec = actor();
        // BR-CST-013: PM 은 직전 대비 ±15%p 초과 보정 불가, EXEC 는 가능
        mvc.perform(as(postJson("/api/v1/cst/projects/" + prj + "/progress",
                        progress.formatted(wbs.get("10-FIELD"), 50, asOf)), pmEmp, "PM"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CST-422-013"));
        for (var p : List.of(Map.entry("10-FIELD", 50), Map.entry("20-MTEST", 40), Map.entry("30-ANALYSIS", 30))) {
            mvc.perform(as(postJson("/api/v1/cst/projects/" + prj + "/progress",
                            progress.formatted(wbs.get(p.getKey()), p.getValue(), asOf)), exec, "EXEC"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(as(postJson("/api/v1/cst/projects/" + prj + "/progress",
                        progress.formatted(wbs.get("30-ANALYSIS"), 40, asOf)), pmEmp, "PM"))
                .andExpect(status().isCreated());   // +10%p 는 PM 가능

        mvc.perform(as(get("/api/v1/cst/projects/" + prj + "/evm?asOf=" + asOf), exec, "EXEC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acBasis").value("REAL"))
                .andExpect(jsonPath("$.result.pv").value(70100000))
                .andExpect(jsonPath("$.result.ev").value(69200000))
                .andExpect(jsonPath("$.result.ac").value(71300000))
                .andExpect(jsonPath("$.result.cpi").value(0.971))
                .andExpect(jsonPath("$.result.spi").value(0.987))
                .andExpect(jsonPath("$.result.tcpi").value(1.027))
                .andExpect(jsonPath("$.assessment.level").value("YELLOW"))
                .andExpect(jsonPath("$.assessment.alerts[0].code").value("WBS_OVERRUN"))
                .andExpect(jsonPath("$.assessment.alerts[0].wbs").value("30-ANALYSIS"));
        var snap = jdbc.queryForMap("SELECT * FROM cst.evm_snapshot WHERE project_id = ? AND as_of = ?", prj, asOf);
        assertThat((BigDecimal) snap.get("cpi")).isEqualByComparingTo("0.971");
        assertThat(snap).containsEntry("alert_level", "YELLOW");
        assertThat((BigDecimal) snap.get("ac_std")).isEqualByComparingTo("64170000");

        // PM: 표준단가 AC 기준으로 재계산 응답, 원장 실단가 열 마스킹
        mvc.perform(as(get("/api/v1/cst/projects/" + prj + "/evm?asOf=" + asOf), pmEmp, "PM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acBasis").value("STANDARD"))
                .andExpect(jsonPath("$.result.ac").value(64170000));
        mvc.perform(as(get("/api/v1/cst/projects/" + prj + "/ledger"), pmEmp, "PM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].amountKrw").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[0].amountKrwMasked").value(true))
                .andExpect(jsonPath("$[0].amountStdKrw").isNumber());
        mvc.perform(as(get("/api/v1/cst/projects/" + prj + "/ledger"), admin, "ADMIN"))
                .andExpect(jsonPath("$[0].amountKrw").isNumber());
        // 담당 외 PM·ENG 는 거부
        mvc.perform(as(get("/api/v1/cst/projects/" + prj + "/evm"), actor(), "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(get("/api/v1/cst/projects/" + prj + "/ledger"), actor(), "ENG"))
                .andExpect(status().isForbidden());
    }
}
