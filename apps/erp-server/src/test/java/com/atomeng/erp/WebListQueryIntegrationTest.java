package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 웹 2차 화면용 읽기 API — FAC 도래 목록 · EQP 교정 현황 · DEM 목록(프로젝트 · 일지 · 도서 · 트랜스미털) · FLD 회차 · 사진.
 * 권한 · 범위 규칙은 각 단건 조회와 같다. 데이터는 전부 합성.
 */
class WebListQueryIntegrationTest extends IntegrationTestBase {

    UUID pm;
    UUID admin;

    @BeforeEach
    void setUp() {
        clock.reset();
        pm = actor();
        admin = actor();
    }

    private JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String roles, int expect) throws Exception {
        MvcResult r = mvc.perform(as(b, actor, roles)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as("%s → %s", r.getRequest().getRequestURI(), body).isEqualTo(expect);
        return body.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("FAC GET /fac/dues — 전 시설물 도래 + daysLeft(오늘 기준), withinDays 필터, fac:due:read 없는 ENG 403")
    void facDueList() throws Exception {
        String name = "합성 도래목록 " + UUID.randomUUID();
        JsonNode b = call(postJson("/api/v1/fac/buildings", """
                {"name":"%s","roadAddress":"서울 합성구 도래로 %d","floorsAbove":3,"floorsBelow":0,
                 "approvalDate":"1998-03-01","dataSource":"MANUAL"}""".formatted(name,
                ThreadLocalRandom.current().nextInt(100000)))
                .header("Idempotency-Key", UUID.randomUUID().toString()), pm, "PM", 201);
        String id = b.get("id").asText();
        call(put("/api/v1/fac/buildings/" + id + "/classification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sitbeopClass\":\"CLASS3\",\"bmaTarget\":false,\"evidence\":{\"note\":\"합성\"}}"), pm, "PM", 200);
        JsonNode all = call(get("/api/v1/fac/dues"), pm, "PM", 200);
        JsonNode mine = null;
        for (JsonNode d : all) {
            if (d.get("buildingId").asText().equals(id)) {
                mine = d;
            }
        }
        assertThat(mine).as("새 시설물 도래").isNotNull();
        assertThat(mine.get("buildingName").asText()).isEqualTo(name);
        LocalDate due = LocalDate.parse(mine.get("dueDate").asText());
        assertThat(mine.get("daysLeft").asLong()).isEqualTo(ChronoUnit.DAYS.between(today(), due));
        long within = Math.max(0, mine.get("daysLeft").asLong()) + 1;
        assertThat(call(get("/api/v1/fac/dues").param("withinDays", String.valueOf(within)), pm, "SALES", 200)
                .findValuesAsText("buildingId")).contains(id);
        if (mine.get("daysLeft").asLong() > 1) {
            assertThat(call(get("/api/v1/fac/dues").param("withinDays", "0"), pm, "PM", 200)
                    .findValuesAsText("id")).doesNotContain(mine.get("id").asText());
        }
        assertThat(call(get("/api/v1/fac/dues"), actor(), "ENG", 403).get("code").asText()).startsWith("CORE-403");
    }

    @Test
    @DisplayName("EQP GET /eqp/calibration-status — 교정 유효 · 임박 · 이력 없음 · 불필요, withinDays = 만료 목록")
    void eqpCalibrationStatus() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String soon = call(postJson("/api/v1/eqp/equipment", """
                {"typeCode":"NDT-UPV","serialNo":"WL-%s-1","model":"합성"}""".formatted(suffix)), admin, "ADMIN", 201)
                .get("id").asText();
        call(postJson("/api/v1/eqp/equipment/" + soon + "/calibrations", """
                {"calibratedOn":"%s","nextDueOn":"%s","result":"PASS","certNo":"KC-WL-%s"}""".formatted(
                today().minusDays(300), today().plusDays(20), suffix)), admin, "ADMIN", 201);
        String none = call(postJson("/api/v1/eqp/equipment", """
                {"typeCode":"SRV-LVL","serialNo":"WL-%s-2"}""".formatted(suffix)), admin, "ADMIN", 201).get("id").asText();
        String core = call(postJson("/api/v1/eqp/equipment", """
                {"typeCode":"NDT-CORE","serialNo":"WL-%s-3"}""".formatted(suffix)), admin, "ADMIN", 201).get("id").asText();
        JsonNode all = call(get("/api/v1/eqp/calibration-status"), actor(), "ENG", 200);
        assertThat(statusOf(all, soon)).isEqualTo("DUE_SOON");
        assertThat(item(all, soon).get("daysLeft").asInt()).isEqualTo(20);
        assertThat(statusOf(all, none)).isEqualTo("NONE");
        assertThat(statusOf(all, core)).isEqualTo("NOT_REQUIRED");
        JsonNode expiring = call(get("/api/v1/eqp/calibration-status").param("withinDays", "10"), admin, "ADMIN", 200);
        assertThat(expiring.findValuesAsText("equipmentId")).contains(none).doesNotContain(soon, core);
        call(get("/api/v1/eqp/calibration-status"), actor(), "FIN", 403);
    }

    private static JsonNode item(JsonNode list, String id) {
        for (JsonNode n : list) {
            if (n.get("equipmentId").asText().equals(id)) {
                return n;
            }
        }
        throw new AssertionError("장비 없음 " + id);
    }

    private static String statusOf(JsonNode list, String id) {
        return item(list, id).get("calStatus").asText();
    }

    @Test
    @DisplayName("DEM 목록 — 프로젝트(ALL) · 도서 · 트랜스미털, 감리일지는 프로젝트 범위(비투입 PM 403 · QA ALL)")
    void demLists() throws Exception {
        UUID client = createClient(actor(), "합성 DEM 목록 " + UUID.randomUUID());
        String project = call(postJson("/api/v1/core/projects", """
                {"name":"합성 해체 목록","serviceType":"DEMOLITION_SUPERVISION","clientId":"%s"}""".formatted(client)),
                pm, "PM", 201).get("id").asText();
        // DEM-02: PM 의 DEM 권한은 PROJECT 범위 — 담당 PM 투입(RESP)
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성PM', DATE '2020-01-01', ?, ?) ON CONFLICT (id) DO NOTHING", pm,
                "WLQ-" + pm.toString().substring(0, 8), pm, pm);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?::uuid, ?, 'RESP', DATE '2020-01-01', DATE '2099-12-31')", project, pm);
        JsonNode dem = call(postJson("/api/v1/dem/projects", """
                {"projectId":"%s","packages":[{"packageType":"DEM_SUPERVISION"},{"packageType":"STR_DESIGN"}]}"""
                .formatted(project)), pm, "PM", 201);
        String wp = dem.get("workPackages").get(0).get("id").asText();
        assertThat(call(get("/api/v1/dem/projects"), actor(), "QA", 200).findValuesAsText("projectId")).contains(project);
        call(postJson("/api/v1/dem/work-packages/" + wp + "/documents", "{\"docNo\":\"S-900\",\"title\":\"합성 도면\"}"),
                pm, "PM", 201);
        JsonNode docs = call(get("/api/v1/dem/work-packages/" + wp + "/documents"), pm, "PM", 200);
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("docNo").asText()).isEqualTo("S-900");
        assertThat(docs.get(0).get("unresolvedComments").asInt()).isZero();
        assertThat(call(get("/api/v1/dem/work-packages/" + wp + "/transmittals"), pm, "PM", 200)).isEmpty();
        assertThat(call(get("/api/v1/dem/work-packages/" + wp + "/daily-logs"), actor(), "QA", 200)).isEmpty();
        assertThat(call(get("/api/v1/dem/work-packages/" + wp + "/daily-logs"), actor(), "PM", 403).get("code").asText())
                .isEqualTo("CORE-403-002");
        assertThat(call(get("/api/v1/dem/work-packages/" + UUID.randomUUID() + "/documents"), pm, "PM", 404)
                .get("code").asText()).isEqualTo("DEM-404-001");
    }

    @Test
    @DisplayName("FLD 회차 목록 · 사진 목록 — 프로젝트 범위(비투입 PM 403), 없는 회차 404")
    void fldLists() throws Exception {
        UUID client = createClient(actor(), "합성 FLD 목록 " + UUID.randomUUID());
        String project = call(postJson("/api/v1/core/projects", """
                {"name":"합성 회차 목록","serviceType":"PRECISE_DIAG","clientId":"%s"}""".formatted(client)),
                pm, "PM", 201).get("id").asText();
        assertThat(call(get("/api/v1/fld/rounds").param("projectId", project), actor(), "QA", 200)).isEmpty();
        assertThat(call(get("/api/v1/fld/rounds").param("projectId", project), actor(), "PM", 403).get("code").asText())
                .isEqualTo("CORE-403-002");
        assertThat(call(get("/api/v1/fld/rounds/" + UUID.randomUUID() + "/photos"), actor(), "QA", 404).get("code")
                .asText()).isEqualTo("FLD-404-001");
    }
}
