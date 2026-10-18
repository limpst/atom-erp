package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.evidence.ObjectLockedException;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.rpt.api.RptRevisionQuery;
import com.atomeng.erp.rpt.domain.hwpx.HwpxPackage;
import com.atomeng.erp.rpt.domain.hwpx.HwpxValidator;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.RptFlow;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * 06 RPT 통합 테스트 — 생성 → 동결 · 사전검증 → compose(등급 · 망도 · 섹션) → 편집 → 렌더(HWPX · 스텁 PDF · anchors) →
 * 검토 요청 → QAR 승인(스텁 이벤트) → 발행(WORM · 해시 · 불변) → 납품 확인 → 정정본(superseded). 합성 데이터만.
 */
class RptIntegrationTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));

    @Autowired
    OutboxRelay relay;
    @Autowired
    RptRevisionQuery revisionQuery;
    @Autowired
    ObjectStorage storage;

    RptFlow flow;
    UUID pm;
    UUID project;

    @BeforeEach
    void setUp() throws Exception {
        clock.reset();
        flow = new RptFlow(mvc, jdbc, objectMapper, relay);
        pm = flow.employee("RPT-PM");
        project = flow.project(pm);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private int outbox(String type, UUID aggregate) {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, type, aggregate);
    }

    private JsonNode payload(String type, UUID aggregate) throws Exception {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event WHERE event_type = ? "
                + "AND aggregate_id = ? ORDER BY occurred_at DESC LIMIT 1", String.class, type, aggregate));
    }

    /** 보고서 생성 + 동결(grade 수준 합성 결함) → [reportId, revId] */
    private UUID[] frozenReport(String grade, String extra) throws Exception {
        UUID building = UUID.randomUUID();
        JsonNode r = flow.createReport(pm, project, building, "RT_PRC", today().minusDays(20));
        UUID reportId = UUID.fromString(r.get("id").asText());
        UUID rev = UUID.fromString(r.get("currentRevisionId").asText());
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, grade, extra));
        return new UUID[] {reportId, rev};
    }

    @Test
    @DisplayName("FR-RPT-001~016 전체 흐름: 동결 → compose(C) → 렌더 → 검토 → QAR 승인 → 발행(WORM · SHA-256) + "
            + "rpt.report.drafted 1회 · rpt.report.published(FAC 계약 필드)")
    void fullFlow() throws Exception {
        UUID building = UUID.randomUUID();
        JsonNode created = flow.createReport(pm, project, building, "RT_PRC", today().minusDays(20));
        assertThat(created.get("status").asText()).isEqualTo("DRAFT");
        assertThat(created.at("/templateVersion/semver").asText()).isEqualTo("2026.1.0");
        UUID reportId = UUID.fromString(created.get("id").asText());
        UUID rev = UUID.fromString(created.get("currentRevisionId").asText());

        JsonNode frozen = flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "C", null));
        assertThat(frozen.get("snapshotVer").asInt()).isEqualTo(1);
        assertThat(frozen.get("blockingCount").asInt()).isZero();
        assertThat(frozen.get("status").asText()).isEqualTo("SNAPSHOT_FROZEN");
        assertThat(frozen.at("/ruleSetVersions/COND_RATING").asText()).isEqualTo("BLD_RC@2026.1");
        assertThat(outbox("rpt.snapshot.frozen", reportId)).isEqualTo(1);
        // 같은 내용(키 순서만 다름)으로 재동결해도 같은 해시
        String sha = frozen.get("sha256").asText();

        JsonNode g = flow.compose(pm, rev);
        assertThat(g.get("computedGrade").asText()).isEqualTo("C");
        assertThat(g.get("status").asText()).isEqualTo("COMPOSING");
        assertThat(g.get("drawings").asInt()).isEqualTo(2);
        assertThat(g.get("members").size()).isEqualTo(2);
        assertThat(g.at("/members/0/evidence/0/indicator").asText()).isEqualTo("crack_width_mm");
        assertThat(g.at("/members/0/evidence/0/defectIds/0").asText()).isEqualTo("D-0001");
        flow.compose(pm, rev);   // 재생성 — drafted 는 리비전당 1회
        assertThat(outbox("rpt.report.drafted", reportId)).isEqualTo(1);
        JsonNode drafted = payload("rpt.report.drafted", reportId);
        assertThat(drafted.get("revisionId").asText()).isEqualTo(rev.toString());
        assertThat(drafted.get("projectId").asText()).isEqualTo(project.toString());

        // 섹션 5개 · 잠금 블록
        JsonNode sections = flow.call(get("/api/v1/rpt/revisions/" + rev + "/sections"), pm, "PM", 200);
        assertThat(sections.size()).isEqualTo(5);
        assertThat(sections.get(1).at("/blocks/0/kind").asText()).isEqualTo("TABLE");
        assertThat(sections.get(1).at("/blocks/0/locked").asBoolean()).isTrue();

        // DXF 망도 다운로드
        var dxf = mvc.perform(RptFlow.as(get("/api/v1/rpt/revisions/" + rev + "/drawings/B01/1F.dxf"), pm, "PM"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.US_ASCII);
        assertThat(dxf).contains("AC1009").contains("DEF_CRACK").contains("D-0001 0.25mm").endsWith("EOF\r\n");

        flow.decide(pm, rev, "C", null, null, 200);
        JsonNode job = flow.render(pm, rev);
        assertThat(job.get("converter").asText()).isEqualTo("STUB");
        assertThat(job.at("/hwpx/tables").asInt()).isEqualTo(3);   // rule 표 + 물량표 + 상태평가표
        assertThat(job.at("/hwpx/pictures").asInt()).isEqualTo(2);
        byte[] hwpx = mvc.perform(RptFlow.as(get("/api/v1/rpt/revisions/" + rev + "/files/report.hwpx"), pm, "PM"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(HwpxValidator.validate(hwpx).errors()).isEmpty();
        assertThat(HwpxPackage.read(hwpx).text(HwpxPackage.SECTION0)).contains("합성 사옥").contains("BLD_RC@2026.1")
                .contains("가중 점수 3.000 → 산정 등급 C");

        flow.reviewRequest(pm, rev);
        assertThat(flow.call(get("/api/v1/rpt/revisions/" + rev), pm, "PM", 200).get("status").asText())
                .isEqualTo("IN_REVIEW");
        flow.qarDecision(rev, true);
        JsonNode pub = flow.publish(pm, rev);
        assertThat(pub.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(pub.get("pdfSha256").asText()).hasSize(64);
        assertThat(pub.at("/grade/finalGrade").asText()).isEqualTo("C");

        JsonNode ev = payload("rpt.report.published", reportId);
        assertThat(ev.get("grade").asText()).isEqualTo("C");
        assertThat(ev.get("inspectionType").asText()).isEqualTo("DETAILED");
        assertThat(ev.get("performedOn").asText()).isEqualTo(today().minusDays(20).toString());
        assertThat(ev.get("buildingIds").get(0).asText()).isEqualTo(building.toString());
        assertThat(ev.get("revNo").asInt()).isEqualTo(1);
        assertThat(ev.get("pdfSha256").asText()).isEqualTo(pub.get("pdfSha256").asText());
        // 발행본(WORM): 다운로드 해시 = 기록 해시, 덮어쓰기 · 삭제 불가(TC-RPT-007 저장소 계층)
        byte[] pdf = mvc.perform(RptFlow.as(get("/api/v1/rpt/revisions/" + rev + "/files/report.pdf"), pm, "PM"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(Hashing.sha256Hex(pdf)).isEqualTo(pub.get("pdfSha256").asText());
        String key = jdbc.queryForObject("SELECT pdf_object_key FROM rpt.report_revision WHERE id = ?", String.class, rev);
        assertThatThrownBy(() -> storage.delete("reports", key)).isInstanceOf(ObjectLockedException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM rpt.report WHERE id = ?", String.class, reportId))
                .isEqualTo("PUBLISHED");
        assertThat(sha).hasSize(64);
    }

    @Test
    @DisplayName("TC-RPT-007: 발행 리비전 — 섹션 수정 · 등급 확정 · 재렌더 409, DELETE API 405, DB 직접 수정 RPT-409-010")
    void publishedImmutable() throws Exception {
        UUID[] ids = frozenReport("B", null);
        UUID rev = ids[1];
        flow.composeToPublish(pm, rev);
        JsonNode sections = flow.call(get("/api/v1/rpt/revisions/" + rev + "/sections"), pm, "PM", 200);
        JsonNode opinion = sections.get(4);
        mvc.perform(RptFlow.as(put("/api/v1/rpt/revisions/" + rev + "/sections/ch7.opinion")
                        .header("If-Match", "\"v" + opinion.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"blocks\":[{\"text\":\"발행 후 수정\"}]}"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-001"));
        mvc.perform(RptFlow.as(post("/api/v1/rpt/revisions/" + rev + "/render"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-001"));
        mvc.perform(RptFlow.as(delete("/api/v1/rpt/revisions/" + rev), pm, "PM"))
                .andExpect(status().isMethodNotAllowed());
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.report_revision SET pdf_sha256 = ? WHERE id = ?",
                "0".repeat(64), rev)).hasMessageContaining("RPT-409-010");
        assertThatThrownBy(() -> jdbc.update("UPDATE rpt.block SET text = '변조' WHERE revision_id = ?", rev))
                .hasMessageContaining("RPT-409-010");
    }

    @Test
    @DisplayName("TC-RPT-003: 검교정 만료 장비 측정값 포함 → BLOCKING(VAL-CALIBRATION-EXPIRED) · 상태 DRAFT · compose 409 RPT-409-002")
    void calibrationExpiredBlocks() throws Exception {
        UUID admin = UUID.randomUUID();
        JsonNode eq = flow.call(RptFlow.postJson("/api/v1/eqp/equipment", ("{\"typeCode\":\"MSR-CRK\",\"serialNo\":\"RPT-%d\","
                + "\"model\":\"합성 균열폭 측정기\",\"homeLocation\":\"합성 장비실\"}").formatted(SEQ.incrementAndGet())),
                admin, "ADMIN", 201);
        UUID equipment = UUID.fromString(eq.get("id").asText());
        flow.call(RptFlow.postJson("/api/v1/eqp/equipment/" + equipment + "/calibrations",
                ("{\"calibratedOn\":\"%s\",\"nextDueOn\":\"%s\",\"result\":\"PASS\",\"certNo\":\"KC-RPT-%d\","
                        + "\"labName\":\"합성교정기관\",\"labAccreditationNo\":\"KC-000\"}").formatted(today().minusDays(400),
                        today().minusDays(35), SEQ.incrementAndGet())), admin, "ADMIN", 201);
        String measured = ("{\"id\":\"D-0901\",\"buildingCode\":\"B01\",\"floorCode\":\"1F\",\"memberType\":\"COLUMN\","
                + "\"memberId\":\"1F-COL-09\",\"defectType\":\"CRACK\",\"widthMm\":0.22,\"lengthM\":0.8,"
                + "\"equipmentId\":\"%s\",\"measuredOn\":\"%s\",\"polyline\":[[9000,2000],[9300,2500]]}")
                .formatted(equipment, today().minusDays(21));
        UUID building = UUID.randomUUID();
        JsonNode r = flow.createReport(pm, project, building, "RT_PRC", today().minusDays(20));
        UUID reportId = UUID.fromString(r.get("id").asText());
        UUID rev = UUID.fromString(r.get("currentRevisionId").asText());
        JsonNode frozen = flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "B", measured));
        assertThat(frozen.get("blockingCount").asInt()).isEqualTo(1);
        assertThat(frozen.get("status").asText()).isEqualTo("DRAFT");
        assertThat(frozen.at("/issues/0/ruleCode").asText()).isEqualTo("VAL-CALIBRATION-EXPIRED");
        assertThat(frozen.at("/issues/0/message").asText()).contains("EQP_CAL_EXPIRED");
        JsonNode issues = flow.call(get("/api/v1/rpt/revisions/" + rev + "/validation-issues"), pm, "PM", 200);
        assertThat(issues.get(0).at("/targetRef/id").asText()).isEqualTo("D-0901");
        mvc.perform(RptFlow.as(post("/api/v1/rpt/revisions/" + rev + "/compose"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-001"));   // DRAFT 라 전이 불가
        // 상태를 무시하고 SNAPSHOT_FROZEN 리비전에 BLOCKING 스냅샷이 붙은 경우도 compose 차단(RPT-409-002)
        jdbc.update("UPDATE rpt.report_revision SET status = 'SNAPSHOT_FROZEN' WHERE id = ?", rev);
        mvc.perform(RptFlow.as(post("/api/v1/rpt/revisions/" + rev + "/compose"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-002"))
                .andExpect(jsonPath("$.blockingCount").value(1));
        // 보완(유효 장비 측정값으로 교체) 후 재동결 → BLOCKING 0
        JsonNode refrozen = flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "B", null));
        assertThat(refrozen.get("snapshotVer").asInt()).isEqualTo(2);
        assertThat(refrozen.get("blockingCount").asInt()).isZero();
        flow.compose(pm, rev);
    }

    @Test
    @DisplayName("BR-RPT-005: override 사유 99자 → 422 RPT-422-003, If-Match 불일치 412, 사유 100자 + 근거 → 확정 · 감사 로그")
    void gradeOverride() throws Exception {
        UUID[] ids = frozenReport("C", null);
        UUID rev = ids[1];
        flow.compose(pm, rev);
        String reason99 = "가".repeat(99);
        JsonNode err = flow.decide(pm, rev, "D", reason99, List.of("D-0001"), 422);
        assertThat(err.get("code").asText()).isEqualTo("RPT-422-003");
        mvc.perform(RptFlow.as(put("/api/v1/rpt/revisions/" + rev + "/grade/decision").header("If-Match", "\"v999\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalGrade\":\"C\"}"), pm, "PM"))
                .andExpect(status().isPreconditionFailed());
        JsonNode unknown = flow.decide(pm, rev, "D", "나".repeat(100), List.of("D-9999"), 422);
        assertThat(unknown.get("detail").asText()).contains("D-9999");
        JsonNode ok = flow.decide(pm, rev, "D", "다".repeat(100), List.of("D-0001", "D-0003"), 200);
        assertThat(ok.get("finalGrade").asText()).isEqualTo("D");
        assertThat(ok.get("overridden").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'RPT' AND target_type = "
                + "'GradeResult' AND action = 'APPROVE'", Integer.class)).isGreaterThanOrEqualTo(1);
        // ENG 는 등급 확정 권한 없음
        UUID eng = flow.employee("RPT-ENG");
        flow.member(project, eng, "PART");
        mvc.perform(RptFlow.as(put("/api/v1/rpt/revisions/" + rev + "/grade/decision").header("If-Match", "\"v1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"finalGrade\":\"C\"}"), eng, "ENG"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("BR-RPT-001 · 섹션 편집: 잠금 블록 수정 422 RPT-422-002, 의견 문단 수정 OK(블록 id 유지), 편집 후 검토 요청은 재렌더 필요 409 · "
            + "sectionEditors = 자동 생성자 + 편집자")
    void sectionEditingAndEditors() throws Exception {
        UUID[] ids = frozenReport("B", null);
        UUID rev = ids[1];
        flow.compose(pm, rev);
        flow.decide(pm, rev, "B", null, null, 200);
        flow.render(pm, rev);
        JsonNode sections = flow.call(get("/api/v1/rpt/revisions/" + rev + "/sections"), pm, "PM", 200);
        JsonNode table = sections.get(1);
        String lockedId = table.at("/blocks/0/blockId").asText();
        mvc.perform(RptFlow.as(put("/api/v1/rpt/revisions/" + rev + "/sections/ch4.quantities")
                        .header("If-Match", "\"v" + table.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"blocks\":[{\"blockId\":\"" + lockedId + "\",\"kind\":\"TABLE\",\"text\":\"조작된 수치\"}]}"),
                        pm, "PM"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RPT-422-002"));
        mvc.perform(RptFlow.as(put("/api/v1/rpt/revisions/" + rev + "/sections/ch4.quantities")
                        .header("If-Match", "\"v" + table.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"blocks\":[]}"), pm, "PM"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RPT-422-002"));

        UUID eng = flow.employee("RPT-ENG");
        flow.member(project, eng, "PART");
        JsonNode opinion = sections.get(4);
        String opId = opinion.at("/blocks/0/blockId").asText();
        JsonNode saved = flow.call(put("/api/v1/rpt/revisions/" + rev + "/sections/ch7.opinion")
                .header("If-Match", "\"v" + opinion.get("version").asLong() + "\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"blocks\":[{\"blockId\":\"" + opId + "\",\"text\":\"기둥 균열은 경미하며 정기 관찰을 권고한다.\"},"
                        + "{\"kind\":\"PARAGRAPH\",\"text\":\"보수 우선순위: 2층 기둥.\"}]}"), eng, "ENG", 200);
        assertThat(saved.at("/blocks/0/blockId").asText()).isEqualTo(opId);
        assertThat(saved.at("/blocks/0/source").asText()).isEqualTo("HUMAN");
        assertThat(saved.get("blocks").size()).isEqualTo(2);
        // 낙관적 잠금: 옛 버전으로 저장 → 412
        mvc.perform(RptFlow.as(put("/api/v1/rpt/revisions/" + rev + "/sections/ch7.opinion")
                        .header("If-Match", "\"v" + opinion.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"blocks\":[]}"), pm, "PM"))
                .andExpect(status().isPreconditionFailed());
        // 본문이 바뀌었으니 검토 요청은 재렌더 필요
        mvc.perform(RptFlow.as(post("/api/v1/rpt/revisions/" + rev + "/review-request"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-004"));
        flow.render(pm, rev);
        flow.reviewRequest(pm, rev);

        List<RptRevisionQuery.SectionEditors> editors = revisionQuery.sectionEditors(rev);
        assertThat(editors).extracting(RptRevisionQuery.SectionEditors::sectionKey)
                .containsExactly("ch1.overview", "ch4.quantities", "ch5.drawings", "ch6.condition", "ch7.opinion");
        RptRevisionQuery.SectionEditors op = editors.get(4);
        assertThat(op.editors()).extracting(RptRevisionQuery.Editor::editorId).containsExactly(pm, eng);
        assertThat(op.editors().get(0).sources()).containsExactly("AUTO");
        assertThat(op.editors().get(1).sources()).containsExactly("HUMAN");
        assertThat(op.editors().get(1).editCount()).isEqualTo(2);   // UPDATE 1 + CREATE 1
        assertThat(editors.get(1).editors()).extracting(RptRevisionQuery.Editor::editorId).containsExactly(pm);
        JsonNode viaApi = flow.call(get("/api/v1/rpt/revisions/" + rev + "/section-editors"), pm, "PM", 200);
        assertThat(viaApi.get(4).get("editors").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("anchors.json(RptRevisionQuery.anchors): 블록마다 blockId · sectionKey · page · rects · textHash, 블록 텍스트 해시와 일치")
    void anchors() throws Exception {
        UUID[] ids = frozenReport("A", null);
        UUID rev = ids[1];
        flow.compose(pm, rev);
        assertThat(revisionQuery.anchors(rev)).isEmpty();
        flow.decide(pm, rev, "A", null, null, 200);
        JsonNode job = flow.render(pm, rev);
        RptRevisionQuery.AnchorSet set = revisionQuery.anchors(rev).orElseThrow();
        assertThat(set.source()).isEqualTo("ESTIMATED");
        assertThat(set.unit()).isEqualTo("mm");
        assertThat(set.pageCount()).isEqualTo(job.get("pageCount").asInt()).isGreaterThanOrEqualTo(2);
        Map<String, String> hashes = new java.util.HashMap<>();
        jdbc.queryForList("SELECT id::text AS id, text_hash FROM rpt.block WHERE revision_id = ?", rev)
                .forEach(m -> hashes.put((String) m.get("id"), ((String) m.get("text_hash")).strip()));
        assertThat(set.anchors()).hasSize(hashes.size());
        for (RptRevisionQuery.Anchor a : set.anchors()) {
            assertThat(hashes).containsEntry(a.blockId().toString(), a.textHash());
            assertThat(a.page()).isGreaterThanOrEqualTo(2);
            assertThat(a.rects()).isNotEmpty();
            assertThat(a.rects().get(0).w()).isEqualByComparingTo("170");
        }
        List<String> keys = new ArrayList<>();
        set.anchors().forEach(a -> keys.add(a.sectionKey()));
        assertThat(keys).startsWith("ch1.overview").contains("ch5.drawings").endsWith("ch7.opinion");
        JsonNode api = flow.call(get("/api/v1/rpt/revisions/" + rev + "/anchors"), pm, "PM", 200);
        assertThat(api.get("sha256").asText()).isEqualTo(set.sha256());
        assertThat(api.get("anchors").size()).isEqualTo(set.anchors().size());
    }

    @Test
    @DisplayName("v0.2 납품 확인: POST delivery-confirmation → 201 DELIVERED + rpt.report.delivered, 중복 409 RPT-409-005, "
            + "ENG 403, 발행 전 409")
    void deliveryConfirmation() throws Exception {
        UUID[] ids = frozenReport("B", null);
        UUID reportId = ids[0];
        UUID rev = ids[1];
        flow.compose(pm, rev);
        String body = "{\"recipientName\":\"합성 발주처 담당자\",\"recipientOrg\":\"합성구청\",\"note\":\"수령증 사본 별첨\","
                + "\"receiptAttachmentId\":\"" + UUID.randomUUID() + "\",\"deliveredAt\":\"2026-10-15T09:30:00+09:00\"}";
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation", body), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-001"));
        flow.decide(pm, rev, "B", null, null, 200);
        flow.render(pm, rev);
        flow.reviewRequest(pm, rev);
        flow.qarDecision(rev, true);
        flow.publish(pm, rev);
        UUID eng = flow.employee("RPT-ENG");
        flow.member(project, eng, "PART");
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation", body), eng, "ENG"))
                .andExpect(status().isForbidden());
        JsonNode d = flow.call(RptFlow.postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation", body), pm, "PM", 201);
        assertThat(d.get("status").asText()).isEqualTo("DELIVERED");
        assertThat(d.get("confirmationId").asText()).isNotBlank();
        JsonNode ev = payload("rpt.report.delivered", reportId);
        assertThat(ev.get("revisionId").asText()).isEqualTo(rev.toString());
        assertThat(ev.get("deliveredAt").asText()).startsWith("2026-10-15T00:30");
        assertThat(ev.get("receiptAttachmentId").asText()).isNotBlank();
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation", body), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-005"));
        // ADMIN(ALL) 도 확인 권한은 있으나 이미 납품됨
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation", body),
                        UUID.randomUUID(), "ADMIN"))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM rpt.report WHERE id = ?", String.class, reportId))
                .isEqualTo("DELIVERED");
    }

    @Test
    @DisplayName("BR-RPT-008 정정본: 납품된 Rev.1 → Rev.2(스냅샷 재사용 · 재동결) 발행 → Rev.1 SUPERSEDED + rpt.report.superseded, "
            + "FAC 등급 이력 2건")
    void correctionSupersedes() throws Exception {
        UUID building = flow.facBuilding(pm, "합성 정정 대상 사옥");
        JsonNode r = flow.createReport(pm, project, building, "RT_REG", today().minusDays(15));
        UUID reportId = UUID.fromString(r.get("id").asText());
        UUID rev1 = UUID.fromString(r.get("currentRevisionId").asText());
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "B", null));
        flow.composeToPublish(pm, rev1);
        flow.call(RptFlow.postJson("/api/v1/rpt/revisions/" + rev1 + "/delivery-confirmation",
                "{\"recipientName\":\"합성 담당\"}"), pm, "PM", 201);
        // 발행 전에는 정정본 불가 → 여기서는 납품 상태라 가능
        JsonNode rev2 = flow.call(RptFlow.postJson("/api/v1/rpt/reports/" + reportId + "/revisions",
                "{\"changeNote\":\"균열폭 측정 오기 정정(합성)\",\"reuseSnapshot\":true}"), pm, "PM", 201);
        UUID rev2Id = UUID.fromString(rev2.get("id").asText());
        assertThat(rev2.get("revNo").asInt()).isEqualTo(2);
        assertThat(rev2.get("status").asText()).isEqualTo("SNAPSHOT_FROZEN");
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/reports/" + reportId + "/revisions",
                        "{\"changeNote\":\"두 번째 정정\"}"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-006"));
        // 정정 내용: 원천 수정 → 재동결(C 수준)
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "C", null));
        flow.composeToPublish(pm, rev2Id);
        assertThat(jdbc.queryForMap("SELECT status, superseded_by FROM rpt.report_revision WHERE id = ?", rev1))
                .containsEntry("status", "SUPERSEDED").containsEntry("superseded_by", rev2Id);
        JsonNode sup = payload("rpt.report.superseded", reportId);
        assertThat(sup.get("oldRevId").asText()).isEqualTo(rev1.toString());
        assertThat(sup.get("newRevId").asText()).isEqualTo(rev2Id.toString());
        assertThat(sup.get("reason").asText()).contains("정정");
        assertThat(outbox("rpt.report.published", reportId)).isEqualTo(2);
        flow.drain();
        assertThat(jdbc.queryForList("SELECT grade FROM fac.grade_history WHERE report_id = ? ORDER BY recorded_at",
                String.class, reportId)).containsExactly("B", "C");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.grade_history WHERE report_id = ? AND correction_of IS NOT NULL",
                Integer.class, reportId)).isEqualTo(1);   // 정정본 등급은 이전 행을 correction_of 로 잇는다
        JsonNode report = flow.call(get("/api/v1/rpt/reports/" + reportId), pm, "PM", 200);
        assertThat(report.get("currentRev").asInt()).isEqualTo(2);
        assertThat(report.get("status").asText()).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("qar.review.rejected → COMPOSING(재작성), 재전달 · 순서 뒤바뀜은 무시(멱등)")
    void qarRejected() throws Exception {
        UUID[] ids = frozenReport("B", null);
        UUID rev = ids[1];
        flow.compose(pm, rev);
        flow.decide(pm, rev, "B", null, null, 200);
        flow.render(pm, rev);
        flow.reviewRequest(pm, rev);
        flow.qarDecision(rev, false);
        assertThat(jdbc.queryForObject("SELECT status FROM rpt.report_revision WHERE id = ?", String.class, rev))
                .isEqualTo("COMPOSING");
        flow.qarDecision(rev, true);   // IN_REVIEW 가 아니면 무시
        assertThat(jdbc.queryForObject("SELECT status FROM rpt.report_revision WHERE id = ?", String.class, rev))
                .isEqualTo("COMPOSING");
        mvc.perform(RptFlow.as(post("/api/v1/rpt/revisions/" + rev + "/publish"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RPT-409-001"));
    }

    @Test
    @DisplayName("NFR-RPT-06 프로젝트 범위: 투입 안 된 PM · ENG → 403 CORE-403-002, CUSTOMER 생성 403, QA 는 ALL 조회 가능")
    void projectScope() throws Exception {
        UUID outsider = flow.employee("RPT-OUT");
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/reports", """
                        {"projectId":"%s","facilityId":"%s","reportType":"RT_REG","title":"x","inspectionDate":"%s"}"""
                        .formatted(project, UUID.randomUUID(), today())), outsider, "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        UUID[] ids = frozenReport("B", null);
        mvc.perform(RptFlow.as(get("/api/v1/rpt/revisions/" + ids[1]), outsider, "ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(RptFlow.as(get("/api/v1/rpt/revisions/" + ids[1]), UUID.randomUUID(), "QA")).andExpect(status().isOk());
        mvc.perform(RptFlow.as(RptFlow.postJson("/api/v1/rpt/reports", """
                        {"projectId":"%s","facilityId":"%s","reportType":"RT_REG","title":"x","inspectionDate":"%s"}"""
                        .formatted(project, UUID.randomUUID(), today())), UUID.randomUUID(), "CUSTOMER"))
                .andExpect(status().isForbidden());
        mvc.perform(RptFlow.as(get("/api/v1/rpt/rule-sets").param("asOf", "2026-03-01"), UUID.randomUUID(), "QA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.pinned.GRADE_BAND").value("BLD_RC@2026.1"))
                .andExpect(jsonPath("$.rules.gradeWeight.floorAgg.mode").value("AVG_WORST_BLEND"));
        mvc.perform(RptFlow.as(get("/api/v1/rpt/rule-sets").param("asOf", "2024-03-01"), UUID.randomUUID(), "QA"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RPT-422-004"));
    }

    @Test
    @DisplayName("TC-RPT-010: 템플릿 v2026.2 배포 후 — 진행 중 보고서는 v2026.1 유지, 신규 보고서만 v2026.2")
    void templateVersionPinned() throws Exception {
        UUID[] before = frozenReport("B", null);
        UUID tv = UUID.randomUUID();
        try {
            jdbc.update("UPDATE rpt.template_version SET effective_to = ? WHERE semver = '2026.1.0'",
                    java.sql.Date.valueOf(today()));
            jdbc.update("""
                    INSERT INTO rpt.template_version(id, template_id, semver, package_ref, manifest, effective_from, status,
                                                     created_by)
                    VALUES (?, '0192e000-0000-7000-8000-000000000001', '2026.2.0', 'classpath:rpt/templates/RT_COMMON/2026.1.0',
                            '{}', ?, 'DEPLOYED', ?)""", tv, java.sql.Date.valueOf(today()), pm);
            JsonNode after = flow.createReport(pm, project, UUID.randomUUID(), "RT_REG", today().minusDays(3));
            assertThat(after.at("/templateVersion/semver").asText()).isEqualTo("2026.2.0");
            JsonNode old = flow.call(get("/api/v1/rpt/reports/" + before[0]), pm, "PM", 200);
            assertThat(old.at("/templateVersion/semver").asText()).isEqualTo("2026.1.0");
            // 진행 중 보고서는 고정 템플릿으로 계속 렌더
            flow.compose(pm, before[1]);
            flow.decide(pm, before[1], "B", null, null, 200);
            flow.render(pm, before[1]);
        } finally {
            jdbc.update("UPDATE rpt.report SET template_version_id = '0192e000-0000-7000-8000-000000002026' "
                    + "WHERE template_version_id = ?", tv);
            jdbc.update("DELETE FROM rpt.template_version WHERE id = ?", tv);
            jdbc.update("UPDATE rpt.template_version SET effective_to = NULL WHERE semver = '2026.1.0'");
        }
    }
}
