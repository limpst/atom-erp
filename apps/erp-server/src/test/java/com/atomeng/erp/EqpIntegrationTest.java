package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.eqp.application.CalibrationScanService;
import com.atomeng.erp.eqp.application.CheckoutService;
import com.atomeng.erp.eqp.application.ReservationService;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.TestBeans.FakePilotQualification;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 04 EQP 통합 테스트 — 내장 PostgreSQL + 전체 컨텍스트(MockMvc). 장비 · 교정 · 프로젝트 · 조종자 모두 합성값.
 * TC-EQP-001·002·003·004·006·007·008·010·011 (+ 012 는 ModularityTests), 검교정 스캔 멱등, 반납 → CST 원가 E2E.
 */
class EqpIntegrationTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));

    @Autowired
    OutboxRelay relay;
    @Autowired
    ReservationService reservationService;
    @Autowired
    CheckoutService checkoutService;
    @Autowired
    CalibrationScanService scanService;
    @Autowired
    FakePilotQualification pilots;

    UUID admin;
    UUID pm;
    UUID eng;
    UUID qa;

    @BeforeEach
    void setUp() {
        admin = actor();
        pm = employee("EQP-PM");
        eng = employee("EQP-ENG");
        qa = employee("EQP-QA");
        clock.reset();
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ------------------------------------------------------------------ helpers

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    /** 합성 직원(core.employee) — 투입(project_member) FK 대상 */
    private UUID employee(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성 직원', DATE '2020-01-01', ?, ?)", id, prefix + "-" + id.toString().substring(0, 8), id, id);
        return id;
    }

    private void member(UUID project, UUID employee, String role) {
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, ?, ?, ?)", project, employee, role, today().minusDays(30), today().plusDays(400));
    }

    /** 합성 프로젝트 — EQP-01(예약 · 반출 PROJECT 범위)에 맞춰 pm(RESP) · eng(PART) 투입 */
    private UUID project() throws Exception {
        UUID client = createClient(actor(), "합성발주처-eqp-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 EQP 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json(r).get("id").asText());
        member(id, pm, "RESP");
        member(id, eng, "PART");
        return id;
    }

    private UUID register(String type, String extra) throws Exception {
        String serial = "SYN-" + SEQ.incrementAndGet();
        var r = mvc.perform(as(postJson("/api/v1/eqp/equipment", ("{\"typeCode\":\"%s\",\"serialNo\":\"%s\","
                        + "\"model\":\"합성 모델\",\"homeLocation\":\"합성 장비실\"%s}").formatted(type, serial,
                        extra == null ? "" : "," + extra)), admin, "ADMIN"))
                .andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private UUID register(String type) throws Exception {
        return register(type, null);
    }

    private ResultActions calibrate(UUID id, LocalDate on, LocalDate next, String result) throws Exception {
        return mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + "/calibrations",
                ("{\"calibratedOn\":\"%s\",%s\"result\":\"%s\",\"certNo\":\"KC-SYN-%d\",\"labName\":\"합성교정기관\","
                        + "\"labAccreditationNo\":\"KC-000\"}").formatted(on, next == null ? "" : "\"nextDueOn\":\"" + next + "\",",
                        result, SEQ.incrementAndGet())), admin, "ADMIN"));
    }

    /** 오늘을 포함하는 PASS 교정(유효 기간: today-300 ~ today+days) */
    private UUID validEquipment(String type, int daysLeft) throws Exception {
        UUID id = register(type);
        calibrate(id, today().minusDays(300), today().plusDays(daysLeft), "PASS").andExpect(status().isCreated());
        return id;
    }

    private String label(UUID id, boolean rotate) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + "/labels", "{\"rotate\":" + rotate + "}"), admin,
                "ADMIN")).andExpect(status().isOk()).andReturn();
        return json(r).get("token").asText();
    }

    private ResultActions checkout(String token, UUID project, UUID op, String anvil) throws Exception {
        String pre = anvil == null ? "{\"exterior\":\"OK\",\"batteryPct\":82}"
                : "{\"exterior\":\"OK\",\"batteryPct\":82,\"anvilValue\":" + anvil + "}";
        return mvc.perform(as(postJson("/api/v1/eqp/checkouts", ("{\"clientOpId\":\"%s\",\"qrToken\":\"%s\",\"projectId\":"
                + "\"%s\",\"location\":{\"lat\":37.5251,\"lng\":126.9255},\"preCheck\":%s}").formatted(op, token, project,
                pre)), eng, "ENG"));
    }

    private List<JsonNode> events(String type, UUID aggregate) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        for (String p : jdbc.queryForList("SELECT payload::text FROM core.outbox_event WHERE event_type = ? "
                + "AND aggregate_id = ? ORDER BY occurred_at, id", String.class, type, aggregate)) {
            out.add(objectMapper.readTree(p));
        }
        return out;
    }

    private String statusOf(UUID equipment) {
        return jdbc.queryForObject("SELECT status FROM eqp.equipment WHERE id = ?", String.class, equipment);
    }

    // ------------------------------------------------------------------ 대장 · ETag · 권한 · QR

    @Test
    @DisplayName("FR-EQP-001: 등록 201 + 코드 EQ-{유형}-nnnn + ETag, 같은 유형 시리얼 중복 409 EQP-409-007, ENG 등록 403, "
            + "PATCH If-Match 없음 428 · 불일치 412 · 일치 200, qr_secret 미노출, 감사 로그, 폐기 = DISPOSED(삭제 아님)")
    void registerEtagAndDispose() throws Exception {
        String serial = "SYN-DUP-" + SEQ.incrementAndGet();
        var r = mvc.perform(as(postJson("/api/v1/eqp/equipment", "{\"typeCode\":\"NDT-UPV\",\"serialNo\":\"" + serial
                        + "\",\"acquisitionCost\":6000000,\"usefulLifeMonths\":60,\"spec\":{\"probes\":2}}"), admin, "ADMIN"))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"v0\""))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.matchesPattern("^EQ-NDT-UPV-\\d{4}$")))
                .andExpect(jsonPath("$.status").value("REGISTERED"))
                .andExpect(jsonPath("$.spec.probes").value(2))
                .andReturn();
        assertThat(r.getResponse().getContentAsString()).doesNotContainIgnoringCase("secret");
        UUID id = UUID.fromString(json(r).get("id").asText());
        mvc.perform(as(postJson("/api/v1/eqp/equipment", "{\"typeCode\":\"NDT-UPV\",\"serialNo\":\"" + serial + "\"}"),
                admin, "ADMIN")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQP-409-007"));
        mvc.perform(as(postJson("/api/v1/eqp/equipment", "{\"typeCode\":\"NDT-UPV\",\"serialNo\":\"X-1\"}"), eng, "ENG"))
                .andExpect(status().isForbidden());

        mvc.perform(as(get("/api/v1/eqp/equipment/" + id), eng, "ENG")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v0\"")).andExpect(jsonPath("$.validityToday.valid").value(false))
                .andExpect(jsonPath("$.validityToday.reason").value("EQP_CAL_EXPIRED"));
        String body = "{\"homeLocation\":\"합성 2창고\",\"dailyRate\":50000}";
        mvc.perform(as(patch("/api/v1/eqp/equipment/" + id).contentType(MediaType.APPLICATION_JSON).content(body), admin,
                "ADMIN")).andExpect(status().isPreconditionRequired());
        mvc.perform(as(patch("/api/v1/eqp/equipment/" + id).contentType(MediaType.APPLICATION_JSON).content(body)
                .header("If-Match", "\"v7\""), admin, "ADMIN")).andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("CORE-412-001"));
        mvc.perform(as(patch("/api/v1/eqp/equipment/" + id).contentType(MediaType.APPLICATION_JSON).content(body)
                        .header("If-Match", "\"v0\""), admin, "ADMIN")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v1\"")).andExpect(jsonPath("$.homeLocation").value("합성 2창고"));

        // 목록 필터: 유형 + 상태
        mvc.perform(as(get("/api/v1/eqp/equipment?type=NDT-UPV&status=REGISTERED&size=500"), eng, "ENG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[?(@.id == '" + id + "')]").exists());

        // 폐기: REGISTERED → DISPOSED 는 전이표 위반 409, 교정 후 AVAILABLE → DISPOSED
        String dispose = "{\"reason\":\"합성 폐기 사유\",\"disposalAmount\":0}";
        mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + ":dispose", dispose).header("If-Match", "\"v1\""), admin,
                "ADMIN")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQP-409-006"));
        calibrate(id, today().minusDays(10), today().plusDays(300), "PASS").andExpect(status().isCreated());
        assertThat(statusOf(id)).isEqualTo("AVAILABLE");
        mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + ":dispose", dispose).header("If-Match", "\"v2\""), admin,
                "ADMIN")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPOSED"));
        mvc.perform(as(get("/api/v1/eqp/equipment/" + id + "/validity"), eng, "ENG"))
                .andExpect(jsonPath("$.valid").value(false)).andExpect(jsonPath("$.reason").value("EQP_NOT_USABLE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'EQP' AND target_id = ?",
                Integer.class, id)).isGreaterThanOrEqualTo(3);   // CREATE · UPDATE · DELETE(폐기)
        assertThat(jdbc.queryForList("SELECT to_status FROM eqp.equipment_status_history WHERE equipment_id = ? "
                + "ORDER BY id", String.class, id)).containsExactly("REGISTERED", "AVAILABLE", "DISPOSED");
        assertThat(events("eqp.equipment.registered", id)).hasSize(1);
    }

    @Test
    @DisplayName("FR-EQP-002 · B7.4: 라벨 발급 → 스캔 200(가능 액션 CHECKOUT), 서명 변조 403 EQP-403-001, 형식 오류 400, "
            + "라벨 재출력(키 회전) 뒤 옛 라벨 403 · 새 라벨 200")
    void qrScan() throws Exception {
        UUID id = validEquipment("SRV-TLT", 200);
        String token = label(id, false);
        mvc.perform(as(get("/api/v1/eqp/scan/" + token), eng, "ENG")).andExpect(status().isOk())
                .andExpect(jsonPath("$.equipmentId").value(id.toString()))
                .andExpect(jsonPath("$.validity.valid").value(true))
                .andExpect(jsonPath("$.actions[0]").value("CHECKOUT"));
        char last = token.charAt(token.length() - 1);
        String forged = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');
        mvc.perform(as(get("/api/v1/eqp/scan/" + forged), eng, "ENG")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("EQP-403-001"));
        mvc.perform(as(get("/api/v1/eqp/scan/not-a-token"), eng, "ENG")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EQP-400-001"));
        String rotated = label(id, true);
        mvc.perform(as(get("/api/v1/eqp/scan/" + token), eng, "ENG")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/eqp/scan/" + rotated), eng, "ENG")).andExpect(status().isOk());
        // 반출도 같은 검증: 옛 라벨로 반출 시도 403
        checkout(token, project(), UUID.randomUUID(), null).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("SEC-EQP-01: 시리얼에 ZPL 명령(^FS^XA · ~JA)이 있어도 라벨 ZPL 에 그대로 들어가지 않음(^XA 는 시작 1회뿐) · "
            + "SEC-EQP-02: page=2147483647 → 500 아님(200 빈 목록)")
    void labelZplInjectionAndPagingOverflow() throws Exception {
        String serial = "SYN-ZPL-" + SEQ.incrementAndGet() + "^FS^XA^FO0,0^FDPWN^FS~JA";
        var r = mvc.perform(as(postJson("/api/v1/eqp/equipment", "{\"typeCode\":\"NDT-UPV\",\"serialNo\":\"" + serial
                + "\"}"), admin, "ADMIN")).andExpect(status().isCreated()).andReturn();
        UUID id = UUID.fromString(json(r).get("id").asText());
        var lr = mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + "/labels", "{\"rotate\":false}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andReturn();
        String zpl = json(lr).get("zpl").asText();
        assertThat(zpl.split("\\^XA", -1)).hasSize(2);   // 라벨 시작 ^XA 하나만
        assertThat(zpl).doesNotContain("^FDPWN").doesNotContain("~JA").contains("SYN-ZPL-");
        mvc.perform(as(get("/api/v1/eqp/equipment?page=2147483647&size=500"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
    }

    // ------------------------------------------------------------------ 검교정 · 판정

    @Test
    @DisplayName("FR-EQP-003 · B7.1 경계값: 차기일 누락 → 규칙 12개월 추정(+estimated), on=교정일 · on=차기일 유효, 차기일+1 무효 / "
            + "FAIL 등록 → BROKEN · 판정 EQP_NOT_USABLE")
    void calibrationAndBoundaries() throws Exception {
        UUID id = register("NDT-RBR");
        LocalDate cal = today().minusDays(30);
        calibrate(id, cal, null, "PASS").andExpect(status().isCreated())
                .andExpect(jsonPath("$.valid_until").value(cal.plusMonths(12).minusDays(1).toString()))
                .andExpect(jsonPath("$.next_due_estimated").value(true));
        assertThat(statusOf(id)).isEqualTo("AVAILABLE");
        LocalDate due = cal.plusMonths(12).minusDays(1);
        for (var c : List.of(Map.entry(cal, true), Map.entry(due, true), Map.entry(due.plusDays(1), false),
                Map.entry(cal.minusDays(1), false))) {
            mvc.perform(as(get("/api/v1/eqp/equipment/" + id + "/validity?date=" + c.getKey()), eng, "ENG"))
                    .andExpect(jsonPath("$.valid").value(c.getValue()));
        }
        mvc.perform(as(get("/api/v1/eqp/equipment/" + id + "/validity?date=" + due), eng, "ENG"))
                .andExpect(jsonPath("$.basis").value("CALIBRATION")).andExpect(jsonPath("$.validUntil").value(due.toString()));
        calibrate(id, today(), today().plusDays(365), "FAIL").andExpect(status().isCreated());
        assertThat(statusOf(id)).isEqualTo("BROKEN");
        mvc.perform(as(get("/api/v1/eqp/equipment/" + id + "/validity"), eng, "ENG"))
                .andExpect(jsonPath("$.reason").value("EQP_NOT_USABLE"));
        assertThat(events("eqp.calibration.recorded", id)).hasSize(2);
        // ENG 는 교정 등록 불가
        mvc.perform(as(postJson("/api/v1/eqp/equipment/" + id + "/calibrations",
                "{\"calibratedOn\":\"2026-01-01\",\"result\":\"PASS\"}"), eng, "ENG")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-EQP-001: 차기교정일 = 어제인 장비 반출 → 409 EQP-409-001(EQP_CAL_EXPIRED) + 대체 장비 제안 · overrideAllowed / "
            + "TC-EQP-008: Override 8일 → 400 EQP-400-002, ENG 403 / QA Override(7일) 후 반출 200(basis OVERRIDE) · 감사 APPROVE")
    void tcEqp001_expiredBlockedAndOverride() throws Exception {
        UUID prj = project();
        UUID expiring = validEquipment("CHM-CL", 1);      // 차기일 = 오늘+1
        UUID alt = validEquipment("CHM-CL", 300);
        String token = label(expiring, false);
        clock.advance(Duration.ofDays(2));                  // → 차기일 = 어제
        assertThat(jdbc.queryForObject("SELECT max(next_due_on) FROM eqp.calibration WHERE equipment_id = ?",
                LocalDate.class, expiring)).isEqualTo(today().minusDays(1));
        var r = checkout(token, prj, UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-001"))
                .andExpect(jsonPath("$.reason").value("EQP_CAL_EXPIRED"))
                .andExpect(jsonPath("$.overrideAllowed").value(true)).andReturn();
        List<String> alts = new ArrayList<>();
        json(r).get("alternatives").forEach(a -> alts.add(a.get("equipmentId").asText()));
        assertThat(alts).contains(alt.toString()).doesNotContain(expiring.toString()).hasSizeLessThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.checkout WHERE equipment_id = ?", Integer.class, expiring))
                .isZero();

        String ovr = "{\"projectId\":\"%s\",\"requestedBy\":\"" + eng
                + "\",\"reason\":\"합성: 교정기관 일정 지연, 재교정 예약됨\",\"validFrom\":\"%s\",\"validTo\":\"%s\"}";
        mvc.perform(as(postJson("/api/v1/eqp/equipment/" + expiring + "/calibration-overrides",
                ovr.formatted(prj, today(), today().plusDays(7))), qa, "QA"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("EQP-400-002"));
        mvc.perform(as(postJson("/api/v1/eqp/equipment/" + expiring + "/calibration-overrides",
                ovr.formatted(prj, today(), today().plusDays(6))), eng, "ENG")).andExpect(status().isForbidden());
        var o = mvc.perform(as(postJson("/api/v1/eqp/equipment/" + expiring + "/calibration-overrides",
                ovr.formatted(prj, today(), today().plusDays(6))), qa, "QA")).andExpect(status().isCreated()).andReturn();
        UUID overrideId = UUID.fromString(json(o).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'EQP' AND action = 'APPROVE' "
                + "AND target_id = ?", Integer.class, overrideId)).isEqualTo(1);

        // 다른 프로젝트는 여전히 차단(프로젝트 한정 Override)
        checkout(token, project(), UUID.randomUUID(), null).andExpect(status().isConflict());
        checkout(token, prj, UUID.randomUUID(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.calibration.basis").value("OVERRIDE"))
                .andExpect(jsonPath("$.equipmentStatus").value("CHECKED_OUT"));
        assertThat(jdbc.queryForObject("SELECT override_id FROM eqp.checkout WHERE equipment_id = ?", UUID.class, expiring))
                .isEqualTo(overrideId);
    }

    @Test
    @DisplayName("TC-EQP-006: 키트 자식(교정 대상 부속) 교정 만료 → 부모 반출 409 EQP-409-004(EQP_KIT_CHILD_INVALID) · 예약도 거부")
    void tcEqp006_kitChildExpired() throws Exception {
        UUID parent = validEquipment("NDT-UPV", 200);
        UUID child = register("KIT-PART", "\"parentId\":\"" + parent + "\"");
        calibrate(child, today().minusDays(400), today().minusDays(5), "PASS").andExpect(status().isCreated());
        UUID prj = project();
        checkout(label(parent, false), prj, UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-004"))
                .andExpect(jsonPath("$.reason").value("EQP_KIT_CHILD_INVALID"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations", "{\"equipmentId\":\"%s\",\"projectId\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"}"
                .formatted(parent, prj, today().plusDays(1), today().plusDays(2))), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQP-409-004"));
        // 자식 재교정 → 부모 반출 가능
        calibrate(child, today(), today().plusDays(365), "PASS").andExpect(status().isCreated());
        checkout(label(parent, false), prj, UUID.randomUUID(), null).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-EQP-007 · BR-EQP-006: 슈미트해머 앤빌 77(기준 80±2) → 422 EQP-422-002 + 교정 의뢰 권고, 앤빌값 누락 422, 80.4 → 200")
    void tcEqp007_anvil() throws Exception {
        UUID id = validEquipment("NDT-RH", 200);
        String token = label(id, false);
        UUID prj = project();
        checkout(token, prj, UUID.randomUUID(), "77").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQP-422-002"))
                .andExpect(jsonPath("$.recommendation").value("CALIBRATION_REQUEST"));
        checkout(token, prj, UUID.randomUUID(), null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("EQP-422-001"));
        assertThat(statusOf(id)).isEqualTo("AVAILABLE");
        checkout(token, prj, UUID.randomUUID(), "80.4").andExpect(status().isOk());
        assertThat(statusOf(id)).isEqualTo("CHECKED_OUT");
    }

    // ------------------------------------------------------------------ 예약

    @Test
    @DisplayName("TC-EQP-002: 같은 장비 · 겹치는 기간 예약 2건 동시 요청 × 20회 → 매번 정확히 1건 성공 · 1건 EQP-409-002 (DB EXCLUDE) / "
            + "HTTP 409 + 대체 장비, 승인 · 취소 시 슬롯 해제, BR-EQP-002 기간 중 만료 409 EQP-409-009")
    void tcEqp002_doubleBookingUnderConcurrency() throws Exception {
        final int ROUNDS = 20;
        UUID id = validEquipment("MSR-CRK", 200);
        UUID alt = validEquipment("MSR-CRK", 250);
        UUID prjA = project();
        UUID prjB = project();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 20회: 수정 전(장비 잠금 없음)에는 GiST EXCLUDE 동시 삽입이 가끔 40P01 교착 → 테스트가 간헐 실패했다
            for (int round = 0; round < ROUNDS; round++) {
                LocalDate from = today().plusDays(10 + round * 4L);
                CountDownLatch go = new CountDownLatch(1);
                List<Callable<String>> calls = new ArrayList<>();
                for (UUID prj : List.of(prjA, prjB)) {
                    LocalDate f = prj.equals(prjA) ? from : from.plusDays(1);
                    calls.add(() -> {
                        go.await();
                        try {
                            reservationService.request(id, prj, f, f.plusDays(2), null);
                            return "OK";
                        } catch (DomainException e) {
                            return e.code();
                        }
                    });
                }
                List<Future<String>> fs = new ArrayList<>();
                for (var c : calls) {
                    fs.add(pool.submit(c));
                }
                go.countDown();
                List<String> results = new ArrayList<>();
                for (var f : fs) {
                    results.add(f.get(30, TimeUnit.SECONDS));
                }
                assertThat(results).as("round %d", round).containsExactlyInAnyOrder("OK", "EQP-409-002");
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.reservation WHERE equipment_id = ?", Integer.class, id))
                .isEqualTo(ROUNDS);

        // HTTP: 첫 예약 201 → 겹침 409 + 대체(alt) → 승인 → 취소 → 같은 기간 재예약 201
        LocalDate f = today().plusDays(100);
        String body = "{\"equipmentId\":\"%s\",\"projectId\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"}";
        var created = mvc.perform(as(postJson("/api/v1/eqp/reservations", body.formatted(id, prjA, f, f.plusDays(2))), eng,
                "ENG")).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("REQUESTED")).andReturn();
        String rid = json(created).get("id").asText();
        var conflict = mvc.perform(as(postJson("/api/v1/eqp/reservations", body.formatted(id, prjB, f.plusDays(2),
                        f.plusDays(4))), eng, "ENG")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-002")).andReturn();
        List<String> alts = new ArrayList<>();
        json(conflict).get("alternatives").forEach(a -> alts.add(a.get("equipmentId").asText()));
        assertThat(alts).contains(alt.toString()).doesNotContain(id.toString());
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + rid + ":approve", "{}"), eng, "ENG"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + rid + ":approve", "{}"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + rid + ":cancel", "{}"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        mvc.perform(as(postJson("/api/v1/eqp/reservations", body.formatted(id, prjB, f.plusDays(2), f.plusDays(4))), eng,
                "ENG")).andExpect(status().isCreated());
        // BR-EQP-002: 차기일(오늘+200) 을 넘는 예약
        mvc.perform(as(postJson("/api/v1/eqp/reservations", body.formatted(id, prjA, today().plusDays(198),
                today().plusDays(203))), pm, "PM")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-009"));
        // 가용 장비 조회
        mvc.perform(as(get("/api/v1/eqp/reservations/availability?type=MSR-CRK&from=" + f + "&to=" + f.plusDays(1)), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.equipmentId == '" + alt + "')]").exists());
    }

    // ------------------------------------------------------------------ 반출 · 반납

    @Test
    @DisplayName("TC-EQP-003: 오프라인 반출 2건(다른 기기) 동기화 → 먼저 도착한 건 200, 나머지 202 sync_conflict + eqp.checkout.conflicted / "
            + "동시 2건도 정확히 1건만 정상(장비 행 잠금 + ux_active_checkout)")
    void tcEqp003_offlineConflict() throws Exception {
        UUID prj = project();
        UUID id = validEquipment("SRV-LVL", 200);
        String token = label(id, false);
        checkout(token, prj, UUID.randomUUID(), null).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("OK"));
        UUID second = UUID.randomUUID();
        checkout(token, prj, second, null).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.result").value("CONFLICT_RECORDED"))
                .andExpect(jsonPath("$.syncConflict").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.checkout WHERE equipment_id = ? AND sync_conflict",
                Integer.class, id)).isEqualTo(1);
        assertThat(events("eqp.checkout.conflicted", id)).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'EQP' AND target_type = "
                + "'checkout-conflict' AND after_json->>'equipmentId' = ?", Integer.class, id.toString())).isEqualTo(1);

        // 동시 경합
        UUID id2 = validEquipment("SRV-LVL", 200);
        String token2 = label(id2, false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                fs.add(pool.submit(() -> {
                    go.await();
                    return checkoutService.checkout(new CheckoutService.Checkout(UUID.randomUUID(), token2, prj, null, null,
                            null, null, Map.of("exterior", "OK"))).status();
                }));
            }
            go.countDown();
            List<String> rs = new ArrayList<>();
            for (var f : fs) {
                rs.add(f.get(30, TimeUnit.SECONDS));
            }
            assertThat(rs).containsExactlyInAnyOrder("OK", "CONFLICT_RECORDED");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.checkout WHERE equipment_id = ? AND in_at IS NULL "
                + "AND NOT sync_conflict", Integer.class, id2)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-EQP-004: 같은 clientOpId 3회 재전송 → 매번 200 · 레코드 1건 · 2·3회차 REPLAYED / Idempotency-Key 재전송 → Idempotent-Replayed")
    void tcEqp004_clientOpIdIdempotent() throws Exception {
        UUID prj = project();
        UUID id = validEquipment("SRV-TLT", 200);
        String token = label(id, false);
        UUID op = UUID.randomUUID();
        checkout(token, prj, op, null).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("OK"));
        for (int i = 0; i < 2; i++) {
            checkout(token, prj, op, null).andExpect(status().isOk()).andExpect(jsonPath("$.result").value("REPLAYED"));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.checkout WHERE client_op_id = ?", Integer.class, op))
                .isEqualTo(1);
        assertThat(events("eqp.equipment.checked-out", id)).hasSize(1);

        // 공통 멱등 필터(Idempotency-Key): 반납 재전송 → 같은 응답 + Idempotent-Replayed
        UUID checkoutId = jdbc.queryForObject("SELECT id FROM eqp.checkout WHERE client_op_id = ?", UUID.class, op);
        String key = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            var res = mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + checkoutId + "/return", "{}")
                    .header("Idempotency-Key", key), eng, "ENG")).andExpect(status().isOk()).andReturn();
            if (i == 1) {
                assertThat(res.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
            }
        }
        assertThat(events("eqp.equipment.returned", id)).hasSize(1);
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + checkoutId + "/return", "{}"), eng, "ENG"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQP-409-008"));
    }

    @Test
    @DisplayName("반납 → eqp.equipment.returned(days = 반출~반납 KST 양 끝 포함) → Relay → CST 원장 EQUIP_USE 1행(일수 × EQUIP_DAILY), "
            + "재전달 멱등 / 예약 IN_USE → RETURNED / 사후 점검 이상 → BROKEN + 고장 + eqp.fault.reported, 고장 장비 예약 409 EQP-409-003")
    void returnPublishesCostEventToCst() throws Exception {
        UUID prj = project();
        UUID id = validEquipment("NDT-UPV", 200);
        String code = jdbc.queryForObject("SELECT code FROM eqp.equipment WHERE id = ?", String.class, id);
        jdbc.update("""
                INSERT INTO cst.rate_table(id, rate_kind, key_code, value, valid_from, source_ref, created_by)
                VALUES (gen_random_uuid(), 'EQUIP_DAILY', ?, 120000, DATE '2000-01-01', '합성 손료', gen_random_uuid())""",
                code);
        var res = mvc.perform(as(postJson("/api/v1/eqp/reservations", ("{\"equipmentId\":\"%s\",\"projectId\":\"%s\","
                + "\"from\":\"%s\",\"to\":\"%s\"}").formatted(id, prj, today(), today().plusDays(3))), eng, "ENG"))
                .andExpect(status().isCreated()).andReturn();
        String rid = json(res).get("id").asText();
        mvc.perform(as(postJson("/api/v1/eqp/reservations/" + rid + ":approve", "{}"), pm, "PM")).andExpect(status().isOk());
        // 다른 프로젝트는 예약 기간에 예약 없이 반출 불가
        String token = label(id, false);
        checkout(token, project(), UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-011"));
        var co = checkout(token, prj, UUID.randomUUID(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.reservationId").value(rid)).andReturn();
        String checkoutId = json(co).get("checkoutId").asText();
        assertThat(jdbc.queryForObject("SELECT status FROM eqp.reservation WHERE id = ?::uuid", String.class, rid))
                .isEqualTo("IN_USE");
        clock.advance(Duration.ofDays(2));
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + checkoutId + "/return", "{\"postCheck\":{\"abnormal\":false}}"),
                        eng, "ENG")).andExpect(status().isOk()).andExpect(jsonPath("$.usageDays").value(3))
                .andExpect(jsonPath("$.equipmentStatus").value("AVAILABLE"));
        assertThat(jdbc.queryForObject("SELECT status FROM eqp.reservation WHERE id = ?::uuid", String.class, rid))
                .isEqualTo("RETURNED");
        var ev = events("eqp.equipment.returned", id);
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).get("rentalId").asText()).isEqualTo(checkoutId);
        assertThat(ev.get(0).get("days").asInt()).isEqualTo(3);
        assertThat(ev.get(0).get("equipmentCode").asText()).isEqualTo(code);

        relay.pollOnce();
        var row = jdbc.queryForMap("SELECT * FROM cst.cost_ledger_entry WHERE source_id = ?::uuid", checkoutId);
        assertThat(row).containsEntry("source_type", "EQUIP").containsEntry("cost_element", "EQUIP_USE")
                .containsEntry("project_id", prj);
        assertThat((BigDecimal) row.get("amount_krw")).isEqualByComparingTo("360000");
        // at-least-once 재전달(새 eventId, 같은 payload) → CST 원장 1행 유지
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, event_version, payload, headers,
                                              occurred_at, next_attempt_at)
                SELECT gen_random_uuid(), aggregate_type, aggregate_id, event_type, event_version, payload, headers, ?, ?
                  FROM core.outbox_event WHERE event_type = 'eqp.equipment.returned' AND aggregate_id = ?""", now, now, id);
        relay.pollOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.cost_ledger_entry WHERE source_id = ?::uuid",
                Integer.class, checkoutId)).isEqualTo(1);

        // 사후 점검 이상 → BROKEN · 고장 신고
        var co2 = checkout(token, prj, UUID.randomUUID(), null).andExpect(status().isOk()).andReturn();
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + json(co2).get("checkoutId").asText() + "/return",
                        "{\"postCheck\":{\"abnormal\":true,\"symptom\":\"합성: 프로브 케이블 단선\"}}"), eng, "ENG"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.equipmentStatus").value("BROKEN"))
                .andExpect(jsonPath("$.faultId").isNotEmpty());
        assertThat(events("eqp.fault.reported", id)).hasSize(1);
        mvc.perform(as(postJson("/api/v1/eqp/reservations", "{\"equipmentId\":\"%s\",\"projectId\":\"%s\",\"from\":\"%s\",\"to\":\"%s\"}"
                .formatted(id, prj, today().plusDays(30), today().plusDays(31))), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("EQP-409-003"));
    }

    // ------------------------------------------------------------------ 일일 검교정 스캔

    @Test
    @DisplayName("FR-EQP-004 · B6 v0.2: 스캔 due-soon(D-30 기준, 남은 25일) 1회 · 재실행 0회 · D-7 진입 시 1회 더 / "
            + "만료 → eqp.calibration.expired(멱등키 equipmentId+expiredOn) 1회 + CAL_DUE 전이, 반출 중 장비는 activeCheckoutIds 포함")
    void calibrationScanIdempotent() throws Exception {
        UUID soon = validEquipment("NDT-RBR", 25);
        LocalDate due = today().plusDays(25);
        scanService.scan(today());
        scanService.scan(today());
        var ds = events("eqp.calibration.due-soon", soon);
        assertThat(ds).hasSize(1);
        assertThat(ds.get(0).get("thresholdDays").asInt()).isEqualTo(30);
        assertThat(ds.get(0).get("daysLeft").asInt()).isEqualTo(25);
        assertThat(ds.get(0).get("nextDueOn").asText()).isEqualTo(due.toString());

        UUID expiring = validEquipment("NDT-RBR", 1);
        UUID out = validEquipment("NDT-RBR", 1);
        UUID prj = project();
        String outCheckout = json(checkout(label(out, false), prj, UUID.randomUUID(), null).andExpect(status().isOk())
                .andReturn()).get("checkoutId").asText();
        LocalDate expiredOn = today().plusDays(2);
        clock.advance(Duration.ofDays(20));                 // soon: 남은 5일 → D-7
        LocalDate t = today();
        var r1 = mvc.perform(as(postJson("/api/v1/eqp/calibration-scan:run?date=" + t, "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(r1).get("expiredPublished").asInt()).isGreaterThanOrEqualTo(2);
        mvc.perform(as(postJson("/api/v1/eqp/calibration-scan:run?date=" + t, "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.expiredPublished").value(0))
                .andExpect(jsonPath("$.dueSoonPublished").value(0));
        mvc.perform(as(postJson("/api/v1/eqp/calibration-scan:run", "{}"), eng, "ENG")).andExpect(status().isForbidden());

        ds = events("eqp.calibration.due-soon", soon);
        assertThat(ds).hasSize(2);
        assertThat(ds.get(1).get("thresholdDays").asInt()).isEqualTo(7);

        var ex = events("eqp.calibration.expired", expiring);
        assertThat(ex).hasSize(1);
        assertThat(ex.get(0).get("expiredOn").asText()).isEqualTo(expiredOn.toString());
        assertThat(ex.get(0).get("activeCheckoutIds")).isEmpty();
        assertThat(statusOf(expiring)).isEqualTo("CAL_DUE");
        var exOut = events("eqp.calibration.expired", out);
        assertThat(exOut).hasSize(1);
        assertThat(exOut.get(0).get("activeCheckoutIds").get(0).asText()).isEqualTo(outCheckout);
        assertThat(statusOf(out)).isEqualTo("CHECKED_OUT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.notification_log WHERE equipment_id = ? AND kind = "
                + "'CAL_EXPIRED' AND event_id IS NOT NULL", Integer.class, expiring)).isEqualTo(1);

        // 다음 날 재실행해도 같은 expiredOn → 추가 발행 없음 / 반납 후 AVAILABLE → 다음 스캔이 CAL_DUE 로 보정(이벤트 없음)
        mvc.perform(as(postJson("/api/v1/eqp/checkouts/" + outCheckout + "/return", "{}"), eng, "ENG"))
                .andExpect(status().isOk());
        assertThat(statusOf(out)).isEqualTo("AVAILABLE");
        var r3 = scanService.scan(t.plusDays(1));
        assertThat(events("eqp.calibration.expired", out)).hasSize(1);
        assertThat(statusOf(out)).isEqualTo("CAL_DUE");
        assertThat(r3.movedToCalDue()).isGreaterThanOrEqualTo(1);
        // CAL_DUE 장비는 Override 없이 반출 불가(EQP-409-001)
        checkout(label(out, false), prj, UUID.randomUUID(), null).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EQP-409-001"));
        // 재교정(PASS) → CAL_DUE → IN_CALIBRATION → AVAILABLE
        calibrate(out, t, t.plusDays(365), "PASS").andExpect(status().isCreated());
        assertThat(statusOf(out)).isEqualTo("AVAILABLE");
    }

    // ------------------------------------------------------------------ 드론

    @Test
    @DisplayName("TC-EQP-010: 비행로그 경로가 승인 폴리곤 밖 → out_of_area=true + eqp.flight.logged(outOfArea) / 안 → false / 승인 기간 밖 경고 / "
            + "TC-EQP-011: 자격 만료 조종자 422 EQP-422-004(HRQ_LICENSE_INVALID), HRQ 장애 422 EQP-422-005(Fail-closed), 보험 만료 422 EQP-422-003")
    void tcEqp010_011_drone() throws Exception {
        UUID drone = register("UAV");
        assertThat(statusOf(drone)).isEqualTo("AVAILABLE");   // 교정 비대상 → 등록 즉시 사용 가능
        UUID prj = project();
        mvc.perform(put("/api/v1/eqp/drones/" + drone + "/profile").header("X-Actor-Id", admin.toString())
                .header("X-Roles", "ADMIN").contentType(MediaType.APPLICATION_JSON)
                .content("{\"registrationNo\":\"SYN-UAV-%d\",\"mtowKg\":6.5,\"insurancePolicyNo\":\"SYN-INS-1\",\"insuranceExpiresOn\":\"%s\"}"
                        .formatted(SEQ.incrementAndGet(), today().plusDays(100)))).andExpect(status().isOk());
        var ap = mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-approvals", ("{\"projectId\":\"%s\","
                        + "\"approvalNo\":\"SYN-DOS-0001\",\"area\":[[127.000,37.500],[127.010,37.500],[127.010,37.510],"
                        + "[127.000,37.510]],\"maxAltM\":120,\"validFrom\":\"%s\",\"validTo\":\"%s\"}").formatted(prj,
                        today().atStartOfDay(SEOUL).toInstant(), today().plusDays(7).atStartOfDay(SEOUL).toInstant())),
                pm, "PM")).andExpect(status().isCreated()).andReturn();
        String approval = json(ap).get("id").asText();
        String start = today().atTime(10, 0).atZone(SEOUL).toInstant().toString();
        String end = today().atTime(10, 25).atZone(SEOUL).toInstant().toString();
        String flight = "{\"projectId\":\"%s\",\"approvalId\":\"%s\",\"pilotId\":\"%s\",\"startedAt\":\"%s\",\"endedAt\":\"%s\","
                + "\"track\":%s}";
        String inside = "[[127.002,37.502,30],[127.005,37.505,60],[127.008,37.508,45]]";
        String outside = "[[127.002,37.502,30],[127.015,37.505,60],[127.008,37.508,45]]";
        UUID pilot = eng;   // EQP-03: 조종자 = 요청자 본인

        var in = mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                flight.formatted(prj, approval, pilot, start, end, inside)), eng, "ENG")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outOfArea").value(false)).andExpect(jsonPath("$.maxAltM").value(60.0)).andReturn();
        var outRes = mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        flight.formatted(prj, approval, pilot, start, end, outside)), eng, "ENG")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.outOfArea").value(true))
                .andExpect(jsonPath("$.warnings[0]").value("EQP_FLIGHT_OUT_OF_AREA")).andReturn();
        UUID outLog = UUID.fromString(json(outRes).get("flightLogId").asText());
        assertThat(jdbc.queryForObject("SELECT out_of_area FROM eqp.flight_log WHERE id = ?", Boolean.class, outLog)).isTrue();
        var logged = events("eqp.flight.logged", outLog);
        assertThat(logged).hasSize(1);
        assertThat(logged.get(0).get("outOfArea").asBoolean()).isTrue();
        assertThat(logged.get(0).get("projectId").asText()).isEqualTo(prj.toString());
        assertThat(events("eqp.flight.logged", UUID.fromString(json(in).get("flightLogId").asText())).get(0)
                .get("outOfArea").asBoolean()).isFalse();
        // 승인 기간 밖 → 경고(등록은 됨)
        String late = today().plusDays(9).atTime(10, 0).atZone(SEOUL).toInstant().toString();
        String lateEnd = today().plusDays(9).atTime(10, 20).atZone(SEOUL).toInstant().toString();
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        flight.formatted(prj, approval, pilot, late, lateEnd, inside)), eng, "ENG"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.outsideApprovalPeriod").value(true));

        // 다른 조종자 대리 등록은 ADMIN 만(EQP-03)
        UUID expiredPilot = UUID.randomUUID();
        pilots.markInvalid(expiredPilot);
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        flight.formatted(prj, approval, expiredPilot, start, end, inside)), admin, "ADMIN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EQP-422-004"))
                .andExpect(jsonPath("$.reason").value("HRQ_LICENSE_INVALID"));
        UUID outagePilot = UUID.randomUUID();
        pilots.markFailing(outagePilot);
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        flight.formatted(prj, approval, outagePilot, start, end, inside)), admin, "ADMIN"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EQP-422-005"));
        String afterInsurance = today().plusDays(101).atTime(10, 0).atZone(SEOUL).toInstant().toString();
        String afterInsuranceEnd = today().plusDays(101).atTime(10, 30).atZone(SEOUL).toInstant().toString();
        mvc.perform(as(postJson("/api/v1/eqp/drones/" + drone + "/flight-logs",
                        flight.formatted(prj, approval, pilot, afterInsurance, afterInsuranceEnd, inside)), eng, "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("EQP-422-003"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.flight_log WHERE equipment_id = ?", Integer.class, drone))
                .isEqualTo(3);
        MvcResult list = mvc.perform(as(get("/api/v1/eqp/drones/" + drone + "/flight-logs"), qa, "QA"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(list)).hasSize(3);
        mvc.perform(as(get("/api/v1/eqp/drones/" + drone + "/flight-logs"), eng, "ENG")).andExpect(status().isForbidden());
    }
}
