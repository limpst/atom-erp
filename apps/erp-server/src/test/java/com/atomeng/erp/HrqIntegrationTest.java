package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.hrq.application.CredentialService;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 03 HRQ 통합 테스트 — 자격 만료(TC-HRQ-006), 등록요건 예측(TC-HRQ-005), 타임시트 정정·마감(TC-HRQ-007·011–013) */
class HrqIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));

    @Autowired
    CredentialService credentials;

    UUID admin;
    UUID pm;

    @BeforeEach
    void setUp() {
        admin = actor();
        pm = actor();
        clock.reset();
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    /** core.employee + hrq.employee 등록(합성) */
    private UUID employee(String grade, boolean registered) throws Exception {
        UUID id = UUID.randomUUID();
        String empNo = "H-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성기술자', DATE '2020-01-01', ?, ?)", id, empNo, id, id);
        mvc.perform(as(postJson("/api/v1/hrq/employees", """
                        {"id":"%s","empNo":"%s","name":"합성기술자","field":"ARCH","employmentType":"FULLTIME",
                         "registeredField":%s,"hiredOn":"2020-01-01","initialGrade":"%s","gradeFrom":"2020-01-01"}"""
                        .formatted(id, empNo, registered, grade)), admin, "ADMIN"))
                .andExpect(status().isCreated());
        return id;
    }

    private UUID project() throws Exception {
        UUID client = createClient(actor(), "합성발주처-hrq-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 HRQ 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private int events(String type, UUID aggregate) {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, type, aggregate);
    }

    private JsonNode lastEvent(String type, UUID aggregate) throws Exception {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event "
                + "WHERE event_type = ? AND aggregate_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1",
                String.class, type, aggregate));
    }

    private MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** 주간 저장(본인) → 엔트리 id 목록 */
    private JsonNode saveWeek(UUID emp, String week, String entries, boolean submit) throws Exception {
        var r = mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + emp + "/weeks/" + week,
                "{\"entries\":[" + entries + "],\"submit\":" + submit + "}"), emp, "ENG"))
                .andExpect(status().isOk()).andReturn();
        return json(r);
    }

    private static String entry(UUID project, String date, int hours) {
        return "{\"projectId\":\"%s\",\"workDate\":\"%s\",\"workType\":\"FIELD\",\"hours\":%d}".formatted(project, date, hours);
    }

    private void approve(String... ids) throws Exception {
        mvc.perform(as(postJson("/api/v1/hrq/timesheets/approvals",
                        "{\"entryIds\":[\"" + String.join("\",\"", ids) + "\"]}"), pm, "PM"))
                .andExpect(status().isOk());
    }

    private MockHttpServletRequestBuilder correct(String entryId, String body, String key) {
        var b = postJson("/api/v1/hrq/timesheets/" + entryId + ":correct", body);
        return key == null ? b : b.header("Idempotency-Key", key);
    }

    // ------------------------------------------------------------------ 자격·등급·퇴사

    @Test
    @DisplayName("TC-HRQ-006: 만료 스캔 2회 → 알림 1회(멱등), 만료일 경과 → EXPIRED + hrq.credential.expired 1건")
    void tcHrq006_expiryScanIdempotent() throws Exception {
        UUID emp = employee("SENIOR", false);
        var r = mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + "/credentials", """
                        {"typeCode":"SAFETY_DIAG_CERT","certNo":"SYN-001","acquiredOn":"2023-01-01","expiresOn":"%s"}"""
                        .formatted(today().plusDays(20))), emp, "ENG"))
                .andExpect(status().isCreated()).andReturn();
        String credId = json(r).get("id").asText();
        mvc.perform(as(postJson("/api/v1/hrq/credentials/" + credId + ":approve", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EXPIRING"));

        credentials.scan();
        credentials.scan();
        assertThat(jdbc.queryForList("SELECT threshold_days FROM hrq.notification_log WHERE record_id = ?::uuid",
                Integer.class, credId)).containsExactly(30);

        clock.advance(Duration.ofDays(21)); // 만료일 경과
        credentials.scan();
        credentials.scan();
        assertThat(jdbc.queryForObject("SELECT status FROM hrq.credential WHERE id = ?::uuid", String.class, credId))
                .isEqualTo("EXPIRED");
        assertThat(events("hrq.credential.expired", emp)).isEqualTo(1);
        assertThat(lastEvent("hrq.credential.expired", emp).get("credentialType").asText()).isEqualTo("SAFETY_DIAG_CERT");
        // 다른 사람의 자격 등록은 ENG 불가(본인만), 승인은 ADMIN 만
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + "/credentials",
                        "{\"typeCode\":\"ARCH_ENG\",\"acquiredOn\":\"2020-01-01\"}"), actor(), "ENG"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/hrq/credentials/" + credId + ":approve", "{}"), pm, "PM"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("FR-HRQ-003: 등급 변경 → 이전 이력 종료 + hrq.grade.changed, 임의 시점 등급 조회")
    void gradeChange() throws Exception {
        UUID emp = employee("INTER", false);
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + "/grades",
                        "{\"grade\":\"SENIOR\",\"validFrom\":\"2026-11-01\"}"), admin, "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].validTo").value("2026-11-01"));
        mvc.perform(as(get("/api/v1/hrq/employees/" + emp).param("gradeAt", "2026-10-31"), admin, "ADMIN"))
                .andExpect(jsonPath("$.gradeAt").value("INTER"));
        mvc.perform(as(get("/api/v1/hrq/employees/" + emp).param("gradeAt", "2026-11-01"), admin, "ADMIN"))
                .andExpect(jsonPath("$.gradeAt").value("SENIOR"));
        var ev = lastEvent("hrq.grade.changed", emp);
        assertThat(ev.get("newGrade").asText()).isEqualTo("SENIOR");
        assertThat(ev.get("previousGrade").asText()).isEqualTo("INTER");
        assertThat(ev.get("effectiveDate").asText()).isEqualTo("2026-11-01");
        // 과거로 겹치는 변경 거부
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + "/grades",
                        "{\"grade\":\"MASTER\",\"validFrom\":\"2026-10-01\"}"), admin, "ADMIN"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("HRQ-422-001"));
    }

    @Test
    @DisplayName("설계 B6 v0.4: 퇴사 확정 → hrq.employee.terminated 1회(멱등키 employeeId+resignedOn)")
    void terminationOnce() throws Exception {
        UUID emp = employee("JUNIOR", false);
        String body = "{\"resignedOn\":\"2026-12-31\",\"source\":\"MANUAL\"}";
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + ":terminate", body), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.eventPublished").value(true))
                .andExpect(jsonPath("$.employee.status").value("RESIGNED"));
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + ":terminate", body), admin, "ADMIN"))
                .andExpect(jsonPath("$.eventPublished").value(false));
        assertThat(events("hrq.employee.terminated", emp)).isEqualTo(1);
        var ev = lastEvent("hrq.employee.terminated", emp);
        assertThat(ev.get("resignedOn").asText()).isEqualTo("2026-12-31");
        assertThat(ev.get("activeAllocationIds").isArray()).isTrue();
    }

    @Test
    @DisplayName("TC-HRQ-005(실데이터 예측): 등록요건 충족 → 중급 1명 퇴사 예정 → 최초 미달일 = 퇴사일 다음날, ARCH:INTER 1 부족")
    void tcHrq005_forecastFirstShortfall() throws Exception {
        UUID inter = null;
        for (String g : new String[] {"MASTER", "MASTER", "SENIOR", "SENIOR", "INTER", "INTER", "JUNIOR", "JUNIOR"}) {
            UUID e = employee(g, true);
            if ("INTER".equals(g)) {
                inter = e;
            }
        }
        // 미승인(PENDING) 자격은 미보유(BR-HRQ-005) — 이 규칙셋은 자격 요구가 없어 영향 없음
        mvc.perform(as(get("/api/v1/hrq/compliance"), admin, "EXEC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allSatisfied").value(true))
                .andExpect(jsonPath("$.fields[0].field").value("ARCH"));
        LocalDate resign = today().plusDays(40);
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + inter + ":plan-resign",
                "{\"resignPlannedOn\":\"" + resign + "\"}"), admin, "ADMIN")).andExpect(status().isOk());

        mvc.perform(as(get("/api/v1/hrq/compliance").param("date", resign.toString()), admin, "QA"))
                .andExpect(jsonPath("$.allSatisfied").value(true));          // 퇴사일 당일은 재직
        mvc.perform(as(get("/api/v1/hrq/compliance:forecast").param("from", today().toString())
                        .param("to", today().plusDays(90).toString()), admin, "EXEC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstShortfall").value(resign.plusDays(1).toString()))
                .andExpect(jsonPath("$.shortage['ARCH:INTER']").value(1));
        mvc.perform(as(get("/api/v1/hrq/compliance"), actor(), "ENG")).andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 타임시트

    @Test
    @DisplayName("SEC-HRQ-001·002: SELF 범위 ENG 는 남의 타임시트 리비전 403 · 직원 목록은 본인 행만, PM(ALL)은 전체")
    void selfScopeEngineerCannotReadOthers() throws Exception {
        UUID emp = employee("SENIOR", false);
        UUID colleague = employee("JUNIOR", false);
        UUID prj = project();
        var saved = saveWeek(emp, "2026-W12", entry(prj, "2026-03-17", 8), true);
        String tsKey = jdbc.queryForObject("SELECT timesheet_key::text FROM hrq.timesheet_entry WHERE id = ?::uuid",
                String.class, saved.get(0).get("id").asText());

        mvc.perform(as(get("/api/v1/hrq/timesheets/keys/" + tsKey + "/revisions"), colleague, "ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(get("/api/v1/hrq/timesheets/keys/" + tsKey + "/revisions"), emp, "ENG"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(get("/api/v1/hrq/timesheets/keys/" + tsKey + "/revisions"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(as(get("/api/v1/hrq/employees"), colleague, "ENG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(colleague.toString()));
        mvc.perform(as(get("/api/v1/hrq/employees"), actor(), "LABELER"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        var all = json(mvc.perform(as(get("/api/v1/hrq/employees"), pm, "PM")).andExpect(status().isOk()).andReturn());
        assertThat(all.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("TC-HRQ-011: 미마감 월 APPROVED 8h→6h 정정 → 리비전 2, 리비전 1 SUPERSEDED, hrq.timesheet.corrected(delta -2) 1건")
    void tcHrq011_correctOpenMonth() throws Exception {
        UUID emp = employee("SENIOR", false);
        UUID prj = project();
        var saved = saveWeek(emp, "2026-W11", entry(prj, "2026-03-10", 8), true);
        String entryId = saved.get(0).get("id").asText();
        assertThat(saved.get(0).get("status").asText()).isEqualTo("SUBMITTED");
        approve(entryId);
        assertThat(events("hrq.timesheet.confirmed", emp)).isEqualTo(1);
        var confirmed = lastEvent("hrq.timesheet.confirmed", emp);
        assertThat(confirmed.get("lines").get(0).get("hours").decimalValue()).isEqualByComparingTo("8");
        assertThat(confirmed.get("postingPeriod").asText()).isEqualTo("202603");

        String key = UUID.randomUUID().toString();
        mvc.perform(as(correct(entryId, "{\"hours\":6,\"reason\":\"현장 조기 종료(합성)\"}", key), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisionNo").value(2))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.supersedesId").value(entryId))
                .andExpect(jsonPath("$.postingPeriod").value("202603"));
        String tsKey = jdbc.queryForObject("SELECT timesheet_key::text FROM hrq.timesheet_entry WHERE id = ?::uuid",
                String.class, entryId);
        mvc.perform(as(get("/api/v1/hrq/timesheets/keys/" + tsKey + "/revisions"), pm, "PM"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].status").value("SUPERSEDED"))
                .andExpect(jsonPath("$[0].hours").value(8.0))     // 원본 값 보존
                .andExpect(jsonPath("$[1].hours").value(6.0));
        UUID keyUuid = UUID.fromString(tsKey);
        assertThat(events("hrq.timesheet.corrected", keyUuid)).isEqualTo(1);
        var ev = lastEvent("hrq.timesheet.corrected", keyUuid);
        assertThat(ev.get("deltaHours").decimalValue()).isEqualByComparingTo("-2");
        assertThat(ev.get("previousHours").decimalValue()).isEqualByComparingTo("8");
        assertThat(ev.get("newRevision").asInt()).isEqualTo(2);
        assertThat(ev.get("idempotencyKey").asText()).isEqualTo(tsKey + ":2");
        assertThat(ev.get("adjustment").asBoolean()).isFalse();

        // TC-HRQ-013: 같은 Idempotency-Key 재요청 → 리비전·이벤트 각 1건 (재반환)
        mvc.perform(as(correct(entryId, "{\"hours\":6,\"reason\":\"현장 조기 종료(합성)\"}", key), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM hrq.timesheet_entry WHERE timesheet_key = ?::uuid",
                Integer.class, tsKey)).isEqualTo(2);
        assertThat(events("hrq.timesheet.corrected", keyUuid)).isEqualTo(1);
        // 키 없이 정정 → 428, SUPERSEDED 리비전 재정정 → 409, 다른 기술자(ENG)의 정정 → 403
        mvc.perform(as(correct(entryId, "{\"hours\":5,\"reason\":\"x\"}", null), pm, "PM"))
                .andExpect(status().is(428)).andExpect(jsonPath("$.code").value("CORE-428-001"));
        mvc.perform(as(correct(entryId, "{\"hours\":5,\"reason\":\"x\"}", UUID.randomUUID().toString()), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HRQ-409-002"));
        String rev2 = jdbc.queryForObject("SELECT id::text FROM hrq.timesheet_entry WHERE timesheet_key = ?::uuid "
                + "AND revision_no = 2", String.class, tsKey);
        mvc.perform(as(correct(rev2, "{\"hours\":5,\"reason\":\"x\"}", UUID.randomUUID().toString()), actor(), "ENG"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-HRQ-007·012 + 월 마감/재오픈: 마감 후 수정 409, 정정 409 / ADMIN adjustment → 다음 미마감 월 조정, 원 월 합계 불변")
    void tcHrq007_012_monthCloseAndAdjustment() throws Exception {
        UUID emp = employee("SENIOR", false);
        UUID prj = project();
        var saved = saveWeek(emp, "2026-W16", entry(prj, "2026-04-14", 8) + "," + entry(prj, "2026-04-15", 4), true);
        String e1 = saved.get(0).get("id").asText();
        String e2 = saved.get(1).get("id").asText();
        approve(e1, e2);
        // 미승인 존재 → 마감 거부
        saveWeek(emp, "2026-W16", entry(prj, "2026-04-16", 1), false);
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202604", "{}"), admin, "ADMIN"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HRQ-409-004"));
        saveWeek(emp, "2026-W16", "", false); // DRAFT 정리(확정 행은 유지)
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202604", "{}"), actor(), "ENG"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202604", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMandays").value(1.5))
                .andExpect(jsonPath("$.lockedEntries").value(2));
        UUID monthAgg = UUID.nameUUIDFromBytes("hrq-month:202604".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(events("hrq.manday.closed", monthAgg)).isEqualTo(1);
        assertThat(lastEvent("hrq.manday.closed", monthAgg).get("rows").get(0).get("grade").asText()).isEqualTo("SENIOR");
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202604", "{}"), admin, "ADMIN"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HRQ-409-003"));

        // TC-HRQ-007: 마감 월 타임시트 수정 → 409 (정정 전표 경로 안내)
        mvc.perform(as(putJson("/api/v1/hrq/timesheets/" + emp + "/weeks/2026-W16",
                        "{\"entries\":[" + entry(prj, "2026-04-14", 7) + "],\"submit\":true}"), emp, "ENG"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HRQ-409-001"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(":correct")));

        // TC-HRQ-012: 재오픈 없이 정정 → 409 / PM 의 adjustment → 403 / ADMIN adjustment → 202605 조정 리비전
        mvc.perform(as(correct(e1, "{\"hours\":6,\"reason\":\"합성 정정\"}", UUID.randomUUID().toString()), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("HRQ-409-001"));
        mvc.perform(as(correct(e1, "{\"hours\":6,\"reason\":\"합성 정정\",\"adjustment\":true}",
                        UUID.randomUUID().toString()), pm, "PM"))
                .andExpect(status().isForbidden());
        mvc.perform(as(correct(e1, "{\"hours\":6,\"reason\":\"합성 정정\",\"adjustment\":true}",
                        UUID.randomUUID().toString()), admin, "ADMIN"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postingPeriod").value("202605"))
                .andExpect(jsonPath("$.workDate").value("2026-04-14"));        // 원 work_date 보존
        String key1 = jdbc.queryForObject("SELECT timesheet_key::text FROM hrq.timesheet_entry WHERE id = ?::uuid",
                String.class, e1);
        var adj = lastEvent("hrq.timesheet.corrected", UUID.fromString(key1));
        assertThat(adj.get("adjustment").asBoolean()).isTrue();
        assertThat(adj.get("postingPeriod").asText()).isEqualTo("202605");
        assertThat(jdbc.queryForObject("SELECT total_mandays FROM hrq.month_close WHERE year_month = '202604'",
                java.math.BigDecimal.class)).isEqualByComparingTo("1.50");   // 원 월 대사 합계 불변

        // 재오픈(사유·감사) 후에는 원 월에 정정 반영 → 재마감 시 새 합계
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202604:reopen", "{\"reason\":\"현장 일지 누락 확인(합성)\"}"),
                        admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REOPENED"));
        mvc.perform(as(correct(e2, "{\"hours\":2,\"reason\":\"재오픈 정정\"}", UUID.randomUUID().toString()), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.postingPeriod").value("202604"));
        mvc.perform(as(postJson("/api/v1/hrq/month-close/202604", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalMandays").value(0.25));   // e2 정정분 2h (e1 은 5월로 조정)
        assertThat(events("hrq.manday.closed", monthAgg)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'HRQ' AND target_type = "
                + "'MonthReopen'", Integer.class)).isGreaterThanOrEqualTo(1);
    }
}
