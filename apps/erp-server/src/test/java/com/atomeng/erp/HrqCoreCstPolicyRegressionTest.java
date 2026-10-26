package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 보안 검토 2026-10-19 §2 정책 결정(권장안) 회귀 시험 — HRQ-01 · 02 · 03 · 06 · 07, CORE-02, CST-01 · 02, SYSADMIN 회계기간 조회.
 * 모든 데이터는 합성값.
 */
class HrqCoreCstPolicyRegressionTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));
    /** 2024-02-12(월) — 열린 과거 월(다른 시험이 마감하지 않는 달) */
    private static final String WEEK = "2024-W07";
    private static final String MON = "2024-02-12";

    UUID admin;

    @BeforeEach
    void setUp() {
        admin = actor();
        clock.reset();
    }

    // ------------------------------------------------------------------ helpers

    private void coreEmployee(UUID id) {
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성직원', DATE '2020-01-01', ?, ?) ON CONFLICT (id) DO NOTHING", id,
                "R-" + id.toString().substring(0, 13), id, id);
    }

    /** core + hrq 기술자 등록(ADMIN, 합성) */
    private UUID engineer() throws Exception {
        UUID id = UUID.randomUUID();
        String empNo = "RG-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성기술자', DATE '2020-01-01', ?, ?)", id, empNo, id, id);
        mvc.perform(as(postJson("/api/v1/hrq/employees", """
                        {"id":"%s","empNo":"%s","name":"합성기술자","field":"ARCH","employmentType":"FULLTIME",
                         "registeredField":false,"hiredOn":"2020-01-01","initialGrade":"SENIOR","gradeFrom":"2020-01-01"}"""
                        .formatted(id, empNo)), admin, "ADMIN"))
                .andExpect(status().isCreated());
        return id;
    }

    private UUID project() throws Exception {
        UUID client = createClient(actor(), "합성발주처-pol-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 정책 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), actor(), "PM")).andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private void member(UUID project, UUID emp, String role) {
        coreEmployee(emp);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, ?, DATE '2020-01-01', DATE '2035-12-31') ON CONFLICT DO NOTHING", project, emp, role);
    }

    private MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String entry(UUID project, String date, int hours) {
        return "{\"projectId\":\"%s\",\"workDate\":\"%s\",\"workType\":\"FIELD\",\"hours\":%d}".formatted(project, date,
                hours);
    }

    private JsonNode saveWeek(UUID emp, String roles, String entries) throws Exception {
        return json(mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + emp + "/weeks/" + WEEK,
                "{\"entries\":[" + entries + "],\"submit\":true}"), emp, roles)).andExpect(status().isOk()).andReturn());
    }

    private MockHttpServletRequestBuilder approval(String body) {
        return postJson("/api/v1/hrq/timesheets/approvals", body);
    }

    private MockHttpServletRequestBuilder correct(String entryId, String body) {
        return postJson("/api/v1/hrq/timesheets/" + entryId + ":correct", body)
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private int events(String type, UUID aggregate) {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, type, aggregate);
    }

    private UUID tsKey(String entryId) {
        return jdbc.queryForObject("SELECT timesheet_key FROM hrq.timesheet_entry WHERE id = ?::uuid", UUID.class,
                entryId);
    }

    // ------------------------------------------------------------------ HRQ-01

    @Test
    @DisplayName("HRQ-01: 비 RESP PM 은 타임시트 승인 403(HRQ-403-001), 그 프로젝트 RESP 는 승인(approvalMode RESP)")
    void hrq01_onlyResponsibleApproves() throws Exception {
        UUID eng = engineer();
        UUID prj = project();
        UUID resp = actor();
        UUID partPm = actor();
        member(prj, eng, "PART");
        member(prj, resp, "RESP");
        member(prj, partPm, "PART");
        String id = saveWeek(eng, "ENG", entry(prj, MON, 8)).get(0).get("id").asText();

        mvc.perform(as(approval("{\"entryIds\":[\"" + id + "\"]}"), partPm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-001"));
        mvc.perform(as(approval("{\"entryIds\":[\"" + id + "\"]}"), actor(), "PM"))   // 프로젝트와 무관한 PM
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-001"));
        assertThat(events("hrq.timesheet.confirmed", eng)).isZero();

        mvc.perform(as(approval("{\"entryIds\":[\"" + id + "\"]}"), resp, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.delegated").value(0));
        mvc.perform(as(get("/api/v1/hrq/timesheets/" + eng + "/weeks/" + WEEK), resp, "PM"))
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].approvalMode").value("RESP"))
                .andExpect(jsonPath("$[0].approvedBy").value(resp.toString()));
        assertThat(events("hrq.timesheet.confirmed", eng)).isEqualTo(1);
    }

    @Test
    @DisplayName("HRQ-01: PM+ENG 를 함께 가진 RESP 도 본인 타임시트는 승인 403(HRQ-403-002), EXEC · ADMIN 대리 승인도 본인 것은 403")
    void hrq01_noSelfApproval() throws Exception {
        UUID respEng = engineer();
        UUID prj = project();
        member(prj, respEng, "RESP");
        String id = saveWeek(respEng, "PM,ENG", entry(prj, MON, 8)).get(0).get("id").asText();

        mvc.perform(as(approval("{\"entryIds\":[\"" + id + "\"]}"), respEng, "PM,ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-002"));
        mvc.perform(as(approval("{\"entryIds\":[\"" + id + "\"],\"delegationReason\":\"본인 대리 승인 시도(합성)\"}"),
                        respEng, "PM,ENG,EXEC,ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-002"));
        assertThat(jdbc.queryForObject("SELECT status FROM hrq.timesheet_entry WHERE id = ?::uuid", String.class, id))
                .isEqualTo("SUBMITTED");
    }

    @Test
    @DisplayName("HRQ-01: EXEC · ADMIN 대리 승인은 사유(5자 이상) 필수 422(HRQ-422-002) → 사유와 함께 DELEGATED 로 기록 · 응답 · 이력 · 감사")
    void hrq01_delegatedApprovalNeedsReason() throws Exception {
        UUID eng = engineer();
        UUID prj = project();
        member(prj, eng, "PART");
        JsonNode rows = saveWeek(eng, "ENG", entry(prj, MON, 8) + "," + entry(prj, "2024-02-13", 4));
        String e1 = rows.get(0).get("id").asText();
        String e2 = rows.get(1).get("id").asText();
        UUID exec = actor();

        mvc.perform(as(approval("{\"entryIds\":[\"" + e1 + "\"]}"), exec, "EXEC"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-002"));
        mvc.perform(as(approval("{\"entryIds\":[\"" + e1 + "\"],\"delegationReason\":\" 짧음 \"}"), exec, "EXEC"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-002"));
        mvc.perform(as(approval("{\"entryIds\":[\"" + e1 + "\"],\"delegationReason\":\"책임기술자 장기 출장(합성)\"}"),
                        exec, "EXEC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.approved").value(1))
                .andExpect(jsonPath("$.delegated").value(1));
        mvc.perform(as(approval("{\"entryIds\":[\"" + e2 + "\"],\"delegationReason\":\"책임기술자 퇴사 공백(합성)\"}"),
                        actor(), "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.delegated").value(1));

        mvc.perform(as(get("/api/v1/hrq/timesheets/keys/" + tsKey(e1) + "/revisions"), exec, "EXEC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].approvalMode").value("DELEGATED"))
                .andExpect(jsonPath("$[0].delegationReason").value("책임기술자 장기 출장(합성)"))
                .andExpect(jsonPath("$[0].approvedBy").value(exec.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'HRQ' AND target_type = "
                + "'TimesheetDelegatedApproval' AND target_id = ?", Integer.class, tsKey(e1))).isEqualTo(1);
        assertThat(events("hrq.timesheet.confirmed", eng)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ HRQ-02

    @Test
    @DisplayName("HRQ-02: ENG 가 승인된 본인 시간을 정정 → SUBMITTED 리비전(재승인 전 corrected 이벤트 없음), RESP 재승인 때 역분개 · 재전기 이벤트 / 비 RESP PM 정정 403")
    void hrq02_engCorrectionNeedsReapproval() throws Exception {
        UUID eng = engineer();
        UUID prj = project();
        UUID resp = actor();
        UUID partPm = actor();
        member(prj, eng, "PART");
        member(prj, resp, "RESP");
        member(prj, partPm, "PART");
        String id = saveWeek(eng, "ENG", entry(prj, MON, 4)).get(0).get("id").asText();
        mvc.perform(as(approval("{\"entryIds\":[\"" + id + "\"]}"), resp, "PM")).andExpect(status().isOk());
        UUID key = tsKey(id);

        // 비 RESP PM 은 남의 타임시트 정정 불가
        mvc.perform(as(correct(id, "{\"hours\":6,\"reason\":\"합성 정정\"}"), partPm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-001"));

        // ENG 본인 정정 4h → 24h: 바로 APPROVED 가 아니라 SUBMITTED 리비전
        var rev2 = json(mvc.perform(as(correct(id, "{\"hours\":24,\"reason\":\"본인 정정(합성)\"}"), eng, "ENG"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andReturn());
        assertThat(events("hrq.timesheet.corrected", key)).isZero();   // CST 역분개 · 재전기 없음
        // 주간 다시 저장해도 재승인 대기 리비전은 지워지지 않는다
        mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + eng + "/weeks/" + WEEK, "{\"entries\":[],\"submit\":false}"),
                eng, "ENG")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrq.timesheet_entry WHERE timesheet_key = ?", Integer.class,
                key)).isEqualTo(2);
        // 승인 대기가 있으면 그 달 마감 불가
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202402", "{}"), admin, "ADMIN"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HRQ-409-004"));
        // 본인 재승인 불가 → RESP 재승인 → corrected 이벤트(이전 4h → 24h)
        String rev2Id = rev2.get("id").asText();
        mvc.perform(as(approval("{\"entryIds\":[\"" + rev2Id + "\"]}"), eng, "ENG,PM"))
                .andExpect(status().isForbidden());
        mvc.perform(as(approval("{\"entryIds\":[\"" + rev2Id + "\"]}"), resp, "PM")).andExpect(status().isOk());
        assertThat(events("hrq.timesheet.corrected", key)).isEqualTo(1);
        JsonNode ev = objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event "
                + "WHERE event_type = 'hrq.timesheet.corrected' AND aggregate_id = ?", String.class, key));
        assertThat(ev.get("previousHours").decimalValue()).isEqualByComparingTo("4");
        assertThat(ev.get("newHours").decimalValue()).isEqualByComparingTo("24");
        assertThat(ev.get("correctedBy").asText()).isEqualTo(eng.toString());
        assertThat(ev.get("idempotencyKey").asText()).isEqualTo(key + ":2");
        assertThat(events("hrq.timesheet.confirmed", eng)).isEqualTo(1);   // 원 승인 1건뿐(재승인은 corrected)

        // RESP(본인 아님)의 정정은 바로 확정 + corrected 이벤트
        mvc.perform(as(correct(rev2Id, "{\"hours\":7,\"reason\":\"RESP 정정(합성)\"}"), resp, "PM"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(events("hrq.timesheet.corrected", key)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ HRQ-03

    @Test
    @DisplayName("HRQ-03: 근무일에 투입되지 않은 프로젝트 422(HRQ-422-003), 하루 합계(프로젝트 합산) 24h 초과 422(HRQ-422-004)")
    void hrq03_assignmentAndDailyLimit() throws Exception {
        UUID eng = engineer();
        UUID p1 = project();
        UUID p2 = project();
        UUID notAssigned = project();
        member(p1, eng, "PART");
        member(p2, eng, "SUPPORT");
        mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + eng + "/weeks/" + WEEK,
                        "{\"entries\":[" + entry(notAssigned, MON, 4) + "],\"submit\":true}"), eng, "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-003"));
        // 투입 기간 밖 근무일
        UUID p3 = project();
        coreEmployee(eng);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, 'PART', DATE '2024-02-13', DATE '2024-12-31')", p3, eng);
        mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + eng + "/weeks/" + WEEK,
                        "{\"entries\":[" + entry(p3, MON, 4) + "],\"submit\":true}"), eng, "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-003"));

        mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + eng + "/weeks/" + WEEK,
                        "{\"entries\":[" + entry(p1, MON, 16) + "," + entry(p2, MON, 10) + "],\"submit\":true}"), eng,
                        "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-004"));
        JsonNode ok = saveWeek(eng, "ENG", entry(p1, MON, 16) + "," + entry(p2, MON, 8));
        assertThat(ok.size()).isEqualTo(2);
        // 확정 행 + 새 입력도 합산: p1 16h 승인 후 같은 날 p2 9h → 25h 거부
        UUID resp = actor();
        member(p1, resp, "RESP");
        mvc.perform(as(approval("{\"entryIds\":[\"" + ok.get(0).get("id").asText() + "\"]}"), resp, "PM"))
                .andExpect(status().isOk());
        mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + eng + "/weeks/" + WEEK,
                        "{\"entries\":[" + entry(p2, MON, 9) + "],\"submit\":true}"), eng, "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-004"));
        // 정정도 상한 적용: 승인 행 p1 16h + 제출 p2 8h 상태에서 p1 을 17h 로 → 25h 거부
        saveWeek(eng, "ENG", entry(p2, MON, 8));
        mvc.perform(as(correct(ok.get(0).get("id").asText(), "{\"hours\":17,\"reason\":\"합성 정정\"}"), resp, "PM"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("HRQ-422-004"));
    }

    // ------------------------------------------------------------------ HRQ-06

    @Test
    @DisplayName("HRQ-06: ADMIN 은 본인 자격 승인 · 반려 403(HRQ-403-002) · 본인 등급 변경 403, 다른 ADMIN 은 가능")
    void hrq06_noSelfCredentialOrGrade() throws Exception {
        UUID self = engineer();   // ADMIN 역할도 가진 직원
        var r = mvc.perform(as(postJson("/api/v1/hrq/employees/" + self + "/credentials",
                        "{\"typeCode\":\"ARCH_ENG\",\"certNo\":\"SYN-SELF\",\"acquiredOn\":\"2019-01-01\"}"), self, "ADMIN"))
                .andExpect(status().isCreated()).andReturn();
        String cred = json(r).get("id").asText();
        mvc.perform(as(postJson("/api/v1/hrq/credentials/" + cred + ":approve", "{}"), self, "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-002"));
        mvc.perform(as(postJson("/api/v1/hrq/credentials/" + cred + ":reject", "{\"reason\":\"본인 반려 시도\"}"), self,
                        "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-002"));
        mvc.perform(as(postJson("/api/v1/hrq/credentials/" + cred + ":approve", "{}"), actor(), "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VALID"));

        mvc.perform(as(postJson("/api/v1/hrq/employees/" + self + "/grades",
                        "{\"grade\":\"MASTER\",\"validFrom\":\"2026-12-01\"}"), self, "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-002"));
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + self + "/grades",
                        "{\"grade\":\"MASTER\",\"validFrom\":\"2026-12-01\"}"), actor(), "ADMIN"))
                .andExpect(status().isCreated());
        // 본인을 초기 등급과 함께 등록하는 것도 거부
        UUID me = UUID.randomUUID();
        mvc.perform(as(postJson("/api/v1/hrq/employees", """
                        {"id":"%s","empNo":"RS-%d","name":"합성본인","field":"ARCH","employmentType":"FULLTIME",
                         "registeredField":false,"hiredOn":"2020-01-01","initialGrade":"MASTER"}"""
                        .formatted(me, SEQ.incrementAndGet())), me, "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("HRQ-403-002"));
    }

    // ------------------------------------------------------------------ HRQ-07

    @Test
    @DisplayName("HRQ-07: 월 마감은 ADMIN, 재오픈은 hrq:month-close:reopen(SYSADMIN)만 — ADMIN 재오픈 403, SYSADMIN 마감 403")
    void hrq07_reopenSeparated() throws Exception {
        String ym = "2019" + String.format("%02d", 1 + SEQ.incrementAndGet() % 12);
        mvc.perform(as(postJson("/api/v1/hrq/month-close/" + ym, "{}"), actor(), "SYSADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-001"));
        var close = mvc.perform(as(postJson("/api/v1/hrq/month-close/" + ym, "{}"), admin, "ADMIN")).andReturn();
        assertThat(close.getResponse().getStatus()).isIn(200, 409);   // 같은 달을 다른 실행이 이미 마감했을 수 있음
        mvc.perform(as(postJson("/api/v1/hrq/month-close/" + ym + ":reopen", "{\"reason\":\"합성 재오픈 사유\"}"), admin,
                        "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-001"));
        mvc.perform(as(postJson("/api/v1/hrq/month-close/" + ym + ":reopen", "{\"reason\":\"합성 재오픈 사유\"}"),
                        actor(), "SYSADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REOPENED"));
        mvc.perform(as(get("/api/v1/hrq/month-close"), actor(), "SYSADMIN")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ CORE-02

    @Test
    @DisplayName("CORE-02: PM(PROJECT 범위)은 RESP 지정 · 본인 투입 변경 403(CORE-403-011), PART · QA · SUPPORT 는 남에게만, EXEC 는 RESP 지정")
    void core02_pmCannotAssignRespOrSelf() throws Exception {
        UUID prj = project();
        UUID pm = actor();
        UUID other = actor();
        coreEmployee(pm);
        coreEmployee(other);
        String today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).toString();
        String later = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).plusDays(30).toString();
        String far = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul"))).plusYears(5).toString();
        String body = "{\"employeeId\":\"%s\",\"roleInProject\":\"%s\",\"fromDate\":\"" + today + "\",\"toDate\":\"%s\"}";
        mvc.perform(as(postJson("/api/v1/core/projects/" + prj + "/members", body.formatted(pm, "RESP", later)),
                        actor(), "EXEC"))
                .andExpect(status().isCreated());
        // PM 이 다른 사람을 RESP 로(pm_id 변경) → 403
        mvc.perform(as(postJson("/api/v1/core/projects/" + prj + "/members", body.formatted(other, "RESP", later)), pm,
                        "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-011"));
        // 본인 RESP 기간 연장 · 본인 PART 추가 → 403
        mvc.perform(as(postJson("/api/v1/core/projects/" + prj + "/members", body.formatted(pm, "RESP", far)), pm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-011"));
        mvc.perform(as(postJson("/api/v1/core/projects/" + prj + "/members", body.formatted(pm, "PART", far)), pm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-011"));
        // 남을 PART · QA · SUPPORT 로는 가능
        for (String role : new String[] {"PART", "QA", "SUPPORT"}) {
            mvc.perform(as(postJson("/api/v1/core/projects/" + prj + "/members", body.formatted(other, role, later)), pm,
                    "PM")).andExpect(status().isCreated());
        }
        // pm_id 는 EXEC 지정 그대로, 기간 연장 없음
        assertThat(jdbc.queryForObject("SELECT pm_id FROM core.project WHERE id = ?", UUID.class, prj)).isEqualTo(pm);
        assertThat(jdbc.queryForObject("SELECT to_date::text FROM core.project_member WHERE project_id = ? AND "
                + "employee_id = ? AND role_in_project = 'RESP'", String.class, prj, pm)).isEqualTo(later);
        // EXEC(ALL)은 RESP 교체 가능
        mvc.perform(as(postJson("/api/v1/core/projects/" + prj + "/members", body.formatted(other, "RESP", later)),
                        actor(), "EXEC"))
                .andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT pm_id FROM core.project WHERE id = ?", UUID.class, prj)).isEqualTo(other);
    }

    // ------------------------------------------------------------------ CST-01

    /** SYSTEM 이 만든 Rev.0 DRAFT(라인 1개, BAC 1,000,000)와 같은 모양의 합성 판 */
    private UUID draftBudget(UUID prj) {
        UUID rev = UUID.randomUUID();
        UUID wbs = UUID.randomUUID();
        jdbc.update("INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, sort_order) "
                + "VALUES (?, ?, '10-FIELD', '현장조사', 100, 10)", wbs, prj);
        jdbc.update("INSERT INTO cst.budget_revision(id, project_id, rev_no, status, bac_krw, contract_amount_krw, created_by) "
                + "VALUES (?, ?, 0, 'DRAFT', 1000000, 1500000, '00000000-0000-0000-0000-000000000000')", rev, prj);
        jdbc.update("INSERT INTO cst.budget_line(id, revision_id, wbs_id, cost_element, qty, unit_cost_krw) "
                + "VALUES (?, ?, ?, 'LABOR'::cst.cost_element, 10, 100000)", UUID.randomUUID(), rev, wbs);
        return rev;
    }

    private MockHttpServletRequestBuilder budgetAction(UUID prj, UUID rev, String action, long version) {
        return postJson("/api/v1/cst/projects/" + prj + "/budget-revisions/" + rev + ":" + action, "{}")
                .header("If-Match", "\"v" + version + "\"");
    }

    @Test
    @DisplayName("CST-01: DRAFT 직접 결재 409(CST-409-005) → PM 상신 → 상신자가 EXEC 를 겸해도 결재 403(CST-403-002) → 다른 EXEC 결재")
    void cst01_budgetMakerChecker() throws Exception {
        UUID prj = project();
        UUID pm = actor();
        member(prj, pm, "RESP");
        UUID rev = draftBudget(prj);
        UUID exec = actor();

        mvc.perform(as(budgetAction(prj, rev, "approve", 0), exec, "EXEC"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-005"));
        mvc.perform(as(budgetAction(prj, rev, "submit", 0), actor(), "PM"))   // 비투입 PM
                .andExpect(status().isForbidden());
        mvc.perform(as(budgetAction(prj, rev, "submit", 0), exec, "EXEC"))    // EXEC 는 cst:budget:update 없음
                .andExpect(status().isForbidden());
        JsonNode submitted = json(mvc.perform(as(budgetAction(prj, rev, "submit", 0), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.submittedBy").value(pm.toString())).andReturn());
        long v = submitted.get("version").asLong();
        mvc.perform(as(budgetAction(prj, rev, "submit", v), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-005"));
        mvc.perform(as(budgetAction(prj, rev, "approve", v), pm, "PM,EXEC"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CST-403-002"));
        mvc.perform(as(budgetAction(prj, rev, "approve", v), exec, "EXEC"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(exec.toString()));
    }

    // ------------------------------------------------------------------ CST-02

    @Test
    @DisplayName("CST-02: 요율 validFrom 이 마감 회계기간에 걸치면 409(CST-409-008) — 시작 월 마감 · 기존 승인 단가를 마감 기간에서 바꾸는 소급, 열린 기간부터는 허용")
    void cst02_noBackdatingIntoClosedPeriod() throws Exception {
        String closedYm = "199906";
        boolean existed = !jdbc.queryForList("SELECT status FROM cst.accounting_period WHERE period_ym = ?", String.class,
                closedYm).isEmpty();
        jdbc.update("INSERT INTO cst.accounting_period(period_ym, status) VALUES (?, 'CLOSED') "
                + "ON CONFLICT (period_ym) DO UPDATE SET status = 'CLOSED'", closedYm);
        try {
            String key = "SYN-BKD" + SEQ.incrementAndGet();
            String body = "{\"rateKind\":\"STD_LABOR\",\"keyCode\":\"" + key
                    + "\",\"value\":%d,\"validFrom\":\"%s\",\"sourceRef\":\"(합성) 소급 시험\"}";
            UUID exec = actor();
            // 시작 월이 마감
            mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(200000, "1999-06-10")), admin, "ADMIN"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-008"));
            // 처음 등록하는 키는 열린 시작 월이면 허용(그 키로 전기된 원장 없음)
            JsonNode d1 = json(mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(200000, "1999-01-01")), admin,
                    "ADMIN")).andExpect(status().isCreated()).andReturn());
            mvc.perform(as(postJson("/api/v1/cst/rates/" + d1.get("id").asText() + ":approve", "{}")
                            .header("If-Match", "\"v" + d1.get("version").asLong() + "\""), exec, "EXEC"))
                    .andExpect(status().isOk());
            // 기존 승인 단가를 마감 기간(199906)에서 바꾸는 소급 → 409
            mvc.perform(as(postJson("/api/v1/cst/rates", body.formatted(210000, "1999-03-01")), admin, "ADMIN"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-008"));
            // 마감 기간 뒤 열린 기간(한정 구간)은 가능 — 무기한이면 그 뒤 다른 마감 월까지 바꾸므로 공유 DB 에서 구간을 둔다
            String bounded = "{\"rateKind\":\"STD_LABOR\",\"keyCode\":\"" + key + "\",\"value\":210000,"
                    + "\"validFrom\":\"1999-07-01\",\"validTo\":\"1999-08-01\",\"sourceRef\":\"(합성) 소급 시험\"}";
            JsonNode d2 = json(mvc.perform(as(postJson("/api/v1/cst/rates", bounded), admin, "ADMIN"))
                    .andExpect(status().isCreated()).andReturn());
            // 초안을 마감 기간으로 당기는 수정도 409
            mvc.perform(as(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                            .patch("/api/v1/cst/rates/" + d2.get("id").asText()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"validFrom\":\"1999-05-01\"}")
                            .header("If-Match", "\"v" + d2.get("version").asLong() + "\""), admin, "ADMIN"))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-008"));
        } finally {
            if (existed) {
                jdbc.update("UPDATE cst.accounting_period SET status = 'OPEN' WHERE period_ym = ?", closedYm);
            } else {
                jdbc.update("DELETE FROM cst.accounting_period WHERE period_ym = ?", closedYm);
            }
        }
    }

    // ------------------------------------------------------------------ SYSADMIN 회계기간 조회

    @Test
    @DisplayName("CST-SYSADMIN-READ: 재오픈하는 SYSADMIN 도 회계기간 목록(cst:period:read) 200")
    void sysadminReadsPeriods() throws Exception {
        mvc.perform(as(get("/api/v1/cst/periods"), actor(), "SYSADMIN")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/cst/periods/202601"), actor(), "SYSADMIN")).andExpect(status().isOk());
    }
}
