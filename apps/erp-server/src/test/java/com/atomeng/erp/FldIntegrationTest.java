package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.erp.fld.domain.Hlc;
import com.atomeng.erp.qar.adapter.out.fld.FldEvidenceAdapter;
import com.atomeng.erp.qar.application.port.QarEvidencePort;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.RptFlow;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 05 FLD 현장조사 서버 측 통합 테스트 — 앱(Flutter)이 쓸 동기화 프로토콜을 HTTP 로 그대로 재현한다.
 * TC-FLD-002(push 재전송 중복 0) · 003(두 기기 같은 결함 폭 충돌 → 해결 API) · 004(다른 필드 자동 병합 · LWW) ·
 * 005(사진 이어 올리기 60% 끊김 → HEAD offset 재개) · 006(1바이트 변조 → REJECTED → 재업로드 지시) · 011(구역 잠금 422 + 목록) ·
 * 013(프로젝션 재구축 해시 동일) · 014(교정 만료 장비 → 잠정 · 확정 차단) · 015(시계 드리프트 경고) +
 * 이벤트 fld.defect.recorded(FAC) · fld.survey.completed(RPT · CST, 1회) · fld.ai-suggestion.discrepancy(QAR) · RPT 스냅샷 fld::api 원천 ·
 * QAR AC-PHO 원천. 모든 데이터는 합성값.
 */
class FldIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    OutboxRelay relay;
    @Autowired
    FldEvidenceAdapter qarEvidence;   // 시험 컨텍스트의 @Primary 대역(FakeQarEvidence) 대신 실제 FLD 연결 어댑터
    @Autowired
    com.atomeng.erp.support.TestBeans.FakeAipWorker aipWorker;

    RptFlow flow;
    UUID admin;

    @BeforeEach
    void setUp() {
        clock.reset();
        admin = actor();
        flow = new RptFlow(mvc, jdbc, objectMapper, relay);
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    // ================================================================== 합성 기준 데이터

    record Fx(UUID project, UUID building, UUID pm, UUID eng, UUID floor, UUID column, UUID round, UUID zone) {}

    private UUID employee() {
        return flow.employee("FLD");
    }

    /** 프로젝트(PM · ENG 투입) + 건물 + 위치 노드(1F · 기둥 C1) + 조사 회차 + 구역(ENG 배정) */
    private Fx fixture() throws Exception {
        UUID pm = employee();
        UUID eng = employee();
        UUID project = flow.project(pm);
        flow.member(project, eng, "PART");
        UUID building = building("합성 FLD 본관");
        UUID floor = node(building, null, "FLOOR", null, "1F");
        UUID column = node(building, floor, "MEMBER", "COL", "1F-C1");
        JsonNode round = call(post("/api/v1/fld/rounds"), pm, "PM", """
                {"projectId":"%s","buildingId":"%s","surveyType":"PRECISE_INSPECTION"}""".formatted(project, building))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString().transform(this::tree);
        UUID roundId = UUID.fromString(round.get("id").asText());
        UUID zone = zone(roundId, pm, eng, "1F-A", floor);
        return new Fx(project, building, pm, eng, floor, column, roundId, zone);
    }

    private UUID zone(UUID round, UUID pm, UUID assignee, String name, UUID floor) throws Exception {
        JsonNode z = tree(call(post("/api/v1/fld/rounds/" + round + "/zones"), pm, "PM", """
                {"zoneName":"%s","floorNodeId":%s,"assigneeId":"%s"}""".formatted(name,
                floor == null ? "null" : "\"" + floor + "\"", assignee)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        return UUID.fromString(z.get("id").asText());
    }

    private UUID building(String name) {
        UUID site = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        jdbc.update("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES (?, '합성 FLD 단지', ?, ?)", site, by, by);
        jdbc.update("INSERT INTO fac.building(id, site_id, name, status, data_source, created_by, updated_by) "
                + "VALUES (?, ?, ?, 'ACTIVE', 'MANUAL', ?, ?)", b, site, name, by, by);
        return b;
    }

    private UUID node(UUID building, UUID parent, String type, String memberType, String code) {
        UUID id = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        String path = parent == null ? "n" + SEQ.incrementAndGet()
                : jdbc.queryForObject("SELECT path::text FROM fac.location_node WHERE id = ?", String.class, parent)
                        + ".n" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO fac.location_node(id, building_id, parent_id, node_type, member_type, code, name, path,
                                              created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::public.ltree, ?, ?)""", id, building, parent, type, memberType, code,
                "합성 " + code, path, by, by);
        return id;
    }

    /** 검교정 PASS 장비(만료일 = today + daysLeft, 음수면 이미 만료) */
    private UUID equipment(int daysLeft) throws Exception {
        JsonNode eq = flow.call(RptFlow.postJson("/api/v1/eqp/equipment", ("{\"typeCode\":\"MSR-CRK\",\"serialNo\":\"FLD-%d\","
                + "\"model\":\"합성 균열폭 측정기\",\"homeLocation\":\"합성 장비실\"}").formatted(SEQ.incrementAndGet())),
                admin, "ADMIN", 201);
        UUID id = UUID.fromString(eq.get("id").asText());
        flow.call(RptFlow.postJson("/api/v1/eqp/equipment/" + id + "/calibrations",
                ("{\"calibratedOn\":\"%s\",\"nextDueOn\":\"%s\",\"result\":\"PASS\",\"certNo\":\"KC-FLD-%d\","
                        + "\"labName\":\"합성교정기관\",\"labAccreditationNo\":\"KC-000\"}").formatted(today().minusDays(400),
                        today().plusDays(daysLeft), SEQ.incrementAndGet())), admin, "ADMIN", 201);
        return id;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private JsonNode tree(String s) {
        try {
            return objectMapper.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, UUID who,
                               String role, String body) throws Exception {
        if (body != null) {
            b = b.contentType("application/json").content(body);
        }
        return mvc.perform(as(b, who, role));
    }

    private void drain() {
        for (int i = 0; i < 30 && relay.pollOnce().published() > 0; i++) {
            // 연쇄 이벤트까지 배달
        }
    }

    private int outbox(String type, UUID aggregate) {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, type, aggregate);
    }

    private JsonNode lastOutbox(String type, UUID aggregate) {
        return tree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event WHERE event_type = ? "
                + "AND aggregate_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1", String.class, type, aggregate));
    }

    // ------------------------------------------------------------------ 동기화 프로토콜 도우미

    /** HLC = 지금 + offsetMs (기기 node) */
    private String hlc(long offsetMs, int counter, String node) {
        return new Hlc(clock.instant().toEpochMilli() + offsetMs, counter, node).pack();
    }

    private String nowKst() {
        return OffsetDateTime.ofInstant(clock.instant(), SEOUL).toString();
    }

    private static String op(UUID opId, UUID defect, String type, String hlc, String payload) {
        return op(opId, defect, type, hlc, payload, null, null);
    }

    private static String op(UUID opId, UUID defect, String type, String hlc, String payload, Long baseSeq,
                             String lockReason) {
        return ("{\"opId\":\"%s\",\"defectId\":\"%s\",\"eventType\":\"%s\",\"hlc\":\"%s\",\"payload\":%s%s%s}")
                .formatted(opId, defect, type, hlc, payload, baseSeq == null ? "" : ",\"baseSeq\":" + baseSeq,
                        lockReason == null ? "" : ",\"lockOverrideReason\":\"" + lockReason + "\"");
    }

    private String created(UUID zone, String type, UUID node) {
        return """
                {"zoneId":"%s","typeCode":"%s","subtypeCode":"FLEXURE","sheetVersion":3,
                 "geomNorm":{"type":"LineString","coords":[[0.412,0.338],[0.455,0.341]]}%s}""".formatted(zone, type,
                node == null ? "" : ",\"locationNodeId\":\"" + node + "\"");
    }

    private String measured(String item, String value, String method, UUID equipment) {
        return """
                {"item":"%s","value":%s,"unit":"%s","method":"%s"%s,"measuredAt":"%s"}""".formatted(item, value,
                item.endsWith("_mm") ? "mm" : "m", method, equipment == null ? "" : ",\"equipmentId\":\"" + equipment + "\"",
                nowKst());
    }

    private JsonNode push(UUID who, String role, UUID device, UUID round, long since, String... ops) throws Exception {
        String body = "{\"deviceId\":\"%s\",\"roundId\":\"%s\",\"clientLastPulledSeq\":%d,\"ops\":[%s]}"
                .formatted(device, round, since, String.join(",", ops));
        return tree(call(post("/api/v1/fld/sync/push"), who, role, body).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    /** 가시성 지연 창(2초) 뒤 pull */
    private JsonNode pull(UUID who, String role, UUID round, UUID device, String tokenOrNull, Long since) throws Exception {
        clock.advance(Duration.ofSeconds(3));
        String q = "/api/v1/fld/sync/pull?roundId=" + round + "&deviceId=" + device
                + (tokenOrNull != null ? "&changeToken=" + tokenOrNull : "") + (since != null ? "&sinceSeq=" + since : "");
        return tree(call(get(q), who, role, null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    private JsonNode defect(UUID who, String role, UUID id) throws Exception {
        return tree(call(get("/api/v1/fld/defects/" + id), who, role, null).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString());
    }

    /** 균열(CRK) 결함 생성 + 폭 · 길이(육안) → RECORDED */
    private UUID recordedCrack(Fx f, UUID device, UUID who, String role, UUID node) throws Exception {
        UUID d = UUID.randomUUID();
        JsonNode r = push(who, role, device, f.round(), 0,
                op(UUID.randomUUID(), d, "DefectCreated", hlc(0, 0, "dv"), created(f.zone(), "CRK", node)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(1, 0, "dv"), measured("width_mm", "0.30", "VISUAL", null)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(2, 0, "dv"), measured("length_m", "2.45", "VISUAL", null)));
        assertThat(r.findValuesAsText("status")).containsOnly("ACCEPTED");
        return d;
    }

    // ================================================================== TC-FLD-002 · 013 · FR-FLD-020 · FAC 구독

    @Test
    @DisplayName("TC-FLD-002: 같은 push 배치 3회 재전송 → 이벤트 중복 0, 2·3회차 DUPLICATE(같은 seq) · 배치 안 중복도 DUPLICATE")
    void duplicatePushReplay() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID d = UUID.randomUUID();
        UUID equipment = equipment(200);
        String[] ops = {
                op(UUID.randomUUID(), d, "DefectCreated", hlc(0, 0, "d7f3"), created(f.zone(), "CRK", f.column())),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(10, 0, "d7f3"),
                        measured("width_mm", "0.30", "INSTRUMENT", equipment)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(20, 0, "d7f3"), measured("length_m", "2.45", "VISUAL", null))};
        JsonNode first = push(f.eng(), "ENG", device, f.round(), 0, ops);
        assertThat(first.findValuesAsText("status")).containsExactly("ACCEPTED", "ACCEPTED", "ACCEPTED");
        assertThat(first.at("/results/0/code").asText()).isEqualTo("CRK-0001");
        assertThat(first.at("/results/2/defectStatus").asText()).isEqualTo("RECORDED");
        List<Long> seqs = first.findValues("seq").stream().map(JsonNode::asLong).toList();
        for (int i = 0; i < 2; i++) {   // 응답 직전 연결 끊김 → 같은 배치 재전송
            JsonNode again = push(f.eng(), "ENG", device, f.round(), 0, ops);
            assertThat(again.findValuesAsText("status")).containsExactly("DUPLICATE", "DUPLICATE", "DUPLICATE");
            assertThat(again.findValues("seq").stream().map(JsonNode::asLong).toList()).isEqualTo(seqs);
            assertThat(again.at("/results/0/replayed").asBoolean()).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.defect_event WHERE defect_id = ?", Integer.class, d))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.measurement WHERE defect_id = ?", Integer.class, d))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.sync_batch WHERE round_id = ?", Integer.class, f.round()))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT sum(duplicates) FROM fld.sync_batch WHERE round_id = ?", Integer.class,
                f.round())).isEqualTo(6);
        // 같은 배치 안 같은 opId 두 번 → 두 번째는 DUPLICATE
        UUID dupOp = UUID.randomUUID();
        String memo = op(dupOp, d, "DefectAttributesSet", hlc(30, 0, "d7f3"), "{\"fields\":{\"memo\":\"합성 메모\"}}");
        JsonNode inBatch = push(f.eng(), "ENG", device, f.round(), 0, memo, memo);
        assertThat(inBatch.findValuesAsText("status")).containsExactly("ACCEPTED", "DUPLICATE");
        // 측정 장비 추적(eqp::api MeasurementTracePort) — 확정 측정 1건 = 추적 1행
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.measurement_trace WHERE equipment_id = ?", Integer.class,
                equipment)).isEqualTo(1);

        // FAC: fld.defect.recorded(위치 노드) → fac.node_defect_ref 읽기 모델 (실제 이벤트 흐름)
        assertThat(outbox("fld.defect.recorded", d)).isEqualTo(1);
        assertThat(lastOutbox("fld.defect.recorded", d).get("locationNodeId").asText()).isEqualTo(f.column().toString());
        assertThat(outbox("fld.defect.changed", f.round())).isGreaterThanOrEqualTo(2);
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.node_defect_ref WHERE node_id = ? AND defect_id = ?",
                Integer.class, f.column(), d)).isEqualTo(1);

        // TC-FLD-013: 프로젝션 삭제 → 이벤트에서 재구축 → 덤프 해시 동일
        JsonNode rebuilt = tree(call(post("/api/v1/fld/rounds/" + f.round() + "/projection:rebuild"), admin, "SYSADMIN",
                null).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(rebuilt.get("deleted").asInt()).isEqualTo(1);
        assertThat(rebuilt.get("hashAfter").asText()).isEqualTo(rebuilt.get("hashBefore").asText());
        call(post("/api/v1/fld/rounds/" + f.round() + "/projection:rebuild"), f.pm(), "PM", null)
                .andExpect(status().isForbidden());

        // FR-FLD-020: 이력 + 특정 시점 상태 재현(첫 배치 직후 = 메모 없음)
        String asOf = jdbc.queryForObject("SELECT max(received_at) FROM fld.defect_event WHERE op_id IN (?, ?, ?)",
                java.sql.Timestamp.class, opIds(ops)).toInstant().toString();
        clock.advance(Duration.ofSeconds(5));
        push(f.eng(), "ENG", device, f.round(), 0, op(UUID.randomUUID(), d, "DefectAttributesSet", hlc(40, 0, "d7f3"),
                "{\"fields\":{\"memo\":\"나중 메모\"}}"));
        call(get("/api/v1/fld/defects/" + d + "/history?asOf=" + asOf), f.pm(), "PM", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.state.status").value("RECORDED"))
                .andExpect(jsonPath("$.state.attrs.memo").doesNotExist());
        call(get("/api/v1/fld/defects/" + d + "/history"), f.pm(), "PM", null)
                .andExpect(jsonPath("$.state.attrs.memo").value("나중 메모"));
    }

    private static Object[] opIds(String[] ops) {
        return Arrays.stream(ops).map(o -> UUID.fromString(o.substring(9, 45))).toArray();
    }

    // ================================================================== TC-FLD-003 · 충돌 해결 API

    @Test
    @DisplayName("TC-FLD-003: 기기 A·B 가 같은 균열 폭을 오프라인에서 0.25/0.30 → 후착 push CONFLICT(재전송도 같은 충돌) → 잠금 불가 → 해결 후 단일 값")
    void twoDevicesConflict() throws Exception {
        Fx f = fixture();
        UUID devA = UUID.randomUUID();
        UUID devB = UUID.randomUUID();
        UUID equipment = equipment(200);
        UUID d = recordedCrack(f, devA, f.eng(), "ENG", f.column());
        // 두 기기 모두 pull 해서 같은 기준(seq S)을 가짐
        long s = pull(f.pm(), "PM", f.round(), devB, null, 0L).get("lastSeq").asLong();
        assertThat(s).isPositive();
        // 비행기 모드: A=0.25(먼저 동기화), B=0.30(나중 동기화, 기준 S)
        UUID opA = UUID.randomUUID();
        UUID opB = UUID.randomUUID();
        JsonNode a = push(f.eng(), "ENG", devA, f.round(), s,
                op(opA, d, "DefectMeasured", hlc(100, 0, "a"), measured("width_mm", "0.25", "INSTRUMENT", equipment)));
        assertThat(a.at("/results/0/status").asText()).isEqualTo("ACCEPTED");
        String opBJson = op(opB, d, "DefectMeasured", hlc(200, 0, "b"), measured("width_mm", "0.30", "INSTRUMENT", equipment));
        JsonNode b = push(f.pm(), "PM", devB, f.round(), s, opBJson);
        JsonNode r = b.at("/results/0");
        assertThat(r.get("status").asText()).isEqualTo("CONFLICT");
        assertThat(r.get("field").asText()).isEqualTo("attrs.width_mm");
        assertThat(r.get("strategy").asText()).isEqualTo("MANUAL");
        assertThat(r.get("serverValue").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(r.get("localValue").decimalValue()).isEqualByComparingTo("0.30");
        UUID conflictId = UUID.fromString(r.get("conflictId").asText());
        // 충돌 op 는 이벤트로 저장하지 않음(원 값은 sync_conflict.local_payload 에 보존), 재전송은 같은 충돌
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.defect_event WHERE op_id = ?", Integer.class, opB)).isZero();
        JsonNode replay = push(f.pm(), "PM", devB, f.round(), s, opBJson);
        assertThat(replay.at("/results/0/status").asText()).isEqualTo("CONFLICT");
        assertThat(replay.at("/results/0/conflictId").asText()).isEqualTo(conflictId.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.sync_conflict WHERE defect_id = ?", Integer.class, d))
                .isEqualTo(1);
        assertThat(defect(f.pm(), "PM", d).at("/attrs/width_mm").decimalValue()).isEqualByComparingTo("0.25");
        // 충돌 UI 원천: pull 에 미해결 충돌 · 목록 API
        assertThat(pull(f.eng(), "ENG", f.round(), devA, null, s).get("conflicts").findValuesAsText("id"))
                .contains(conflictId.toString());
        call(get("/api/v1/fld/sync/conflicts?roundId=" + f.round()), f.eng(), "ENG", null).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conflictId.toString()))
                .andExpect(jsonPath("$[0].localPayload.value").value(0.30));
        // FR-FLD-016: 미해결 충돌이 있으면 구역 잠금 불가
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("FLD-422-002"))
                .andExpect(jsonPath("$.issues[0].kind").value("OPEN_CONFLICT"));
        // ENG 는 본인 충돌만(충돌 작성자 = PM 기기 B) → 403
        call(post("/api/v1/fld/sync/conflicts/" + conflictId + "/resolve"), f.eng(), "ENG", """
                {"resolution":"KEEP_SERVER"}""").andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FLD-403-001"));
        // PM: 내 값(0.30) 유지 → 새 op 로 저장, HLC 는 서버 값보다 늦게 보정 → 단일 값 0.30
        UUID resolveOp = UUID.randomUUID();
        String resolveBody = """
                {"resolution":"KEEP_CLIENT","opId":"%s","deviceId":"%s","hlc":"%s"}""".formatted(resolveOp, devB,
                hlc(-60_000, 0, "b"));
        call(post("/api/v1/fld/sync/conflicts/" + conflictId + "/resolve"), f.pm(), "PM", resolveBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("KEEP_CLIENT"));
        assertThat(defect(f.pm(), "PM", d).at("/attrs/width_mm").decimalValue()).isEqualByComparingTo("0.30");
        call(post("/api/v1/fld/sync/conflicts/" + conflictId + "/resolve"), f.pm(), "PM", resolveBody)
                .andExpect(status().isOk()).andExpect(jsonPath("$.replayed").value(true));
        call(post("/api/v1/fld/sync/conflicts/" + conflictId + "/resolve"), f.pm(), "PM", """
                {"resolution":"KEEP_SERVER","opId":"%s"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FLD-409-006"));
        // 해결 이벤트는 기기 A 의 pull 로 내려간다(A 화면도 단일 값)
        JsonNode pa = pull(f.eng(), "ENG", f.round(), devA, null, s);
        assertThat(pa.get("events").findValuesAsText("opId")).contains(resolveOp.toString());
        assertThat(jdbc.queryForObject("SELECT payload->>'resolvesConflict' FROM fld.defect_event WHERE op_id = ?",
                String.class, resolveOp)).isEqualTo(conflictId.toString());

        // MERGE: 길이 충돌 → 두 값 사이 재측정값(2.40)으로 병합
        long s2 = pa.get("lastSeq").asLong();
        push(f.eng(), "ENG", devA, f.round(), s2, op(UUID.randomUUID(), d, "DefectMeasured", hlc(300, 0, "a"),
                measured("length_m", "2.30", "VISUAL", null)));
        JsonNode lenConflict = push(f.pm(), "PM", devB, f.round(), s2, op(UUID.randomUUID(), d, "DefectMeasured",
                hlc(400, 0, "b"), measured("length_m", "2.50", "VISUAL", null)));
        UUID c2 = UUID.fromString(lenConflict.at("/results/0/conflictId").asText());
        call(post("/api/v1/fld/sync/conflicts/" + c2 + "/resolve"), f.pm(), "PM", """
                {"resolution":"MERGE","opId":"%s","deviceId":"%s","merged":{"value":2.40}}""".formatted(UUID.randomUUID(),
                devB)).andExpect(status().isOk()).andExpect(jsonPath("$.resolvedValue").value(2.40));
        assertThat(defect(f.pm(), "PM", d).at("/attrs/length_m").decimalValue()).isEqualByComparingTo("2.40");

        // 기하 동시 변경(5분 이내) → GEOM_5MIN 충돌 → 서버 값 유지(KEEP_SERVER, 이벤트 없음)
        long s3 = pull(f.pm(), "PM", f.round(), devB, null, null).get("lastSeq").asLong();
        push(f.eng(), "ENG", devA, f.round(), s3, op(UUID.randomUUID(), d, "DefectGeometryChanged", hlc(500, 0, "a"),
                "{\"geomNorm\":{\"type\":\"LineString\",\"coords\":[[0.40,0.33],[0.46,0.35]]}}"));
        JsonNode geo = push(f.pm(), "PM", devB, f.round(), s3, op(UUID.randomUUID(), d, "DefectGeometryChanged",
                hlc(600, 0, "b"), "{\"geomNorm\":{\"type\":\"LineString\",\"coords\":[[0.41,0.30],[0.47,0.36]]}}"));
        assertThat(geo.at("/results/0/strategy").asText()).isEqualTo("GEOM_5MIN");
        UUID c3 = UUID.fromString(geo.at("/results/0/conflictId").asText());
        call(post("/api/v1/fld/sync/conflicts/" + c3 + "/resolve"), f.pm(), "PM", "{\"resolution\":\"KEEP_SERVER\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.resolutionSeq").doesNotExist());
        assertThat(defect(f.pm(), "PM", d).at("/geomNorm/coords/0/0").asDouble()).isEqualTo(0.40);
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
    }

    // ================================================================== TC-FLD-004 · LWW

    @Test
    @DisplayName("TC-FLD-004: A 는 메모, B 는 방향을 동시 수정 → 자동 병합(충돌 0) · 같은 일반 필드는 HLC 늦은 값 승(도착 순서 무관)")
    void autoMergeDifferentFields() throws Exception {
        Fx f = fixture();
        UUID devA = UUID.randomUUID();
        UUID devB = UUID.randomUUID();
        UUID d = recordedCrack(f, devA, f.eng(), "ENG", null);
        long s = pull(f.pm(), "PM", f.round(), devB, null, 0L).get("lastSeq").asLong();
        JsonNode a = push(f.eng(), "ENG", devA, f.round(), s, op(UUID.randomUUID(), d, "DefectAttributesSet",
                hlc(100, 0, "a"), "{\"fields\":{\"memo\":\"하부 백태 동반\"}}"));
        JsonNode b = push(f.pm(), "PM", devB, f.round(), s, op(UUID.randomUUID(), d, "DefectAttributesSet",
                hlc(90, 0, "b"), "{\"fields\":{\"direction\":\"VERTICAL\"}}"));
        assertThat(a.at("/results/0/status").asText()).isEqualTo("ACCEPTED");
        assertThat(b.at("/results/0/status").asText()).isEqualTo("ACCEPTED");
        JsonNode cur = defect(f.pm(), "PM", d);
        assertThat(cur.at("/attrs/memo").asText()).isEqualTo("하부 백태 동반");
        assertThat(cur.at("/attrs/direction").asText()).isEqualTo("VERTICAL");
        assertThat(cur.get("versionVector").has(devA.toString())).isTrue();
        assertThat(cur.get("versionVector").has(devB.toString())).isTrue();
        // 같은 메모 필드: 늦은 HLC(A, +500) 가 먼저 도착, 이른 HLC(B, +200)가 나중 도착 → A 유지(충돌 아님, LWW)
        push(f.eng(), "ENG", devA, f.round(), s, op(UUID.randomUUID(), d, "DefectAttributesSet", hlc(500, 0, "a"),
                "{\"fields\":{\"memo\":\"A 최종 메모\"}}"));
        JsonNode late = push(f.pm(), "PM", devB, f.round(), s, op(UUID.randomUUID(), d, "DefectAttributesSet",
                hlc(200, 0, "b"), "{\"fields\":{\"memo\":\"B 옛 메모\"}}"));
        assertThat(late.at("/results/0/status").asText()).isEqualTo("ACCEPTED");
        assertThat(defect(f.pm(), "PM", d).at("/attrs/memo").asText()).isEqualTo("A 최종 메모");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.sync_conflict WHERE defect_id = ?", Integer.class, d))
                .isZero();
        // 측정 항목을 일반 속성으로 우회 기록 → 거부
        JsonNode bypass = push(f.pm(), "PM", devB, f.round(), s, op(UUID.randomUUID(), d, "DefectAttributesSet",
                hlc(600, 0, "b"), "{\"fields\":{\"width_mm\":9.9}}"));
        assertThat(bypass.at("/results/0/reason").asText()).isEqualTo("UNKNOWN_ATTRIBUTE");

        // pull change token: B 는 A 이벤트만 받고(자기 기기 제외), 토큰으로 이어 받으면 새 것만
        JsonNode p1 = pull(f.pm(), "PM", f.round(), devB, null, 0L);
        assertThat(p1.get("events").findValuesAsText("deviceId")).containsOnly(devA.toString());
        String token = p1.get("changeToken").asText();
        assertThat(pull(f.pm(), "PM", f.round(), devB, token, null).get("events")).isEmpty();
        push(f.eng(), "ENG", devA, f.round(), 0, op(UUID.randomUUID(), d, "DefectAttributesSet", hlc(700, 0, "a"),
                "{\"fields\":{\"active\":true}}"));
        JsonNode p2 = pull(f.pm(), "PM", f.round(), devB, token, null);
        assertThat(p2.get("events")).hasSize(1);
        assertThat(p2.at("/events/0/payload/fields/active").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT last_pulled_seq FROM fld.device_cursor WHERE device_id = ? AND round_id = ?",
                Long.class, devB, f.round())).isEqualTo(p2.get("lastSeq").asLong());
        // 다른 회차 토큰은 거부
        Fx other = fixture();
        call(get("/api/v1/fld/sync/pull?roundId=" + other.round() + "&changeToken=" + token), other.pm(), "PM", null)
                .andExpect(status().isUnprocessableEntity());
        // 가시성 지연 창: 방금 기록된 이벤트는 창이 지나기 전 pull 에 안 보인다(커밋 지연 대응)
        push(f.eng(), "ENG", devA, f.round(), 0, op(UUID.randomUUID(), d, "DefectAttributesSet", hlc(800, 0, "a"),
                "{\"fields\":{\"severity\":\"HIGH\"}}"));
        call(get("/api/v1/fld/sync/pull?roundId=" + f.round() + "&deviceId=" + devB + "&changeToken="
                + p2.get("changeToken").asText()), f.pm(), "PM", null)
                .andExpect(jsonPath("$.events.length()").value(0)).andExpect(jsonPath("$.pendingVisibility").value(true));
    }

    // ================================================================== TC-FLD-014 · FR-FLD-010

    @Test
    @DisplayName("TC-FLD-014: 교정 만료 장비 측정 → 서버 재검증 실패 · 잠정(provisional) 유지 · 확정 차단(DRAFT), 장비 미기재 · 미등록 장비 거부(배치는 계속)")
    void calibrationInvalidBlocksMeasurement() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID expired = equipment(-10);
        UUID valid = equipment(100);
        UUID d = UUID.randomUUID();
        JsonNode r = push(f.eng(), "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), d, "DefectCreated", hlc(0, 0, "e"), created(f.zone(), "CRK", f.column())),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(1, 0, "e"), measured("length_m", "1.10", "VISUAL", null)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(2, 0, "e"),
                        measured("width_mm", "0.35", "INSTRUMENT", expired)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(3, 0, "e"), measured("width_mm", "0.35", "GAUGE", null)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(4, 0, "e"),
                        measured("width_mm", "0.35", "INSTRUMENT", UUID.randomUUID())),
                op(UUID.randomUUID(), d, "DefectAttributesSet", hlc(5, 0, "e"), "{\"fields\":{\"memo\":\"잠정 측정\"}}"));
        assertThat(r.findValuesAsText("status")).containsExactly("ACCEPTED", "ACCEPTED", "ACCEPTED", "REJECTED",
                "REJECTED", "ACCEPTED");
        JsonNode prov = r.at("/results/2");
        assertThat(prov.get("provisional").asBoolean()).isTrue();
        assertThat(prov.get("blockedReason").asText()).isEqualTo("EQP_CAL_EXPIRED");
        assertThat(prov.get("calibration").asText()).isEqualTo("INVALID");
        assertThat(r.at("/results/3/reason").asText()).isEqualTo("EQUIPMENT_REQUIRED");
        assertThat(r.at("/results/4/reason").asText()).isEqualTo("UNKNOWN_EQUIPMENT");
        JsonNode cur = defect(f.eng(), "ENG", d);
        assertThat(cur.get("status").asText()).isEqualTo("DRAFT");   // 폭 미확정 → 필수값 누락
        assertThat(cur.at("/attrs/width_mm").isMissingNode()).isTrue();
        assertThat(cur.at("/provisionalMeasurements/0/reason").asText()).isEqualTo("EQP_CAL_EXPIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM eqp.measurement_trace WHERE equipment_id = ?", Integer.class,
                expired)).isZero();
        assertThat(outbox("fld.defect.recorded", d)).isZero();
        // 잠금 검증: 필수값 누락으로 거부(TC-FLD-011 형식)
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].kind").value("REQUIRED_MISSING"))
                .andExpect(jsonPath("$.issues[0].defectCode").value("CRK-0001"));
        // 유효 장비로 재측정 → VALID · 추적 행 · RECORDED · fld.defect.recorded
        JsonNode ok = push(f.eng(), "ENG", device, f.round(), 0, op(UUID.randomUUID(), d, "DefectMeasured",
                hlc(10, 0, "e"), measured("width_mm", "0.35", "INSTRUMENT", valid)));
        assertThat(ok.at("/results/0/calibration").asText()).isEqualTo("VALID");
        assertThat(ok.at("/results/0/defectStatus").asText()).isEqualTo("RECORDED");
        assertThat(jdbc.queryForObject("SELECT basis FROM eqp.measurement_trace WHERE equipment_id = ?", String.class,
                valid)).isEqualTo("CALIBRATION");
        assertThat(outbox("fld.defect.recorded", d)).isEqualTo(1);
    }

    // ================================================================== TC-FLD-005 · 006 사진

    private static byte[] jpeg(int seed) throws Exception {
        BufferedImage img = new BufferedImage(360, 360, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int y = 0; y < 360; y++) {
            for (int x = 0; x < 360; x++) {
                img.setRGB(x, y, new Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)).getRGB());
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static String sha(byte[] b) {
        return HashChain.sha256(new ByteArrayInputStream(b));
    }

    private JsonNode createUpload(Fx f, UUID who, UUID photo, UUID defect, UUID device, byte[] data, String claimedSha,
                                  int expect) throws Exception {
        return tree(call(post("/api/v1/fld/uploads"), who, "ENG", """
                {"photoId":"%s","roundId":"%s","defectId":%s,"deviceId":"%s","sha256":"%s","sizeBytes":%d,
                 "kind":"NEAR","takenAt":"%s","exif":{"Model":"합성 태블릿"}}""".formatted(photo, f.round(),
                defect == null ? "null" : "\"" + defect + "\"", device, claimedSha, data.length,
                clock.instant().toString())).andExpect(status().is(expect))
                .andExpect(header().string("Tus-Resumable", "1.0.0")).andReturn().getResponse().getContentAsString());
    }

    private ResultActions patchChunk(UUID who, UUID upload, long offset, byte[] chunk) throws Exception {
        return mvc.perform(as(patch("/api/v1/fld/uploads/" + upload).contentType("application/offset+octet-stream")
                .header("Upload-Offset", offset).content(chunk), who, "ENG"));
    }

    private long headOffset(UUID who, UUID upload) throws Exception {
        return Long.parseLong(mvc.perform(as(head("/api/v1/fld/uploads/" + upload), who, "ENG"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Upload-Offset"));
    }

    @Test
    @DisplayName("TC-FLD-005: 업로드 60% 지점 끊김 → 재시작 시 같은 세션 · HEAD offset 기준 재개 → 전송 바이트 합 = 파일 크기, 이미 받은 청크 재전송은 409")
    void resumableUpload() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID d = recordedCrack(f, device, f.eng(), "ENG", f.column());
        byte[] data = jpeg(1);
        assertThat(data.length).isGreaterThan(50_000);
        UUID photo = UUID.randomUUID();
        // 오프라인 기록: PhotoAttached op (기기 해시) → 사진 PENDING
        push(f.eng(), "ENG", device, f.round(), 0, op(UUID.randomUUID(), d, "PhotoAttached", hlc(50, 0, "u"),
                "{\"photoId\":\"%s\",\"sha256\":\"%s\",\"kind\":\"NEAR\",\"sizeBytes\":%d}".formatted(photo, sha(data),
                        data.length)));
        JsonNode created = createUpload(f, f.eng(), photo, d, device, data, sha(data), 201);
        UUID upload = UUID.fromString(created.get("uploadId").asText());
        int chunk = 16 * 1024;
        long sent = 0;
        long offset = 0;
        while (offset < data.length * 0.6) {
            byte[] part = Arrays.copyOfRange(data, (int) offset, (int) Math.min(data.length, offset + chunk));
            patchChunk(f.eng(), upload, offset, part).andExpect(status().isOk());
            sent += part.length;
            offset += part.length;
        }
        // 네트워크 끊김 → 앱 재시작: 같은 사진으로 생성 요청 → 열린 세션 그대로(200)
        JsonNode again = createUpload(f, f.eng(), photo, d, device, data, sha(data), 200);
        assertThat(again.get("uploadId").asText()).isEqualTo(upload.toString());
        long resumeAt = headOffset(f.eng(), upload);
        assertThat(resumeAt).isEqualTo(offset);
        // 잘못된(옛) offset 으로 보낸 청크는 409 + 현재 offset(중복 수신 0)
        patchChunk(f.eng(), upload, resumeAt - chunk, Arrays.copyOfRange(data, (int) resumeAt - chunk, (int) resumeAt))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FLD-409-002"))
                .andExpect(jsonPath("$.currentOffset").value(resumeAt));
        // 청크 체크섬(tus checksum 확장) 불일치 → 422, 수신 안 됨
        byte[] next = Arrays.copyOfRange(data, (int) resumeAt, (int) Math.min(data.length, resumeAt + chunk));
        String badSum = "sha256 " + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha(new byte[] {1})));
        mvc.perform(as(patch("/api/v1/fld/uploads/" + upload).contentType("application/offset+octet-stream")
                        .header("Upload-Offset", resumeAt).header("Upload-Checksum", badSum).content(next), f.eng(), "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FLD-422-003"));
        offset = resumeAt;
        JsonNode last = null;
        while (offset < data.length) {
            byte[] part = Arrays.copyOfRange(data, (int) offset, (int) Math.min(data.length, offset + chunk));
            String sum = "sha256 " + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha(part)));
            last = tree(mvc.perform(as(patch("/api/v1/fld/uploads/" + upload).contentType("application/offset+octet-stream")
                    .header("Upload-Offset", offset).header("Upload-Checksum", sum).content(part), f.eng(), "ENG"))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            sent += part.length;
            offset += part.length;
        }
        assertThat(sent).isEqualTo(data.length);   // 끊김 전후 합 = 파일 크기(재전송 0)
        assertThat(last.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(last.get("photoState").asText()).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT sum(length) FROM fld.upload_chunk WHERE session_id = ?", Long.class, upload))
                .isEqualTo(data.length);
        // core 첨부(WORM) · 회차 해시 체인 · fld.photo.verified
        var p = jdbc.queryForMap("SELECT attachment_id, server_sha256, chain_hash FROM fld.photo WHERE id = ?", photo);
        assertThat(((String) p.get("server_sha256")).trim()).isEqualTo(sha(data));
        assertThat(jdbc.queryForObject("SELECT chain_scope FROM core.attachment WHERE id = ?", UUID.class,
                p.get("attachment_id"))).isEqualTo(f.round());
        assertThat(outbox("fld.photo.verified", photo)).isEqualTo(1);
        assertThat(lastOutbox("fld.photo.verified", photo).get("defectId").asText()).isEqualTo(d.toString());
        assertThat(defect(f.eng(), "ENG", d).get("photos").findValuesAsText("")).isNotNull();
        assertThat(defect(f.eng(), "ENG", d).get("photos").toString()).contains(photo.toString());
        // 검증된 사진 재업로드 요청 → 409(중복 업로드 불필요)
        call(post("/api/v1/fld/uploads"), f.eng(), "ENG", """
                {"photoId":"%s","roundId":"%s","sha256":"%s","sizeBytes":%d}""".formatted(photo, f.round(), sha(data),
                data.length)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FLD-409-005"));
        // 완료된 세션에 PATCH → 409
        patchChunk(f.eng(), upload, data.length, new byte[] {1}).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLD-409-003"));
    }

    @Test
    @DisplayName("TC-FLD-006: 업로드 원본 1바이트 변조(중간자) → REJECTED · pull 재업로드 지시 → 재업로드 VERIFIED, 이후 WORM 원본 변조는 verify MISMATCH")
    void tamperedPhotoRejected() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID d = recordedCrack(f, device, f.eng(), "ENG", f.column());
        byte[] data = jpeg(2);
        byte[] tampered = data.clone();
        tampered[tampered.length / 2] ^= 0x01;
        UUID photo = UUID.randomUUID();
        UUID upload = UUID.fromString(createUpload(f, f.eng(), photo, d, device, data, sha(data), 201)
                .get("uploadId").asText());
        int half = tampered.length / 2 + 10;
        patchChunk(f.eng(), upload, 0, Arrays.copyOfRange(tampered, 0, half)).andExpect(status().isOk());
        patchChunk(f.eng(), upload, half, Arrays.copyOfRange(tampered, half, tampered.length))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FLD-409-004"))
                .andExpect(jsonPath("$.reupload").value(true))
                .andExpect(jsonPath("$.expectedSha256").value(sha(data)));
        assertThat(jdbc.queryForObject("SELECT upload_state FROM fld.photo WHERE id = ?", String.class, photo))
                .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.attachment WHERE owner_id = ?", Integer.class, photo))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.upload_chunk c JOIN fld.upload_session s "
                + "ON s.id = c.session_id WHERE s.photo_id = ?", Integer.class, photo)).isEqualTo(2);
        JsonNode pulled = pull(f.eng(), "ENG", f.round(), device, null, 0L);
        assertThat(pulled.get("reuploadRequests").findValuesAsText("photoId")).containsExactly(photo.toString());
        assertThat(pulled.at("/reuploadRequests/0/reason").asText()).isEqualTo("HASH_MISMATCH");
        // 잠금 검증: 거부된 사진이 있으면 재업로드 전 잠금 불가
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.issues[0].kind").value("PHOTO_REJECTED"));
        // 재업로드(새 세션, 원본 그대로) → VERIFIED, 재업로드 지시 사라짐
        UUID upload2 = UUID.fromString(createUpload(f, f.eng(), photo, d, device, data, sha(data), 201)
                .get("uploadId").asText());
        assertThat(upload2).isNotEqualTo(upload);
        patchChunk(f.eng(), upload2, 0, data).andExpect(status().isOk())
                .andExpect(jsonPath("$.photoState").value("VERIFIED"));
        assertThat(pull(f.eng(), "ENG", f.round(), device, null, 0L).get("reuploadRequests")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT reject_count FROM fld.photo WHERE id = ?", Integer.class, photo)).isEqualTo(1);
        // 두 번째 사진도 같은 회차 체인에 연결 → 체인 검증 2고리 valid
        byte[] data2 = jpeg(3);
        UUID photo2 = UUID.randomUUID();
        UUID up3 = UUID.fromString(createUpload(f, f.eng(), photo2, d, device, data2, sha(data2), 201)
                .get("uploadId").asText());
        patchChunk(f.eng(), up3, 0, data2).andExpect(status().isOk());
        call(get("/api/v1/fld/rounds/" + f.round() + "/evidence/verify"), f.pm(), "PM", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true)).andExpect(jsonPath("$.length").value(2));
        // 업로드 후 WORM 원본 1바이트 변조(로컬 WORM 모사 우회) → verify MISMATCH
        call(get("/api/v1/fld/photos/" + photo + "/verify"), f.pm(), "QA", null)
                .andExpect(jsonPath("$.result").value("MATCH"));
        var att = jdbc.queryForMap("SELECT a.bucket, a.object_key FROM fld.photo p JOIN core.attachment a "
                + "ON a.id = p.attachment_id WHERE p.id = ?", photo);
        Path original = EVIDENCE_ROOT.resolve((String) att.get("bucket")).resolve((String) att.get("object_key"));
        byte[] bytes = Files.readAllBytes(original);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(original, bytes);
        call(get("/api/v1/fld/photos/" + photo + "/verify"), f.pm(), "QA", null)
                .andExpect(jsonPath("$.result").value("MISMATCH"));
    }

    // ================================================================== TC-FLD-011 · BR-FLD-009 · fld.survey.completed

    @Test
    @DisplayName("TC-FLD-011 · BR-FLD-009 · fld.survey.completed: 잠금 검증 422 목록 → 잠금 · 잠긴 구역 수정(ENG 거부 · PM 사유) → 모든 구역 잠금 시 1회만 발행 → RPT · CST 구독, RPT 스냅샷 fld::api 원천, QAR 사진 원천")
    void zoneLockAndSurveyCompleted() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID zone2 = zone(f.round(), f.pm(), f.eng(), "1F-B", f.floor());
        UUID equipment = equipment(200);
        UUID d = UUID.randomUUID();
        UUID draft = UUID.randomUUID();
        push(f.eng(), "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), d, "DefectCreated", hlc(0, 0, "z"), created(f.zone(), "CRK", f.column())),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(1, 0, "z"), measured("width_mm", "0.30", "INSTRUMENT",
                        equipment)),
                op(UUID.randomUUID(), d, "DefectMeasured", hlc(2, 0, "z"), measured("length_m", "2.45", "VISUAL", null)),
                op(UUID.randomUUID(), draft, "DefectCreated", hlc(3, 0, "z"), created(f.zone(), "SPA", f.column())),
                op(UUID.randomUUID(), draft, "DefectCreated", hlc(4, 0, "z"), created(f.zone(), "SPA", f.column())));
        // TC-FLD-011: 필수값 누락 결함 → 422 + 목록, lock-check 로 미리 확인 가능
        call(get("/api/v1/fld/zones/" + f.zone() + "/lock-check"), f.pm(), "PM", null)
                .andExpect(jsonPath("$.ready").value(false)).andExpect(jsonPath("$.issues.length()").value(1));
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("FLD-422-002"))
                .andExpect(jsonPath("$.reason").value("FLD_ZONE_NOT_READY"))
                .andExpect(jsonPath("$.issues[0].kind").value("REQUIRED_MISSING"))
                .andExpect(jsonPath("$.issues[0].defectCode").value("SPA-0001"));
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.eng(), "ENG", null).andExpect(status().isForbidden());
        // ENG 는 본인 작성 24h 이내 삭제 가능(Tombstone, 사유 필수)
        JsonNode rm = push(f.eng(), "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), draft, "DefectRemoved", hlc(10, 0, "z"), "{\"reason\":\"\"}"),
                op(UUID.randomUUID(), draft, "DefectRemoved", hlc(11, 0, "z"), "{\"reason\":\"중복 기록\"}"));
        assertThat(rm.findValuesAsText("status")).containsExactly("REJECTED", "ACCEPTED");
        assertThat(rm.at("/results/1/defectStatus").asText()).isEqualTo("REMOVED");
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LOCKED"));
        assertThat(defect(f.pm(), "PM", d).get("status").asText()).isEqualTo("VERIFIED");
        assertThat(outbox("fld.zone.locked", f.zone())).isEqualTo(1);
        assertThat(outbox("fld.survey.completed", f.round())).isZero();   // 구역 2 미잠금
        // BR-FLD-009: 잠긴 구역 — ENG 수정 거부, PM 은 사유와 함께 after_lock
        String memo = "{\"fields\":{\"memo\":\"잠금 후 보완\"}}";
        assertThat(push(f.eng(), "ENG", device, f.round(), 0, op(UUID.randomUUID(), d, "DefectAttributesSet",
                hlc(20, 0, "z"), memo)).at("/results/0/reason").asText()).isEqualTo("ZONE_LOCKED");
        UUID pmDevice = UUID.randomUUID();
        assertThat(push(f.pm(), "PM", pmDevice, f.round(), 0, op(UUID.randomUUID(), d, "DefectAttributesSet",
                hlc(21, 0, "p"), memo)).at("/results/0/reason").asText()).isEqualTo("LOCK_REASON_REQUIRED");
        UUID afterOp = UUID.randomUUID();
        JsonNode after = push(f.pm(), "PM", pmDevice, f.round(), 0, op(afterOp, d, "DefectAttributesSet", hlc(22, 0, "p"),
                memo, null, "현장 재확인 결과 보완"));
        assertThat(after.at("/results/0/afterLock").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT after_lock FROM fld.defect_event WHERE op_id = ?", Boolean.class, afterOp))
                .isTrue();
        // 구역 2 (결함 없음) 잠금 → 회차 완료 · fld.survey.completed 1회
        call(post("/api/v1/fld/zones/" + zone2 + "/lock"), f.pm(), "PM", null).andExpect(status().isOk());
        assertThat(outbox("fld.survey.completed", f.round())).isEqualTo(1);
        JsonNode completed = lastOutbox("fld.survey.completed", f.round());
        assertThat(completed.get("zoneCount").asInt()).isEqualTo(2);
        assertThat(completed.get("defectCount").asInt()).isEqualTo(1);
        // 해제(사유 필수) → 재잠금해도 다시 발행하지 않음
        call(post("/api/v1/fld/zones/" + zone2 + "/unlock"), f.pm(), "PM", "{\"reason\":\"짧음\"}")
                .andExpect(status().isUnprocessableEntity());
        call(post("/api/v1/fld/zones/" + zone2 + "/unlock"), f.pm(), "PM", "{\"reason\":\"추가 조사 필요(누수 흔적)\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IN_PROGRESS"));
        call(post("/api/v1/fld/zones/" + zone2 + "/lock"), f.pm(), "PM", null).andExpect(status().isOk());
        assertThat(outbox("fld.survey.completed", f.round())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fld.zone_lock_audit WHERE zone_id = ?", Integer.class, zone2))
                .isEqualTo(3);

        // CST 마일스톤 WBS(현장조사) + RPT · CST 구독(실제 이벤트 흐름)
        UUID wbs = UUID.randomUUID();
        jdbc.update("INSERT INTO cst.wbs_node(id, project_id, code, name, weight_pct, milestone_event, sort_order) "
                + "VALUES (?, ?, 'W-FLD', '현장조사', 30, 'fld.survey.completed', 1)", wbs, f.project());
        drain();
        assertThat(jdbc.queryForObject("SELECT defect_count FROM rpt.field_survey_completion WHERE round_id = ?",
                Integer.class, f.round())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pct_complete FROM cst.progress_record WHERE wbs_id = ?",
                java.math.BigDecimal.class, wbs)).isEqualByComparingTo("100");

        // RPT 스냅샷: 결함은 fld::api 원천(요청 본문 defects 없음) — 확정 측정 · 장비 · 부재 매핑
        JsonNode report = flow.createReport(f.pm(), f.project(), f.building(), "RT_PRC", today());
        UUID reportId = UUID.fromString(report.get("id").asText());
        JsonNode frozen = flow.freeze(f.pm(), reportId, """
                {"facilityName":"합성 FLD 본관","fldRoundId":"%s",
                 "buildings":[{"code":"B01","name":"본관","buildingId":"%s","grossAreaM2":3200}],
                 "memberGroups":[{"buildingCode":"B01","floorCode":"1F","memberType":"COLUMN","memberCount":12}]}"""
                .formatted(f.round(), f.building()));
        assertThat(frozen.get("defectCount").asInt()).isEqualTo(1);
        assertThat(frozen.at("/sourceWatermarks/fld").asText()).isEqualTo("fld::api");
        assertThat(frozen.at("/sourceWatermarks/roundStatus").asText()).isEqualTo("COMPLETED");
        assertThat(frozen.get("issues").findValuesAsText("severity")).doesNotContain("BLOCKING");
        JsonNode doc = tree(jdbc.queryForObject("SELECT document::text FROM rpt.snapshot WHERE id = ?::uuid", String.class,
                frozen.get("snapshotId").asText()));
        assertThat(doc.at("/defects/0/id").asText()).isEqualTo("CRK-0001");
        assertThat(doc.at("/defects/0/memberType").asText()).isEqualTo("COLUMN");
        assertThat(doc.at("/defects/0/floorCode").asText()).isEqualTo("1F");
        assertThat(doc.at("/defects/0/widthMm").decimalValue()).isEqualByComparingTo("0.30");
        assertThat(doc.at("/defects/0/equipmentId").asText()).isEqualTo(equipment.toString());
        flow.call(RptFlow.postJson("/api/v1/rpt/reports/" + reportId + "/snapshots", """
                {"fldRoundId":"%s","buildings":[{"code":"B01","grossAreaM2":1}],"memberGroups":[],
                 "defects":[{"id":"X","buildingCode":"B01","floorCode":"1F","defectType":"CRACK"}]}""".formatted(f.round())),
                f.pm(), "PM", 422);

        // QAR AC-PHO 원천(fld::api): 검증 사진 없음 → 빈 목록(연동됨), 회차 없는 프로젝트 → null(미연동)
        var ev = qarEvidence.evidence(new QarEvidencePort.Query(f.project(), null, f.building(), List.of(), today()));
        assertThat(ev.photos()).isEmpty();
        assertThat(qarEvidence.evidence(new QarEvidencePort.Query(flow.project(employee()), null, null, List.of(),
                today())).photos()).isNull();
    }

    // ================================================================== AI 제안 · discrepancy · 음성 메모

    @Test
    @DisplayName("FR-FLD-012 · A8-8: AI 제안(사람 0.30 ↔ AI 0.90 = 3배) → fld.ai-suggestion.discrepancy 1회 → QAR 구독, 미결 제안은 잠금 차단 → 결정 op → 해제 · 음성 메모 · AIP 워커 꺼짐 = DISABLED")
    void aiSuggestionDiscrepancyAndVoice() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID d = recordedCrack(f, device, f.eng(), "ENG", f.column());
        byte[] data = jpeg(4);
        UUID photo = UUID.randomUUID();
        UUID up = UUID.fromString(createUpload(f, f.eng(), photo, d, device, data, sha(data), 201).get("uploadId").asText());
        patchChunk(f.eng(), up, 0, data).andExpect(status().isOk());
        // AIP 워커 꺼짐 → DISABLED(제안 없음), 켜짐 → 동기 Job(CRACK_MEASURE) 결과 폭 0.3 → 제안 1(사람 값과 같음 → 불일치 아님)
        aipWorker.setEnabled(false);
        try {
            call(post("/api/v1/fld/photos/" + photo + "/ai:analyze"), f.eng(), "ENG", null).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISABLED"));
        } finally {
            aipWorker.reset();
        }
        JsonNode a1 = tree(call(post("/api/v1/fld/photos/" + photo + "/ai:analyze"), f.eng(), "ENG", null)
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUGGESTED")).andReturn().getResponse()
                .getContentAsString());
        UUID sug1 = UUID.fromString(a1.get("suggestionId").asText());
        assertThat(a1.get("discrepancyRatio").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT width_mm FROM fld.ai_suggestion WHERE id = ?", java.math.BigDecimal.class,
                sug1)).isEqualByComparingTo("0.3");
        JsonNode s = tree(call(post("/api/v1/fld/internal/ai-suggestions"), admin, "ADMIN", """
                {"defectId":"%s","photoId":"%s","modelVersion":"crack-seg-synthetic-1","widthMm":0.90,"confidence":0.91,
                 "result":{"polygons":[]}}""".formatted(d, photo)).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
        UUID sug = UUID.fromString(s.get("suggestionId").asText());
        assertThat(s.get("defectStatus").asText()).isEqualTo("AI_SUGGESTED");
        assertThat(s.get("discrepancyRatio").decimalValue()).isEqualByComparingTo("3.000");
        assertThat(defect(f.eng(), "ENG", d).at("/attrs/width_mm").decimalValue()).isEqualByComparingTo("0.30"); // BR-FLD-004
        assertThat(outbox("fld.ai-suggestion.discrepancy", d)).isEqualTo(1);
        call(post("/api/v1/fld/internal/ai-suggestions"), f.eng(), "ENG", "{\"defectId\":\"%s\",\"modelVersion\":\"x\"}"
                .formatted(d)).andExpect(status().isForbidden());
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.issues[0].kind").value("AI_SUGGESTION_PENDING"));
        JsonNode dec = push(f.eng(), "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), d, "AiSuggestionDecided", hlc(100, 0, "v"),
                        "{\"suggestionId\":\"%s\",\"decision\":\"REJECTED\"}".formatted(sug)),
                op(UUID.randomUUID(), d, "AiSuggestionDecided", hlc(101, 0, "v"),
                        "{\"suggestionId\":\"%s\",\"decision\":\"ACCEPTED\"}".formatted(sug)),
                op(UUID.randomUUID(), d, "AiSuggestionDecided", hlc(102, 0, "v"),
                        "{\"suggestionId\":\"%s\",\"decision\":\"ACCEPTED\"}".formatted(sug1)));
        assertThat(dec.findValuesAsText("status")).containsExactly("ACCEPTED", "REJECTED", "ACCEPTED");
        assertThat(dec.at("/results/0/defectStatus").asText()).isEqualTo("AI_SUGGESTED");   // 제안 1 아직 미결
        assertThat(dec.at("/results/2/defectStatus").asText()).isEqualTo("RECORDED");
        assertThat(dec.at("/results/1/reason").asText()).isEqualTo("SUGGESTION_DECIDED");
        assertThat(jdbc.queryForObject("SELECT decision FROM fld.ai_suggestion WHERE id = ?", String.class, sug))
                .isEqualTo("REJECTED");
        assertThat(outbox("fld.ai-suggestion.decided", sug)).isEqualTo(1);
        assertThat(outbox("fld.ai-suggestion.discrepancy", d)).isEqualTo(1);   // 결정 때 다시 안 냄
        drain();
        assertThat(jdbc.queryForObject("SELECT ratio FROM qar.fld_ai_discrepancy WHERE suggestion_id = ?",
                java.math.BigDecimal.class, sug)).isEqualByComparingTo("3.000");

        // 음성 메모: 원본 음성 = core 첨부(여기서는 검증된 첨부 재사용), 없는 첨부는 거부
        UUID att = jdbc.queryForObject("SELECT attachment_id FROM fld.photo WHERE id = ?", UUID.class, photo);
        UUID memo = UUID.randomUUID();
        JsonNode v = push(f.eng(), "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), d, "VoiceMemoAttached", hlc(200, 0, "v"), """
                        {"voiceMemoId":"%s","attachmentId":"%s","transcript":"하부 백태 동반 누수 흔적","sttModel":"whisper-small-ko",
                         "durationMs":8200}""".formatted(memo, att)),
                op(UUID.randomUUID(), d, "VoiceMemoAttached", hlc(201, 0, "v"),
                        "{\"voiceMemoId\":\"%s\",\"attachmentId\":\"%s\"}".formatted(UUID.randomUUID(), UUID.randomUUID())));
        assertThat(v.findValuesAsText("status")).containsExactly("ACCEPTED", "REJECTED");
        assertThat(jdbc.queryForObject("SELECT transcript FROM fld.voice_memo WHERE id = ?", String.class, memo))
                .isEqualTo("하부 백태 동반 누수 흔적");
        assertThat(defect(f.eng(), "ENG", d).at("/voiceMemos/0/voiceMemoId").asText()).isEqualTo(memo.toString());

        // QAR AC-PHO 원천: 검증 사진 1장(결함 번호 CRK-0001)
        call(post("/api/v1/fld/zones/" + f.zone() + "/lock"), f.pm(), "PM", null).andExpect(status().isOk());
        var photos = qarEvidence.evidence(new QarEvidencePort.Query(f.project(), null, f.building(), List.of(), today()))
                .photos();
        assertThat(photos).hasSize(1);
        assertThat(photos.get(0).defectId()).isEqualTo("CRK-0001");
        assertThat(photos.get(0).sha256()).isEqualTo(sha(data));
    }

    // ================================================================== 권한 · 검증 · TC-FLD-015

    @Test
    @DisplayName("A2 권한 · 검증 · TC-FLD-015: 비투입 ENG 403 · 미배정 구역 ENG 거부 · 다른 건물 위치 노드 거부 · 시스템 이벤트 거부 · 시계 +10분 드리프트 경고")
    void permissionsAndValidation() throws Exception {
        Fx f = fixture();
        UUID device = UUID.randomUUID();
        UUID outsider = employee();
        call(post("/api/v1/fld/sync/push"), outsider, "ENG", """
                {"deviceId":"%s","roundId":"%s","ops":[%s]}""".formatted(device, f.round(),
                op(UUID.randomUUID(), UUID.randomUUID(), "DefectCreated", hlc(0, 0, "x"), created(f.zone(), "CRK", null))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        call(get("/api/v1/fld/sync/pull?roundId=" + f.round()), outsider, "ENG", null).andExpect(status().isForbidden());
        // 투입됐지만 구역 배정 아님 → REJECTED NOT_ASSIGNED
        UUID eng2 = employee();
        flow.member(f.project(), eng2, "PART");
        UUID otherBuildingNode = node(building("합성 다른 동"), null, "FLOOR", null, "9F");
        UUID d = UUID.randomUUID();
        JsonNode r = push(eng2, "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), d, "DefectCreated", hlc(0, 0, "x"), created(f.zone(), "CRK", null)));
        assertThat(r.at("/results/0/reason").asText()).isEqualTo("NOT_ASSIGNED");
        JsonNode v = push(f.eng(), "ENG", device, f.round(), 0,
                op(UUID.randomUUID(), d, "DefectCreated", hlc(600_000, 0, "x"), created(f.zone(), "CRK", otherBuildingNode)),
                op(UUID.randomUUID(), d, "DefectCreated", hlc(600_001, 0, "x"), created(f.zone(), "XYZ", null)),
                op(UUID.randomUUID(), d, "DefectCreated", hlc(600_002, 0, "x"),
                        "{\"zoneId\":\"%s\",\"typeCode\":\"CRK\",\"geomNorm\":{\"type\":\"LineString\",\"coords\":[[1.4,0.2]]}}"
                                .formatted(f.zone())),
                op(UUID.randomUUID(), d, "DefectVerified", hlc(600_003, 0, "x"), "{}"),
                op(UUID.randomUUID(), UUID.randomUUID(), "DefectMeasured", hlc(600_004, 0, "x"),
                        measured("width_mm", "0.2", "VISUAL", null)),
                op(UUID.randomUUID(), d, "DefectCreated", "1728950400123:3:bad", created(f.zone(), "CRK", null)));
        assertThat(v.findValuesAsText("reason")).containsExactly("UNKNOWN_LOCATION_NODE", "UNKNOWN_TYPE_CODE",
                "INVALID_GEOMETRY", "UNKNOWN_EVENT_TYPE", "UNKNOWN_DEFECT", "INVALID_HLC");
        // TC-FLD-015: 기기 시계 +10분 → 드리프트 경고(기록은 서버 수신 시각 received_at 기준)
        assertThat(v.get("warnings").findValuesAsText("")).isNotNull();
        assertThat(v.get("warnings").toString()).contains("CLOCK_DRIFT");
        assertThat(v.get("clockDriftMs").asLong()).isGreaterThan(5 * 60_000);
        // 500건 상한 초과 → 422
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(op(UUID.randomUUID(), UUID.randomUUID(), "DefectRemoved", hlc(i, 0, "x"), "{\"reason\":\"합성\"}"));
        }
        call(post("/api/v1/fld/sync/push"), f.eng(), "ENG", "{\"deviceId\":\"%s\",\"roundId\":\"%s\",\"ops\":[%s]}"
                .formatted(device, f.round(), String.join(",", many))).andExpect(status().isUnprocessableEntity());
        // ENG 는 남이 만든 결함을 삭제할 수 없다(A2: 본인 작성 24h 이내만)
        UUID pmDefect = recordedCrack(f, UUID.randomUUID(), f.pm(), "PM", null);
        assertThat(push(f.eng(), "ENG", device, f.round(), 0, op(UUID.randomUUID(), pmDefect, "DefectRemoved",
                hlc(700, 0, "x"), "{\"reason\":\"중복\"}")).at("/results/0/reason").asText()).isEqualTo("REMOVE_NOT_ALLOWED");
        // 같은 프로젝트에 같은 회차 번호 → 409
        call(post("/api/v1/fld/rounds"), f.pm(), "PM", """
                {"projectId":"%s","buildingId":"%s","roundNo":1,"surveyType":"REGULAR"}""".formatted(f.project(),
                f.building())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FLD-409-007"));
        call(get("/api/v1/fld/rounds/" + f.round()), f.eng(), "ENG", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.zones[0].status").value("IN_PROGRESS"));
    }
}
