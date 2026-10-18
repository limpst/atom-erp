package com.atomeng.erp;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.support.IntegrationTestBase;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 02-IMPL Step 3(대장 ACL) · Step 5(병합) · Step 7(위치 계층) — TC-FAC-002 · TC-FAC-011 · TC-FAC-008.
 * 공공 API 는 WireMock 합성 응답 (⚠️ 실제 명세 확인 필요).
 */
class FacAclMergeNodeIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger BUN = new AtomicInteger(5000 + (int) (System.nanoTime() % 3000));

    @Autowired
    OutboxRelay relay;

    UUID pm;

    @BeforeEach
    void setUp() {
        pm = actor();
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
    }

    /** 합성 주소 1건 → 고유 PNU(본번) 반환 */
    private String stubJuso(String keyword) {
        String bun = "%04d".formatted(BUN.incrementAndGet());
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/juso/addrLinkApi.do"))
                .withQueryParam("keyword", equalTo(keyword))
                .willReturn(okJson("""
                        {"results":{"common":{"errorCode":"0","totalCount":"1"},"juso":[{"roadAddr":"서울 합성구 %s",
                         "jibunAddr":"서울 합성구 합성동 %s","admCd":"1156011000","mtYn":"0","lnbrMnnm":"%s",
                         "lnbrSlno":"0","bdNm":"합성빌딩"}]}}""".formatted(keyword, bun, bun))));
        return bun;
    }

    private static String hub(String items) {
        return "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":" + items + "}}}";
    }

    private void stubRegister(String bun, String titleItems) {
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub("\"\""))));
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrTitleInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub(titleItems))));
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrFlrOulnInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub("\"\""))));
    }

    private static String title(String pk, String dong, int above, int below) {
        return """
                {"mgmBldrgstPk":"%s","bldNm":"합성 업무시설","dongNm":"%s","mainPurpsCdNm":"업무시설",
                 "strctCdNm":"철근콘크리트구조","totArea":"12000.5","grndFlrCnt":"%d","ugrndFlrCnt":"%d",
                 "useAprDay":"20010520"}""".formatted(pk, dong, above, below);
    }

    private String createManual(String name) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/fac/buildings", "{\"name\":\"" + name + "\",\"floorsAbove\":2}"),
                pm, "PM")).andExpect(status().isCreated()).andReturn();
        return json(r).get("id").asText();
    }

    // ------------------------------------------------------------------ Step 3

    @Test
    @DisplayName("Step3: 주소 → 대장 자동 입력 — REGISTER·VERIFIED, 층별 FLOOR 노드, 원본 스냅샷, 재조회는 캐시")
    void registerFromBuildingRegister() throws Exception {
        String bun = stubJuso("합성로 101");
        stubRegister(bun, "{\"item\":" + title("11560-777001", "", 5, 1) + "}");

        mvc.perform(as(get("/api/v1/fac/register-lookup").param("address", "합성로 101"), pm, "SALES"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address.pnu.value").value("11560110001" + bun + "0000"))
                .andExpect(jsonPath("$.register.available").value(true))
                .andExpect(jsonPath("$.register.titles[0].structureNm").value("철근콘크리트구조"));

        var r = mvc.perform(as(postJson("/api/v1/fac/buildings", "{\"lookupAddress\":\"합성로 101\"}"), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataSource").value("REGISTER"))
                .andExpect(jsonPath("$.status").value("VERIFIED"))
                .andExpect(jsonPath("$.name").value("합성 업무시설"))
                .andExpect(jsonPath("$.floorsAbove").value(5))
                .andExpect(jsonPath("$.approvalDate").value("2001-05-20"))
                .andReturn();
        String id = json(r).get("id").asText();
        assertThat(jdbc.queryForObject("SELECT register_snapshot->>'mgmBldrgstPk' FROM fac.building WHERE id = ?::uuid",
                String.class, id)).isEqualTo("11560-777001");
        mvc.perform(as(get("/api/v1/fac/buildings/" + id + "/nodes"), pm, "ENG"))
                .andExpect(jsonPath("$.length()").value(6)) // B1, 1F~5F
                .andExpect(jsonPath("$[0].code").value("B1"));
        PUBLIC_API.verify(1, getRequestedFor(urlPathEqualTo("/hub/getBrTitleInfo")).withQueryParam("bun", equalTo(bun)));
    }

    @Test
    @DisplayName("TC-FAC-002: 건축물대장 API 타임아웃 → 재시도 3회 후 수기 모드(data_source=MANUAL), 등록은 계속")
    void tcFac002_timeoutFallsBackToManual() throws Exception {
        String bun = stubJuso("합성로 202");
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub("\"\"")).withFixedDelay(1500)));

        var r = mvc.perform(as(postJson("/api/v1/fac/buildings",
                        "{\"lookupAddress\":\"합성로 202\",\"name\":\"합성 수기 건물\",\"floorsAbove\":3}"), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dataSource").value("MANUAL"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.pnu").value("11560110001" + bun + "0000")) // 주소 API 는 성공 → PNU 확보
                .andReturn();
        PUBLIC_API.verify(3, getRequestedFor(urlPathEqualTo("/hub/getBrRecapTitleInfo"))
                .withQueryParam("bun", equalTo(bun)));
        String id = json(r).get("id").asText();
        assertThat(jdbc.queryForObject("SELECT after_json->>'registerFailure' FROM core.audit_log WHERE module = 'FAC' "
                + "AND target_id = ?::uuid AND action = 'CREATE'", String.class, id)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("Step3: 표제부 다건이면 동 선택 요구(422 + registerCandidates), mgmBldrgstPk 지정 시 해당 동 등록")
    void multipleTitlesRequireSelection() throws Exception {
        String bun = stubJuso("합성로 303");
        stubRegister(bun, "{\"item\":[" + title("11560-888001", "가동", 3, 0) + "," + title("11560-888002", "나동", 7, 1)
                + "]}");
        mvc.perform(as(postJson("/api/v1/fac/buildings", "{\"lookupAddress\":\"합성로 303\"}"), pm, "PM"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("FAC-422-001"))
                .andExpect(jsonPath("$.registerCandidates.length()").value(2));
        mvc.perform(as(postJson("/api/v1/fac/buildings",
                        "{\"lookupAddress\":\"합성로 303\",\"mgmBldrgstPk\":\"11560-888002\"}"), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dongName").value("나동"))
                .andExpect(jsonPath("$.floorsAbove").value(7));
    }

    // ------------------------------------------------------------------ Step 7

    @Test
    @DisplayName("Step7: 그리드 템플릿 일괄 생성(COL·GIR·SLAB, 코드 3F-C-X2Y3) → ltree 트리 조회 · 이동 · 코드 유일성")
    void gridTemplateAndTree() throws Exception {
        String id = createManual("합성 템플릿 건물");
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + id + "/nodes:generate",
                        "{\"floorFrom\":1,\"floorTo\":3,\"xGrid\":[\"X1\",\"X2\",\"X3\"],\"yGrid\":[\"Y1\",\"Y2\"]}"),
                        pm, "ENG"))
                .andExpect(status().isCreated())
                // 층당: ZONE 1 + COL 6 + GIR (X 방향 2×2=4, Y 방향 3×1=3) + SLAB 2 = 16, 3F 는 FLOOR 신규 1 → 49
                .andExpect(jsonPath("$.created").value(49));
        assertThat(jdbc.queryForObject("SELECT path::text FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND code = '3F-C-X2Y2'", String.class, id))
                .matches("b_[0-9a-f]{32}\\.f_3\\.z_1\\.m_3f_c_x2y2");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND path <@ (SELECT path FROM fac.location_node WHERE building_id = ?::uuid AND code = '2F')",
                Integer.class, id, id)).isEqualTo(17); // 2F + 하위 16
        mvc.perform(as(get("/api/v1/fac/buildings/" + id + "/nodes"), pm, "QA"))
                .andExpect(jsonPath("$[0].code").value("1F"))
                .andExpect(jsonPath("$[0].children[0].code").value("1F-Z1"))
                .andExpect(jsonPath("$[0].children[0].children.length()").value(15));

        // 이동: 3F 의 부재 하나를 2F 구역으로 → 경로 갱신
        String member = jdbc.queryForObject("SELECT id::text FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND code = '3F-C-X1Y1'", String.class, id);
        String zone2 = jdbc.queryForObject("SELECT id::text FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND code = '2F-Z1'", String.class, id);
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + id + "/nodes/" + member + ":move",
                        "{\"newParentId\":\"" + zone2 + "\"}"), pm, "PM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value(org.hamcrest.Matchers.endsWith(".f_2.z_1.m_3f_c_x1y1")));
        // 같은 템플릿 재생성 → 코드 중복(ux_node_code) 409
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + id + "/nodes:generate",
                        "{\"floorFrom\":1,\"floorTo\":1,\"xGrid\":[\"X1\",\"X2\"],\"yGrid\":[\"Y1\",\"Y2\"]}"), pm, "ENG"))
                .andExpect(status().isConflict());
        // 권한: SALES 는 조회만
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + id + "/nodes:generate",
                        "{\"floorFrom\":4,\"floorTo\":4,\"xGrid\":[\"X1\",\"X2\"],\"yGrid\":[\"Y1\",\"Y2\"]}"), pm, "SALES"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("TC-FAC-008: 결함이 참조하는 member 삭제 → 422 FAC-422-002(대체 필요), replacedById 지정 시 논리삭제 + 참조 이관")
    void tcFac008_memberWithDefectNeedsReplacement() throws Exception {
        String id = createManual("합성 결함 건물");
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + id + "/nodes:generate",
                "{\"floorFrom\":1,\"floorTo\":1,\"xGrid\":[\"X1\",\"X2\"],\"yGrid\":[\"Y1\",\"Y2\"],"
                        + "\"memberTypes\":[\"COL\"]}"), pm, "ENG")).andExpect(status().isCreated());
        UUID col = UUID.fromString(jdbc.queryForObject("SELECT id::text FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND code = '1F-C-X1Y1'", String.class, id));
        UUID other = UUID.fromString(jdbc.queryForObject("SELECT id::text FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND code = '1F-C-X2Y1'", String.class, id));
        // FLD 결함 기록 이벤트(스텁 계약) → 참조 읽기 모델
        UUID defect = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at)
                VALUES (?, 'Defect', ?, 'fld.defect.recorded', ?::jsonb, now() - interval '1 minute')""", UUID.randomUUID(), defect,
                "{\"defectId\":\"" + defect + "\",\"locationNodeId\":\"" + col + "\",\"defectType\":\"CRACK\"}");
        relay.pollOnce();

        mvc.perform(as(delete("/api/v1/fac/buildings/" + id + "/nodes/" + col), pm, "PM"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("FAC-422-002"))
                .andExpect(jsonPath("$.defectRefs").value(1));
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM fac.location_node WHERE id = ?", Boolean.class, col))
                .isTrue();

        mvc.perform(as(delete("/api/v1/fac/buildings/" + id + "/nodes/" + col).param("replacedById", other.toString()),
                        pm, "PM"))
                .andExpect(status().isNoContent());
        var row = jdbc.queryForMap("SELECT deleted_at, replaced_by_id FROM fac.location_node WHERE id = ?", col);
        assertThat(row.get("deleted_at")).isNotNull();          // 물리 삭제 아님(논리삭제)
        assertThat(row.get("replaced_by_id")).isEqualTo(other);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.node_defect_ref WHERE node_id = ? AND defect_id = ?",
                Integer.class, other, defect)).isEqualTo(1);
        // 참조 없는 부재는 대체 없이 삭제 가능
        UUID free = UUID.fromString(jdbc.queryForObject("SELECT id::text FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND code = '1F-C-X2Y2'", String.class, id));
        mvc.perform(as(delete("/api/v1/fac/buildings/" + id + "/nodes/" + free), pm, "ENG"))
                .andExpect(status().isNoContent());
    }

    // ------------------------------------------------------------------ Step 5

    @Test
    @DisplayName("TC-FAC-011: 병합 → 노드·등급·도래·보수 전부 target 이관, source=MERGED, fac.building.merged 1건")
    void tcFac011_merge() throws Exception {
        String src = createManual("합성 병합 원본");
        String tgt = createManual("합성 병합 존속");
        for (String b : new String[] {src, tgt}) {
            mvc.perform(as(put("/api/v1/fac/buildings/" + b + "/classification"), pm, "PM")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"sitbeopClass\":\"CLASS2\",\"bmaTarget\":false,\"evidence\":{\"note\":\"합성\"}}"))
                    .andExpect(status().isOk());
        }
        // source: 노드(1F·2F 기존 + 템플릿), 등급 2건(보고서 이벤트), 보수 1건
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + src + "/nodes:generate",
                "{\"floorFrom\":1,\"floorTo\":1,\"xGrid\":[\"X1\",\"X2\"],\"yGrid\":[\"Y1\",\"Y2\"]}"), pm, "ENG"))
                .andExpect(status().isCreated());
        int srcNodes = jdbc.queryForObject("SELECT count(*) FROM fac.location_node WHERE building_id = ?::uuid",
                Integer.class, src);
        for (String[] g : new String[][] {{"B", "400"}, {"C", "20"}}) {
            UUID rep = UUID.randomUUID();
            jdbc.update("INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at) "
                    + "VALUES (?, 'Report', ?, 'rpt.report.published', ?::jsonb, now() - interval '1 minute')", UUID.randomUUID(), rep,
                    "{\"reportId\":\"%s\",\"buildingIds\":[\"%s\"],\"grade\":\"%s\",\"inspectionType\":\"REGULAR\",\"performedOn\":\"%s\"}"
                            .formatted(rep, src, g[0], LocalDate.now().minusDays(Integer.parseInt(g[1]))));
        }
        relay.pollOnce();
        UUID actorId = UUID.randomUUID();
        jdbc.update("INSERT INTO fac.repair_history(id, building_id, method, created_by, updated_by) "
                + "VALUES (?, ?::uuid, '단면복구(합성)', ?, ?)", UUID.randomUUID(), src, actorId, actorId);

        var report = mvc.perform(as(postJson("/api/v1/fac/buildings/" + src + "/merge",
                        "{\"targetId\":\"" + tgt + "\",\"reason\":\"동일 건물 중복 등록(합성)\"}"), pm, "PM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes").value(srcNodes))
                .andExpect(jsonPath("$.grades").value(2))
                .andExpect(jsonPath("$.repairs").value(1))
                .andExpect(jsonPath("$.duesCancelled").value(1))
                .andReturn();
        assertThat(json(report).get("targetId").asText()).isEqualTo(tgt);

        assertThat(jdbc.queryForObject("SELECT status FROM fac.building WHERE id = ?::uuid", String.class, src))
                .isEqualTo("MERGED");
        assertThat(jdbc.queryForObject("SELECT merged_into_id::text FROM fac.building WHERE id = ?::uuid", String.class,
                src)).isEqualTo(tgt);
        // 노드: 코드 충돌(1F·2F)은 -M1 접미사, 경로 루트는 target
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.location_node WHERE building_id = ?::uuid", Integer.class,
                tgt)).isEqualTo(srcNodes + 2);
        assertThat(jdbc.queryForList("SELECT code FROM fac.location_node WHERE building_id = ?::uuid AND code LIKE '%-M1' "
                + "ORDER BY code", String.class, tgt)).containsExactly("1F-M1", "2F-M1");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.location_node WHERE building_id = ?::uuid "
                + "AND NOT (path <@ text2ltree(?))", Integer.class, tgt, "b_" + tgt.replace("-", ""))).isZero();
        // 등급: target 에 복제(correction_of=원본), 원본은 append-only 로 그대로
        mvc.perform(as(get("/api/v1/fac/buildings/" + tgt + "/grades"), pm, "PM"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].grade").value("C"))
                .andExpect(jsonPath("$[0].correctionOf").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.grade_history WHERE building_id = ?::uuid", Integer.class,
                src)).isEqualTo(2);
        // 도래: source 활성 0, target 재계산(정기점검 C 기준)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.inspection_due WHERE building_id = ?::uuid "
                + "AND status NOT IN ('DONE','LOST','CANCELLED')", Integer.class, src)).isZero();
        mvc.perform(as(get("/api/v1/fac/buildings/" + tgt + "/dues"), pm, "PM"))
                .andExpect(jsonPath("$[?(@.inspectionType == 'REGULAR')].dueDate")
                        .value(LocalDate.now().minusDays(20).plusMonths(6).toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'fac.building.merged' "
                + "AND aggregate_id = ?::uuid AND payload->>'targetId' = ?", Integer.class, src, tgt)).isEqualTo(1);

        // 재병합·자기 병합 거부, 권한
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + src + "/merge",
                        "{\"targetId\":\"" + tgt + "\",\"reason\":\"재시도\"}"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAC-409-002"));
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + tgt + "/merge",
                        "{\"targetId\":\"" + tgt + "\",\"reason\":\"x\"}"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FAC-409-003"));
        mvc.perform(as(postJson("/api/v1/fac/buildings/" + tgt + "/merge",
                        "{\"targetId\":\"" + src + "\",\"reason\":\"x\"}"), pm, "SALES"))
                .andExpect(status().isForbidden());
    }
}
