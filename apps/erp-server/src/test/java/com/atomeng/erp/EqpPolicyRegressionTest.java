package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.eqp.adapter.out.hrq.StubPilotQualificationAdapter;
import com.atomeng.erp.support.IntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 보안 검토 2026-10-19 EQP 정책 결정(권장안) 회귀 시험 — EQP-01 · 02 · 03 · 04, 조종 자격 스텁 운영 가드.
 * 직원 · 프로젝트 · 장비 모두 합성값.
 */
class EqpPolicyRegressionTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));

    UUID admin;
    UUID pm;
    UUID eng;
    UUID qa;
    UUID outsiderPm;
    UUID outsiderEng;
    UUID project;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        admin = UUID.randomUUID();
        pm = employee("EP-PM");
        eng = employee("EP-ENG");
        qa = employee("EP-QA");
        outsiderPm = employee("EP-XPM");
        outsiderEng = employee("EP-XENG");
        UUID client = createClient(actor(), "합성발주처-eqp-policy-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 EQP 정책 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        project = UUID.fromString(json(r).get("id").asText());
        member(project, pm, "RESP");
        member(project, eng, "PART");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private UUID employee(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성 직원', DATE '2020-01-01', ?, ?)", id, prefix + "-" + id.toString().substring(0, 8), id, id);
        return id;
    }

    private void member(UUID prj, UUID employee, String role) {
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, ?, ?, ?)", prj, employee, role, today().minusDays(30), today().plusDays(400));
    }

    private UUID equipment(String type, int daysLeft) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/eqp/equipment", "{\"typeCode\":\"%s\",\"serialNo\":\"SYN-EP-%d\"}"
                .formatted(type, SEQ.incrementAndGet())), admin, "ADMIN")).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json(r).get("id").asText());
        if (daysLeft != Integer.MIN_VALUE) {
            mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + "/calibrations",
                    ("{\"calibratedOn\":\"%s\",\"nextDueOn\":\"%s\",\"result\":\"PASS\",\"certNo\":\"KC-EP-%d\","
                            + "\"labName\":\"합성교정기관\",\"labAccreditationNo\":\"KC-000\"}").formatted(today().minusDays(300),
                            today().plusDays(daysLeft), SEQ.incrementAndGet())), admin, "ADMIN"))
                    .andExpect(status().isCreated());
        }
        return id;
    }

    private String label(UUID id) throws Exception {
        return json(mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + "/labels", "{\"rotate\":false}"), admin,
                "ADMIN")).andExpect(status().isOk()).andReturn()).get("token").asText();
    }

    private ResultActions reserve(UUID equipment, LocalDate from, UUID who, String role) throws Exception {
        return mvc.perform(as(postJson("/api/v1/eqp/reservations",
                "{\"equipmentId\":\"%s\",\"projectId\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"}".formatted(equipment, project,
                        from, from.plusDays(1))), who, role));
    }

    private ResultActions checkout(String token, String outAt, UUID who, String role) throws Exception {
        return mvc.perform(as(postJson("/api/v1/eqp/checkouts", ("{\"clientOpId\":\"%s\",\"qrToken\":\"%s\",\"projectId\":"
                + "\"%s\",%s\"preCheck\":{\"exterior\":\"OK\"}}").formatted(UUID.randomUUID(), token, project,
                outAt == null ? "" : "\"outAt\":\"" + outAt + "\",")), who, role));
    }

    private ResultActions override(UUID equipment, String projectJson, UUID requestedBy, LocalDate from, UUID who,
                                   String role) throws Exception {
        return mvc.perform(as(postJson("/api/v1/eqp/equipment/" + equipment + "/calibration-overrides",
                ("{%s\"requestedBy\":%s,\"reason\":\"합성: 교정기관 일정 지연\",\"validFrom\":\"%s\",\"validTo\":\"%s\"}")
                        .formatted(projectJson, requestedBy == null ? "null" : "\"" + requestedBy + "\"", from,
                                from.plusDays(3))), who, role));
    }

    private String reservationId(ResultActions r) throws Exception {
        return json(r.andExpect(status().isCreated()).andReturn()).get("id").asText();
    }

    // ------------------------------------------------------------------ EQP-01

    @Test
    @DisplayName("EQP-01: 예약 — 비투입 ENG 신청 403, 본인 승인 403 EQP-403-003, 비투입 PM 승인 403 CORE-403-002, "
            + "투입 PM 승인 200 · ADMIN 승인 200, 취소는 신청자 · 승인 권한자만(그 밖 403 EQP-403-002)")
    void eqp01ReservationScopeAndSoD() throws Exception {
        UUID eq = equipment("MSR-CRK", 300);
        reserve(eq, today().plusDays(3), outsiderEng, "ENG").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"));
        // PM 본인 신청 → 본인 승인 금지
        String own = reservationId(reserve(eq, today().plusDays(3), pm, "PM"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + own + ":approve", "{}"), pm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EQP-403-003"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + own + ":approve", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        // ENG 신청 → 비투입 PM 승인 403, 투입 PM 승인 200
        String r2 = reservationId(reserve(eq, today().plusDays(10), eng, "ENG"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + r2 + ":approve", "{}"), outsiderPm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + r2 + ":approve", "{}"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        assertThat(jdbc.queryForObject("SELECT approved_by FROM eqp.reservation WHERE id = ?::uuid", UUID.class, r2))
                .isEqualTo(pm);
        // 취소: 다른 투입 ENG · 비투입 PM 403, 신청자 200 / 투입 PM(승인 권한) 200
        UUID otherEng = employee("EP-ENG2");
        member(project, otherEng, "PART");
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + r2 + ":cancel", "{}"), otherEng, "ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EQP-403-002"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + r2 + ":cancel", "{}"), outsiderPm, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EQP-403-002"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + r2 + ":cancel", "{}"), eng, "ENG"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        String r3 = reservationId(reserve(eq, today().plusDays(20), eng, "ENG"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + r3 + ":cancel", "{}"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("EQP-01: 반출 · 반납은 PROJECT 범위 — 비투입 ENG 반출 403 CORE-403-002 · 반납 403, 투입 ENG 200")
    void eqp01CheckoutProjectScope() throws Exception {
        UUID eq = equipment("SRV-LVL", 300);
        String token = label(eq);
        checkout(token, null, outsiderEng, "ENG").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"));
        String co = json(checkout(token, null, eng, "ENG").andExpect(status().isOk()).andReturn()).get("checkoutId").asText();
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + co + "/return", "{}"), outsiderEng, "ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + co + "/return", "{}"), eng, "ENG")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ EQP-02

    @Test
    @DisplayName("EQP-02: 반출 시각 — 미래 422 EQP-422-006, 72시간보다 과거 422, 시계 오차(2분) 안 미래는 서버 시각으로 기록 · "
            + "48시간 전 오프라인 반출은 허용 → 사용 일수는 받아들인 시각 기준(3일), 반납 inAt 미래 422")
    void eqp02ServerTimeAndOfflineWindow() throws Exception {
        UUID eq = equipment("NDT-UPV", 300);
        String token = label(eq);
        Instant now = clock.instant();
        checkout(token, now.plus(Duration.ofHours(1)).toString(), eng, "ENG").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQP-422-006"));
        checkout(token, now.minus(Duration.ofHours(73)).toString(), eng, "ENG").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQP-422-006"));
        String skew = json(checkout(token, now.plusSeconds(30).toString(), eng, "ENG").andExpect(status().isOk())
                .andReturn()).get("checkoutId").asText();
        Instant recorded = jdbc.queryForObject("SELECT out_at FROM eqp.checkout WHERE id = ?::uuid", Timestamp.class,
                skew).toInstant();
        assertThat(recorded).isAfterOrEqualTo(now.truncatedTo(java.time.temporal.ChronoUnit.MILLIS))
                .isBefore(now.plusSeconds(30)).isBeforeOrEqualTo(clock.instant());   // 기기 시각(미래)이 아니라 서버 시각
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + skew + "/return",
                "{\"inAt\":\"" + now.plus(Duration.ofHours(2)) + "\"}"), eng, "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EQP-422-006"));
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + skew + "/return", "{}"), eng, "ENG")).andExpect(status().isOk());

        UUID eq2 = equipment("NDT-UPV", 300);
        String offline = json(checkout(label(eq2), now.minus(Duration.ofHours(48)).toString(), eng, "ENG")
                .andExpect(status().isOk()).andReturn()).get("checkoutId").asText();
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + offline + "/return", "{}"), eng, "ENG"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.usageDays").value(3));
    }

    // ------------------------------------------------------------------ EQP-03

    @Test
    @DisplayName("EQP-03: 비행기록 조종자 = 요청자 — ENG 가 남을 조종자로 등록 403 EQP-403-004, 생략하면 본인, ADMIN 대리 등록 201 / "
            + "운영 프로필에서 조종 자격 스텁 생성 → 기동 실패")
    void eqp03PilotIsRequesterAndStubForbiddenInProd() throws Exception {
        UUID drone = equipment("UAV", Integer.MIN_VALUE);
        mvc.perform(put("/api/v1/eqp/drones/" + drone + "/profile").header("X-Actor-Id", admin.toString())
                .header("X-Roles", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"registrationNo\":\"SYN-UAV-EP-%d\",\"mtowKg\":6.5,\"insuranceExpiresOn\":\"%s\"}"
                        .formatted(SEQ.incrementAndGet(), today().plusDays(100)))).andExpect(status().isOk());
        String start = today().atTime(10, 0).atZone(SEOUL).toInstant().toString();
        String end = today().atTime(10, 20).atZone(SEOUL).toInstant().toString();
        String body = "{\"projectId\":\"%s\",%s\"startedAt\":\"%s\",\"endedAt\":\"%s\",\"track\":[[127.0,37.5,30]]}";
        UUID someoneElse = UUID.randomUUID();
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        body.formatted(project, "\"pilotId\":\"" + someoneElse + "\",", start, end)), eng, "ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EQP-403-004"));
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs", body.formatted(project, "", start, end)),
                eng, "ENG")).andExpect(status().isCreated()).andExpect(jsonPath("$.pilotId").value(eng.toString()));
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        body.formatted(project, "\"pilotId\":\"" + someoneElse + "\",", start, end)), admin, "ADMIN"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.pilotId").value(someoneElse.toString()));

        StandardEnvironment prod = new StandardEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> new StubPilotQualificationAdapter(false, prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EQP-03");
        StandardEnvironment production = new StandardEnvironment();
        production.setActiveProfiles("production");
        assertThatThrownBy(() -> new StubPilotQualificationAdapter(true, production))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new StubPilotQualificationAdapter(false, new StandardEnvironment())
                .check(UUID.randomUUID(), null, today()).status().name()).isEqualTo("VALID");
    }

    // ------------------------------------------------------------------ EQP-04

    @Test
    @DisplayName("EQP-04: 검교정 예외 — projectId 없음 422, 요청자 = 승인자 403 EQP-403-005, 소급 시작 422 EQP-422-007, "
            + "비투입 요청자 422, 정상 201(requested_by 기록), 같은 장비 · 프로젝트 두 번째 409 EQP-409-012")
    void eqp04OverrideRules() throws Exception {
        UUID eq = equipment("CHM-CL", 1);
        String prj = "\"projectId\":\"" + project + "\",";
        override(eq, "", eng, today(), qa, "QA").andExpect(status().isUnprocessableEntity());
        override(eq, prj, null, today(), qa, "QA").andExpect(status().isUnprocessableEntity());
        override(eq, prj, qa, today(), qa, "QA").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EQP-403-005"));
        override(eq, prj, eng, today().minusDays(1), qa, "QA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQP-422-007"));
        override(eq, prj, outsiderEng, today(), qa, "QA").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQP-422-001"));
        String id = json(override(eq, prj, eng, today(), qa, "QA").andExpect(status().isCreated())
                .andExpect(jsonPath("$.requested_by").value(eng.toString()))
                .andExpect(jsonPath("$.approved_by").value(qa.toString())).andReturn()).get("id").asText();
        override(eq, prj, pm, today().plusDays(2), qa, "QA").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-012")).andExpect(jsonPath("$.overrideId").value(id));
        // DB 도 4-eyes 를 강제
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO eqp.calibration_override(id, equipment_id, project_id, reason, valid_from, valid_to,
                                                     approved_by, requested_by) VALUES (?, ?, ?, '합성 사유 5자', ?, ?, ?, ?)""",
                UUID.randomUUID(), eq, project, java.sql.Date.valueOf(today().plusDays(30)),
                java.sql.Date.valueOf(today().plusDays(31)), qa, qa)).hasMessageContaining("ck_override_4eyes");
    }
}
