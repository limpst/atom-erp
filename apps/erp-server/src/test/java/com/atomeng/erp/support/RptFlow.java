package com.atomeng.erp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * RPT 실제 흐름 도우미(통합 테스트 공용): 프로젝트(PM 투입) → 보고서 생성 → 스냅샷 동결(합성 FLD export) → compose →
 * 등급 확정 → 렌더(스텁 PDF) → 검토 요청 → qar.review.approved(QAR 스텁 이벤트, outbox → Relay) → 발행.
 * 합성 결함은 기둥(COLUMN) 그룹만 두고 균열폭으로 원하는 부재 등급 a~e 를 만든다(⚠️ 예시 규칙 2025.1 · 2026.1 모두 같은 등급):
 * a 0.05 · b 0.15 · c 0.25 · d 0.50 · e 1.20mm → 시설물 점수 1~5 → 안전등급 A~E.
 */
public class RptFlow {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));
    public static final Map<String, String> WIDTH = Map.of("A", "0.05", "B", "0.15", "C", "0.25", "D", "0.50", "E", "1.20");
    public static final Map<String, String> REPORT_TYPE = Map.of("REGULAR", "RT_REG", "DETAILED", "RT_PRC",
            "PRECISION_DIAG", "RT_PSD", "EMERGENCY", "RT_EMG", "BMA_CHECK", "RT_BLD");

    private final MockMvc mvc;
    private final JdbcTemplate jdbc;
    private final ObjectMapper om;
    private final OutboxRelay relay;

    public RptFlow(MockMvc mvc, JdbcTemplate jdbc, ObjectMapper om, OutboxRelay relay) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.om = om;
        this.relay = relay;
    }

    public record Published(UUID projectId, UUID reportId, UUID revisionId, UUID eventId, UUID pm) {}

    public static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UUID actor, String roles) {
        return b.header("X-Actor-Id", actor.toString()).header("X-Roles", roles);
    }

    public JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsByteArray());
    }

    public JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String role, int expectStatus) throws Exception {
        MvcResult r = mvc.perform(as(b, actor, role)).andExpect(status().is(expectStatus)).andReturn();
        byte[] body = r.getResponse().getContentAsByteArray();
        return body.length == 0 ? null : om.readTree(body);
    }

    public static MockHttpServletRequestBuilder postJson(String url, String json) {
        return post(url).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    /** 합성 직원(core.employee) — actorId = employeeId */
    public UUID employee(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성기술자', DATE '2020-01-01', ?, ?)", id, prefix + "-" + SEQ.incrementAndGet(), id, id);
        return id;
    }

    public void member(UUID project, UUID employee, String role) {
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, ?, DATE '2020-01-01', DATE '2099-12-31')", project, employee, role);
    }

    private static final java.util.concurrent.atomic.AtomicLong PNU = new java.util.concurrent.atomic.AtomicLong(
            System.nanoTime() % 1_000_000_000L + 500_000_000L);

    /**
     * FAC 시설물(수기 등록, 소유 고객사 = 프로젝트 발주처) — rpt.report.published 를 FAC 가 등급 이력으로 남기려면 실제 시설물이어야
     * 하고, 보안 검토 RPT-02b 로 스냅샷 동은 FAC 에 있고 프로젝트 발주처 소유여야 한다.
     */
    public UUID facBuilding(UUID pm, String name, UUID project) throws Exception {
        String pnu = "11%017d".formatted(PNU.incrementAndGet());
        JsonNode b = call(postJson("/api/v1/fac/buildings", """
                {"name":"%s","dongName":"","pnu":"%s","roadAddress":"서울 합성구 RPT로 %s","structureNm":"철근콘크리트구조",
                 "floorsAbove":5,"floorsBelow":1,"approvalDate":"2001-03-31","dataSource":"MANUAL","ownerClientId":"%s"}"""
                .formatted(name, pnu, pnu.substring(14), clientOf(project)))
                .header("Idempotency-Key", UUID.randomUUID().toString()), pm, "PM", 201);
        return UUID.fromString(b.get("id").asText());
    }

    /** 프로젝트 발주 고객사 */
    public UUID clientOf(UUID project) {
        return jdbc.queryForObject("SELECT client_id FROM core.project WHERE id = ?", UUID.class, project);
    }

    /** 프로젝트 발주처 소유 FAC 시설물(직접 SQL, ACTIVE) — RPT-02b 스냅샷 동 */
    public UUID ownedBuilding(UUID project) {
        UUID b = UUID.randomUUID();
        ensureOwnedBuilding(b, clientOf(project));
        return b;
    }

    /**
     * 시설물이 없으면 고객사 소유로 만들고, 있는데 소유 고객사가 없으면 그 고객사 소유로 정한다(시험 준비 — RPT-02b).
     */
    public void ensureOwnedBuilding(UUID buildingId, UUID clientId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT b.site_id, s.owner_client_id FROM fac.building b "
                + "JOIN fac.site s ON s.id = b.site_id WHERE b.id = ?", buildingId);
        UUID by = UUID.randomUUID();
        if (rows.isEmpty()) {
            UUID site = UUID.randomUUID();
            jdbc.update("INSERT INTO fac.site(id, name, owner_client_id, created_by, updated_by) VALUES (?, ?, ?, ?, ?)",
                    site, "합성 RPT 단지", clientId, by, by);
            jdbc.update("INSERT INTO fac.building(id, site_id, name, road_address, structure_nm, status, data_source, "
                    + "created_by, updated_by) VALUES (?, ?, '합성 RPT 동', '서울 합성구 RPT로 1', '철근콘크리트구조', 'ACTIVE', "
                    + "'MANUAL', ?, ?)", buildingId, site, by, by);
        } else if (rows.get(0).get("owner_client_id") == null) {
            jdbc.update("UPDATE fac.site SET owner_client_id = ? WHERE id = ?", clientId, rows.get(0).get("site_id"));
        }
    }

    /** 시설물의 소유 고객사(없거나 시설물이 없으면 null) */
    public UUID ownerOf(UUID buildingId) {
        List<UUID> owners = jdbc.queryForList("SELECT s.owner_client_id FROM fac.building b JOIN fac.site s "
                + "ON s.id = b.site_id WHERE b.id = ?", UUID.class, buildingId);
        return owners.isEmpty() ? null : owners.get(0);
    }

    /**
     * RPT-07: 등급 결정자 = 지정 책임기술자(RESP) + HRQ 자격 유효. 합성 HRQ 직원 · 승인된 건축기사 자격(무기한)을 둔다
     * (등록 분야 아님 — HRQ 등록요건 시험에 영향 0).
     */
    public void qualify(UUID employee) {
        jdbc.update("""
                INSERT INTO hrq.employee(id, emp_no, name, field, employment_type, hired_on, created_by, updated_by)
                SELECT id, emp_no, name, 'ARCH', 'FULLTIME', DATE '2020-01-01', id, id FROM core.employee WHERE id = ?
                ON CONFLICT (id) DO NOTHING""", employee);
        Integer has = jdbc.queryForObject("SELECT count(*) FROM hrq.credential WHERE employee_id = ? AND status = 'VALID'",
                Integer.class, employee);
        if (has == null || has == 0) {
            jdbc.update("""
                    INSERT INTO hrq.credential(id, employee_id, type_code, cert_no, acquired_on, status, approved_by,
                                               approved_at, created_by, updated_by)
                    VALUES (?, ?, 'ARCH_ENG', ?, DATE '2019-01-01', 'VALID', ?, now(), ?, ?)""", UUID.randomUUID(), employee,
                    "SYN-RPT-" + SEQ.incrementAndGet(), employee, employee, employee);
        }
    }

    /** PM(책임기술자 RESP 투입 · HRQ 자격 유효) 이 만든 합성 프로젝트 */
    public UUID project(UUID pm) throws Exception {
        UUID sales = UUID.randomUUID();
        JsonNode c = call(postJson("/api/v1/core/clients", "{\"name\":\"합성발주처-rpt-" + UUID.randomUUID()
                + "\",\"clientType\":\"PRIVATE\"}"), sales, "SALES", 201);
        return project(pm, UUID.fromString(c.get("id").asText()));
    }

    /** 주어진 발주 고객사의 합성 프로젝트(PM = RESP · 자격 유효) */
    public UUID project(UUID pm, UUID clientId) throws Exception {
        JsonNode p = call(postJson("/api/v1/core/projects", "{\"name\":\"합성 RPT 프로젝트\",\"serviceType\":\"PRECISE_DIAG\","
                + "\"clientId\":\"" + clientId + "\"}"), pm, "PM", 201);
        UUID id = UUID.fromString(p.get("id").asText());
        member(id, pm, "RESP");
        qualify(pm);
        return id;
    }

    public JsonNode createReport(UUID pm, UUID project, UUID facility, String reportType, LocalDate inspectionDate)
            throws Exception {
        return call(postJson("/api/v1/rpt/reports", """
                {"projectId":"%s","facilityId":"%s","reportType":"%s","title":"합성 시설물 %s 보고서",
                 "inspectionDate":"%s"}""".formatted(project, facility, reportType, reportType, inspectionDate)),
                pm, "PM", 201);
    }

    /** 기둥 그룹 2개(1F · 2F, 각 10개) + 그룹마다 균열 2건(서로 다른 부재) → 부재 등급 = rating */
    public static String snapshotBody(UUID buildingId, String grade, String extraDefects) {
        String w = WIDTH.get(grade);
        List<String> defects = new ArrayList<>();
        int n = 0;
        for (String floor : List.of("1F", "2F")) {
            for (int k = 1; k <= 2; k++) {
                n++;
                defects.add(("{\"id\":\"D-%04d\",\"buildingCode\":\"B01\",\"floorCode\":\"%s\",\"memberType\":\"COLUMN\","
                        + "\"memberId\":\"%s-COL-%02d\",\"defectType\":\"CRACK\",\"widthMm\":%s,\"lengthM\":1.20,"
                        + "\"polyline\":[[%d,2000],[%d,2600]]}").formatted(n, floor, floor, k, w, 1000 * n, 1000 * n + 400));
            }
        }
        if (extraDefects != null) {
            defects.add(extraDefects);
        }
        return """
                {"facilityName":"합성 사옥","buildings":[{"code":"B01","name":"본관","buildingId":"%s","grossAreaM2":5200.5}],
                 "memberGroups":[{"buildingCode":"B01","floorCode":"1F","memberType":"COLUMN","memberCount":10},
                                 {"buildingCode":"B01","floorCode":"2F","memberType":"COLUMN","memberCount":10}],
                 "defects":[%s]}""".formatted(buildingId, String.join(",", defects));
    }

    public JsonNode freeze(UUID pm, UUID report, String body) throws Exception {
        return call(postJson("/api/v1/rpt/reports/" + report + "/snapshots", body), pm, "PM", 201);
    }

    public JsonNode compose(UUID pm, UUID rev) throws Exception {
        return call(post("/api/v1/rpt/revisions/" + rev + "/compose"), pm, "PM", 200);
    }

    public String etag(UUID pm, UUID rev) throws Exception {
        return mvc.perform(as(get("/api/v1/rpt/revisions/" + rev), pm, "PM")).andExpect(status().isOk()).andReturn()
                .getResponse().getHeader("ETag");
    }

    public JsonNode decide(UUID pm, UUID rev, String grade, String reason, List<String> evidence, int expect)
            throws Exception {
        String body = om.writeValueAsString(Map.of("finalGrade", grade, "reason", reason == null ? "" : reason,
                "evidenceDefectIds", evidence == null ? List.of() : evidence));
        return call(put("/api/v1/rpt/revisions/" + rev + "/grade/decision").header("If-Match", etag(pm, rev))
                .contentType(MediaType.APPLICATION_JSON).content(body), pm, "PM", expect);
    }

    public JsonNode render(UUID pm, UUID rev) throws Exception {
        return call(post("/api/v1/rpt/revisions/" + rev + "/render"), pm, "PM", 200);
    }

    public JsonNode reviewRequest(UUID pm, UUID rev) throws Exception {
        return call(post("/api/v1/rpt/revisions/" + rev + "/review-request"), pm, "PM", 200);
    }

    /** QAR 미구현 — qar.review.approved/rejected 를 outbox 에 넣고 Relay 로 전달(실제 경로와 동일) */
    public UUID qarDecision(UUID rev, boolean approved) {
        UUID eventId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at)
                VALUES (?, 'Review', ?, ?, ?::jsonb, now() - interval '1 minute')""", eventId, reviewId,
                approved ? "qar.review.approved" : "qar.review.rejected",
                "{\"reviewId\":\"%s\",\"revisionId\":\"%s\",\"decidedBy\":\"%s\",\"comment\":\"합성 검토\"}"
                        .formatted(reviewId, rev, UUID.randomUUID()));
        drainUntilPublished(eventId);
        return eventId;
    }

    /** 다른 테스트가 남긴 미전달 이벤트가 많아도(배치 상한) 이 이벤트가 전달될 때까지 Relay 를 돌린다 */
    public void drainUntilPublished(UUID eventId) {
        for (int i = 0; i < 200; i++) {
            Boolean done = jdbc.queryForObject("SELECT published_at IS NOT NULL FROM core.outbox_event WHERE id = ?",
                    Boolean.class, eventId);
            if (Boolean.TRUE.equals(done) || relay.pollOnce().published() == 0) {
                return;
            }
        }
    }

    /** 미전달 이벤트가 없어질 때까지 Relay 반복 */
    public void drain() {
        for (int i = 0; i < 200 && relay.pollOnce().published() > 0; i++) {
            // 반복
        }
    }

    public JsonNode publish(UUID pm, UUID rev) throws Exception {
        return call(post("/api/v1/rpt/revisions/" + rev + "/publish"), pm, "PM", 200);
    }

    /** compose → 확정(=산정) → 렌더 → 검토 요청 → QAR 승인 → 발행 */
    public JsonNode composeToPublish(UUID pm, UUID rev) throws Exception {
        JsonNode g = compose(pm, rev);
        decide(pm, rev, g.get("computedGrade").asText(), null, null, 200);
        render(pm, rev);
        reviewRequest(pm, rev);
        qarDecision(rev, true);
        return publish(pm, rev);
    }

    /**
     * FAC 연동용 전체 흐름: 시설물 1동(buildingId)의 안전등급 grade 를 점검 종류(FAC 값)·수행일로 발행.
     * @return 발행 이벤트(outbox rpt.report.published) id 포함 — Relay 전달은 호출 테스트가 한다
     */
    public Published publishGrade(UUID buildingId, String grade, String facInspectionType, LocalDate performedOn)
            throws Exception {
        UUID pm = employee("RPT-PM");
        UUID owner = ownerOf(buildingId);
        UUID project = owner == null ? project(pm) : project(pm, owner);
        ensureOwnedBuilding(buildingId, clientOf(project));   // RPT-02b: 스냅샷 동은 프로젝트 발주처 소유 FAC 시설물
        JsonNode report = createReport(pm, project, buildingId, REPORT_TYPE.get(facInspectionType), performedOn);
        UUID reportId = UUID.fromString(report.get("id").asText());
        UUID rev = UUID.fromString(report.get("currentRevisionId").asText());
        JsonNode frozen = freeze(pm, reportId, snapshotBody(buildingId, grade, null));
        if (frozen.get("blockingCount").asInt() != 0) {
            throw new IllegalStateException("합성 스냅샷 BLOCKING: " + frozen);
        }
        JsonNode g = compose(pm, rev);
        if (!g.get("computedGrade").asText().equals(grade)) {
            throw new IllegalStateException("합성 결함으로 산정 등급 " + grade + " 기대 — 실제 " + g);
        }
        decide(pm, rev, grade, null, null, 200);
        render(pm, rev);
        reviewRequest(pm, rev);
        qarDecision(rev, true);
        publish(pm, rev);
        UUID eventId = jdbc.queryForObject("SELECT id FROM core.outbox_event WHERE event_type = 'rpt.report.published' "
                + "AND aggregate_id = ? ORDER BY occurred_at DESC LIMIT 1", UUID.class, reportId);
        return new Published(project, reportId, rev, eventId, pm);
    }
}
