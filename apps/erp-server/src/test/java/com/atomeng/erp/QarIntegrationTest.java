package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.qar.application.AiReviewService;
import com.atomeng.erp.qar.application.CommentService;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.atomeng.erp.qar.domain.ai.CitationGuard;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.RptFlow;
import com.atomeng.erp.support.TestBeans.FakeAtomAiCore;
import com.atomeng.erp.support.TestBeans.FakeQarEvidence;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 07 QAR 통합 테스트 — 내장 PostgreSQL + 전체 컨텍스트. 보고서는 실제 RPT 흐름(RptFlow: 생성 → 동결 → compose → 확정 → 렌더 →
 * 검토 요청)으로 만들고, QAR 은 rpt::api 조회 · 이벤트로만 연결된다. AI 는 대역(FakeAtomAiCore — vLLM 미호출), 데이터는 합성값.
 * TC-QAR-001 · 002 · 003 · 005 · 006 · 009 · 011 · 012 · 014 · 015 (+ E2E: RPT 발행은 QAR 승인이 있어야 가능, 정정본 재검토).
 */
class QarIntegrationTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final String DEFAULT_OPINION_PHRASE = "상태평가 결과는 위 표와 같다";

    @Autowired
    OutboxRelay relay;
    @Autowired
    AiReviewService aiService;
    @Autowired
    CommentService commentService;
    @Autowired
    FakeAtomAiCore aiCore;
    @Autowired
    FakeQarEvidence qarEvidence;

    RptFlow flow;
    UUID pm;
    UUID qa1;
    UUID qa2;

    record Prepared(UUID project, UUID report, UUID rev) {}

    @BeforeEach
    void setUp() {
        flow = new RptFlow(mvc, jdbc, objectMapper, relay);
        aiCore.reset();
        qarEvidence.clear();
        clock.reset();
        pm = flow.employee("QAR-PM");
        qa1 = flow.employee("QAR-QA");
        qa2 = flow.employee("QAR-QA");
        jdbc.update("UPDATE qar.reviewer_profile SET active = false");
        jdbc.update("INSERT INTO qar.reviewer_profile(employee_id) VALUES (?), (?)", qa1, qa2);
    }

    // ------------------------------------------------------------------ helpers

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String role, int expect) throws Exception {
        return flow.call(b, actor, role, expect);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** RPT: 보고서 → 동결 → compose → 확정 → (종합 의견 수정) → 렌더 → 검토 요청(IN_REVIEW) */
    private Prepared prepare(String opinion, boolean renderAndRequest) throws Exception {
        UUID project = flow.project(pm);
        UUID building = flow.facBuilding(pm, "합성 QAR 동");
        JsonNode report = flow.createReport(pm, project, building, "RT_PRC", today().minusDays(3));
        UUID reportId = UUID.fromString(report.get("id").asText());
        UUID rev = UUID.fromString(report.get("currentRevisionId").asText());
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "C", null));
        JsonNode g = flow.compose(pm, rev);
        flow.decide(pm, rev, g.get("computedGrade").asText(), null, null, 200);
        if (opinion != null) {
            editOpinion(rev, opinion);
        }
        if (renderAndRequest) {
            flow.render(pm, rev);
            flow.reviewRequest(pm, rev);
        }
        return new Prepared(project, reportId, rev);
    }

    private JsonNode opinionSection(UUID rev) throws Exception {
        JsonNode sections = call(get("/api/v1/rpt/revisions/" + rev + "/sections"), pm, "PM", 200);
        for (JsonNode s : sections) {
            if (s.get("sectionKey").asText().equals("ch7.opinion")) {
                return s;
            }
        }
        throw new IllegalStateException("ch7.opinion 없음");
    }

    private void editOpinion(UUID rev, String text) throws Exception {
        JsonNode s = opinionSection(rev);
        String blockId = s.get("blocks").get(0).get("blockId").asText();
        call(json(put("/api/v1/rpt/revisions/" + rev + "/sections/ch7.opinion").header("If-Match",
                "\"v" + s.get("version").asLong() + "\""), objectMapper.writeValueAsString(Map.of("blocks",
                List.of(Map.of("blockId", blockId, "kind", "PARAGRAPH", "text", text))))), pm, "PM", 200);
    }

    private JsonNode createReview(UUID rev) throws Exception {
        return call(json(post("/api/v1/qar/reviews"), "{\"revisionId\":\"" + rev + "\"}"), pm, "PM", 201);
    }

    private JsonNode review(UUID id) throws Exception {
        return call(get("/api/v1/qar/reviews/" + id), pm, "PM", 200);
    }

    private String etag(UUID id) throws Exception {
        return mvc.perform(as(get("/api/v1/qar/reviews/" + id), pm, "PM")).andExpect(status().isOk()).andReturn()
                .getResponse().getHeader("ETag");
    }

    private JsonNode autocheck(UUID id) throws Exception {
        return call(post("/api/v1/qar/reviews/" + id + "/autocheck-runs"), pm, "PM", 201);
    }

    /** 체크리스트 미응답 문항을 PASS 로 채움 */
    private void answerMissing(UUID roundId, String stage, UUID actor, String role) throws Exception {
        JsonNode cl = call(get("/api/v1/qar/rounds/" + roundId + "/checklist"), actor, role, 200);
        List<String> missing = new ArrayList<>();
        cl.get("SELF".equals(stage) ? "missingSelf" : "missingQa").forEach(n -> missing.add(n.asText()));
        for (JsonNode item : cl.get("items")) {
            if (missing.contains(item.get("itemNo").asText())) {
                call(json(put("/api/v1/qar/rounds/" + roundId + "/checklist/" + item.get("itemId").asText()),
                        "{\"stage\":\"" + stage + "\",\"answer\":\"PASS\",\"note\":\"합성 확인\"}"), actor, role, 200);
            }
        }
    }

    private JsonNode submit(UUID id, int expect) throws Exception {
        return call(post("/api/v1/qar/reviews/" + id + "/submit").header("If-Match", etag(id)), pm, "PM", expect);
    }

    /** 생성 → 자동검사 → 자체 체크리스트 → 제출 → AI 작업 처리 → QA_REVIEWING */
    private JsonNode toQaReviewing(UUID rev) throws Exception {
        UUID id = UUID.fromString(createReview(rev).get("id").asText());
        autocheck(id);
        answerMissing(roundId(review(id)), "SELF", pm, "PM");
        submit(id, 200);
        aiService.runPending(10);
        JsonNode r = review(id);
        assertThat(r.get("status").asText()).isEqualTo("QA_REVIEWING");
        return r;
    }

    private static UUID roundId(JsonNode review) {
        return UUID.fromString(review.get("round").get("id").asText());
    }

    private static UUID assignee(JsonNode review) {
        for (JsonNode a : review.get("round").get("assignments")) {
            if (a.get("step").asText().equals("QA_REVIEW") && a.get("completedAt").isNull()) {
                return UUID.fromString(a.get("assigneeId").asText());
            }
        }
        return null;
    }

    private static List<String> violationCodes(JsonNode problem) {
        List<String> out = new ArrayList<>();
        problem.get("violations").forEach(v -> out.add(v.get("code").asText()));
        return out;
    }

    private String rptStatus(UUID rev) throws Exception {
        return call(get("/api/v1/rpt/revisions/" + rev), pm, "PM", 200).get("status").asText();
    }

    private UUID lastEvent(String type, UUID aggregate) {
        return jdbc.queryForObject("SELECT id FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ? "
                + "ORDER BY occurred_at DESC, id DESC LIMIT 1", UUID.class, type, aggregate);
    }

    private static AtomAiCorePort.BlockIn opinionBlock(AtomAiCorePort.Request r) {
        return r.sections().stream().filter(s -> s.key().equals("ch7.opinion")).findFirst().orElseThrow()
                .blocks().get(0);
    }

    // ------------------------------------------------------------------ E2E

    @Test
    @DisplayName("E2E-QAR-01: RPT 발행은 QAR 승인 전 409 → 자체검토 · 제출 · AI(인용 검증) · QA(코멘트 · 채택 · 해결) · 승인 → "
            + "qar.review.approved → RPT APPROVED → 발행 200, 정정본 → REOPEN_FOR_CORRECTION(APPROVED → SELF_CHECK)")
    void e2eApprovalGatesPublish() throws Exception {
        Prepared p = prepare(null, true);
        // RPT 발행은 QAR 승인 없이는 불가(IN_REVIEW)
        JsonNode denied = call(post("/api/v1/rpt/revisions/" + p.rev() + "/publish"), pm, "PM", 409);
        assertThat(denied.get("code").asText()).isEqualTo("RPT-409-001");

        JsonNode created = createReview(p.rev());
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(created.get("status").asText()).isEqualTo("SELF_CHECK");
        assertThat(created.get("workflow").asText()).isEqualTo("WF_PSD_STANDARD v1");
        UUID round1 = roundId(created);
        call(json(post("/api/v1/qar/reviews"), "{\"revisionId\":\"" + p.rev() + "\"}"), pm, "PM", 409);

        // 앵커 프록시: ETag = revisionId:pdfSha256, If-None-Match → 304
        MvcResult a = mvc.perform(as(get("/api/v1/qar/rounds/" + round1 + "/anchors"), pm, "PM"))
                .andExpect(status().isOk()).andReturn();
        String anchorEtag = a.getResponse().getHeader("ETag");
        JsonNode anchors = objectMapper.readTree(a.getResponse().getContentAsByteArray());
        assertThat(anchors.get("source").asText()).isEqualTo("ESTIMATED");
        assertThat(anchorEtag).isEqualTo("\"" + p.rev() + ":" + anchors.get("pdfSha256").asText() + "\"");
        mvc.perform(as(get("/api/v1/qar/rounds/" + round1 + "/anchors"), pm, "PM").header("If-None-Match", anchorEtag))
                .andExpect(status().isNotModified());

        JsonNode run = autocheck(id);
        assertThat(run.get("blocking").asInt()).isZero();
        assertThat(run.get("warning").asInt()).isZero();
        assertThat(run.get("ruleVersion").asText()).isEqualTo("2026.1");
        // 자체 체크리스트 미완료 → 409 사유 목록
        JsonNode incomplete = submit(id, 409);
        assertThat(incomplete.get("code").asText()).isEqualTo("QAR-409-002");
        assertThat(violationCodes(incomplete)).containsExactly("QAR_CHECKLIST_INCOMPLETE");
        assertThat(incomplete.get("violations").get(0).get("refs").get(0).asText()).isEqualTo("4.2");
        answerMissing(round1, "SELF", pm, "PM");

        // 제출 → AI_START(AUTO) 자동 → AI_REVIEWING, 라운드 고정
        aiCore.respond(req -> {
            AtomAiCorePort.BlockIn b = opinionBlock(req);
            return new AtomAiCorePort.Response("gemma-synthetic", List.of(
                    new CitationGuard.Draft("NUM_MISMATCH", "MAJOR", "본문 총수와 1F 기둥 물량을 함께 확인 필요",
                            List.of(new CitationGuard.Citation("report", b.id(), "결함 4건을 조사", null, null),
                                    new CitationGuard.Citation("data", null, null, "quantityTable[0].count", 2))),
                    new CitationGuard.Draft("NUM_MISMATCH", "MAJOR", "환각 인용",
                            List.of(new CitationGuard.Citation("report", b.id(), "결함 9건", null, null),
                                    new CitationGuard.Citation("data", null, null, "quantityTable[0].count", 2))),
                    new CitationGuard.Draft("LOGIC_GAP", "MINOR", "없는 블록",
                            List.of(new CitationGuard.Citation("report", UUID.randomUUID(), "결함", null, null)))));
        });
        JsonNode submitted = submit(id, 200);
        assertThat(submitted.get("status").asText()).isEqualTo("AI_REVIEWING");
        assertThat(submitted.get("round").get("ruleVersions").get("AUTOCHECK").asText()).isEqualTo("2026.1");
        assertThat(submitted.get("round").get("pdfSha256").asText()).isEqualTo(anchors.get("pdfSha256").asText());
        assertThat(lastEvent("qar.review.submitted", id)).isNotNull();

        assertThat(aiService.runPending(10).done()).isEqualTo(1);
        JsonNode inQa = review(id);
        assertThat(inQa.get("status").asText()).isEqualTo("QA_REVIEWING");
        assertThat(inQa.get("round").get("aiStatus").asText()).isEqualTo("DONE");
        UUID qa = assignee(inQa);
        assertThat(qa).isIn(qa1, qa2);   // PM 은 compose 실행자(섹션 편집 이력) → SoD 로 제외
        // TC-QAR-005: 인용 검증 실패분은 저장만(guard_passed=false), QA 에게는 1건만 노출
        assertThat(jdbc.queryForList("SELECT guard_reason FROM qar.ai_finding WHERE round_id = ? AND NOT guard_passed "
                + "ORDER BY guard_reason", String.class, round1)).containsExactly("QUOTE_NOT_FOUND", "UNKNOWN_BLOCK");
        JsonNode findings = call(get("/api/v1/qar/rounds/" + round1 + "/ai-findings"), qa, "QA", 200);
        assertThat(findings).hasSize(1);
        call(get("/api/v1/qar/rounds/" + round1 + "/ai-findings"), pm, "PM", 403);

        // TC-QAR-014: anchors.json 블록 원문 기준 TEXT_RANGE 코멘트
        JsonNode opinion = opinionSection(p.rev()).get("blocks").get(0);
        String text = opinion.get("text").asText();
        int start = text.indexOf(DEFAULT_OPINION_PHRASE);
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("type", "TEXT_RANGE");
        anchor.put("revisionId", p.rev().toString());
        anchor.put("blockId", opinion.get("blockId").asText());
        anchor.put("start", start);
        anchor.put("end", start + DEFAULT_OPINION_PHRASE.length());
        anchor.put("quote", DEFAULT_OPINION_PHRASE);
        JsonNode minor = call(json(post("/api/v1/qar/rounds/" + round1 + "/comments"), objectMapper.writeValueAsString(
                Map.of("severity", "MINOR", "issueCode", "QI-STYLE", "anchor", anchor, "body", "표 번호를 명시하세요"))),
                qa, "QA", 201);
        assertThat(minor.get("anchor").get("start").asInt()).isEqualTo(start);
        assertThat(minor.get("anchor").get("quote").asText()).isEqualTo(DEFAULT_OPINION_PHRASE);
        assertThat(minor.get("anchor").get("pdf").get("page").asInt()).isPositive();
        anchor.put("start", start + 1);
        JsonNode badAnchor = call(json(post("/api/v1/qar/rounds/" + round1 + "/comments"), objectMapper.writeValueAsString(
                Map.of("severity", "MINOR", "issueCode", "QI-STYLE", "anchor", anchor, "body", "어긋난 앵커"))), qa, "QA", 422);
        assertThat(badAnchor.get("code").asText()).isEqualTo("QAR-422-003");

        // AI 제안 채택 → MAJOR 코멘트, 기각 사유 없는 기각은 422
        UUID findingId = UUID.fromString(findings.get(0).get("id").asText());
        JsonNode adopted = call(json(post("/api/v1/qar/ai-findings/" + findingId + "/decision"), "{\"decision\":\"ADOPTED\"}"),
                qa, "QA", 200);
        UUID major = UUID.fromString(adopted.get("adoptedCommentId").asText());
        call(json(post("/api/v1/qar/ai-findings/" + findingId + "/decision"), "{\"decision\":\"DISMISSED\"}"), qa, "QA", 409);
        assertThat(lastEvent("qar.ai-finding.decided", findingId)).isNotNull();

        answerMissing(round1, "QA", qa, "QA");
        JsonNode blocked = call(json(post("/api/v1/qar/rounds/" + round1 + "/decision").header("If-Match", etag(id)),
                "{\"decision\":\"PASS\"}"), qa, "QA", 409);
        assertThat(violationCodes(blocked)).containsExactly("QAR_OPEN_SEVERE_COMMENTS");

        // BR-QAR-004: 답변 · 해결 요청은 작성 측, 해결 확인은 코멘트 작성자(QA)만
        call(json(post("/api/v1/qar/comments/" + major + "/replies"), "{\"action\":\"ANSWER\",\"body\":\"확인했습니다\"}"),
                pm, "PM", 201);
        call(json(post("/api/v1/qar/comments/" + major + "/replies"), "{\"action\":\"REQUEST_RESOLUTION\",\"body\":\"반영\"}"),
                pm, "PM", 201);
        JsonNode selfClose = call(json(post("/api/v1/qar/comments/" + major + "/replies"), "{\"action\":\"CONFIRM\"}"),
                pm, "PM", 403);
        assertThat(selfClose.get("code").asText()).isEqualTo("QAR-403-002");
        JsonNode resolved = call(json(post("/api/v1/qar/comments/" + major + "/replies"), "{\"action\":\"CONFIRM\"}"),
                qa, "QA", 201);
        assertThat(resolved.get("status").asText()).isEqualTo("RESOLVED");
        assertThat(resolved.get("replies")).hasSize(3);

        JsonNode passed = call(json(post("/api/v1/qar/rounds/" + round1 + "/decision").header("If-Match", etag(id)),
                "{\"decision\":\"PASS\"}"), qa, "QA", 200);
        assertThat(passed.get("status").asText()).isEqualTo("PENDING_APPROVAL");

        // 최종 승인: QA 는 권한 없음, TC-QAR-011 자격 무효 → 가드 실패, 그다음 승인
        call(post("/api/v1/qar/reviews/" + id + "/approve").header("If-Match", etag(id)), qa, "QA", 403);
        qarEvidence.markInvalid(pm);
        JsonNode invalid = call(post("/api/v1/qar/reviews/" + id + "/approve").header("If-Match", etag(id)), pm, "PM", 409);
        assertThat(violationCodes(invalid)).containsExactly("QAR_QUALIFICATION_INVALID");
        qarEvidence.clear();
        JsonNode approved = call(post("/api/v1/qar/reviews/" + id + "/approve").header("If-Match", etag(id)), pm, "PM", 200);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("rounds").get(0).get("outcome").asText()).isEqualTo("APPROVED");

        // 이벤트 전달 전에는 여전히 발행 불가 → Relay → RPT APPROVED → 발행
        call(post("/api/v1/rpt/revisions/" + p.rev() + "/publish"), pm, "PM", 409);
        UUID approvedEvent = lastEvent("qar.review.approved", id);
        JsonNode payload = objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event WHERE id = ?",
                String.class, approvedEvent));
        assertThat(payload.get("revisionId").asText()).isEqualTo(p.rev().toString());
        assertThat(payload.get("reviewId").asText()).isEqualTo(id.toString());
        flow.drainUntilPublished(approvedEvent);
        assertThat(rptStatus(p.rev())).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT review_id FROM rpt.report_revision WHERE id = ?", UUID.class, p.rev()))
                .isEqualTo(id);
        assertThat(flow.publish(pm, p.rev()).get("status").asText()).isEqualTo("PUBLISHED");

        // BR-QAR-010 REOPEN_FOR_CORRECTION: 정정본 없으면 가드 실패, 정정본(Rev.2) 생성 후 새 라운드
        JsonNode noCorrection = call(post("/api/v1/qar/reviews/" + id + "/reopen-for-correction").header("If-Match", etag(id)),
                pm, "PM", 409);
        assertThat(violationCodes(noCorrection)).containsExactly("QAR_NO_CORRECTION_REVISION");
        JsonNode rev2 = call(json(post("/api/v1/rpt/reports/" + p.report() + "/revisions"),
                "{\"changeNote\":\"합성 정정: 오탈자 수정\",\"reuseSnapshot\":true}"), pm, "PM", 201);
        JsonNode reopened = call(post("/api/v1/qar/reviews/" + id + "/reopen-for-correction").header("If-Match", etag(id)),
                pm, "PM", 200);
        assertThat(reopened.get("status").asText()).isEqualTo("SELF_CHECK");
        assertThat(reopened.get("currentRound").asInt()).isEqualTo(2);
        assertThat(reopened.get("round").get("revisionId").asText()).isEqualTo(rev2.get("id").asText());
        assertThat(reopened.get("round").get("revNo").asInt()).isEqualTo(2);
        assertThat(reopened.get("round").get("openedBy").asText()).isEqualTo("REOPEN_FOR_CORRECTION");
        assertThat(reopened.get("rounds").get(0).get("outcome").asText()).isEqualTo("APPROVED");
    }

    // ------------------------------------------------------------------ TC

    @Test
    @DisplayName("TC-QAR-001 · 003 · 009: 본문 \"1F 기둥 균열이 3개소\"(물량표 2) → AC-NUM-001 BLOCKING(앵커 · 원천), 제출 409 사유 목록, "
            + "BLOCKING waiver 422 · WARNING waiver PM+QA 2인 GRANTED")
    void tcQar001_003_009() throws Exception {
        String opinion = "점검 결과 1F 기둥 균열이 3개소 확인되었다. 구조는 완전히 안전하다.";
        Prepared p = prepare(opinion, true);
        UUID id = UUID.fromString(createReview(p.rev()).get("id").asText());
        JsonNode run = autocheck(id);
        assertThat(run.get("blocking").asInt()).isEqualTo(1);
        JsonNode blocking = null;
        JsonNode warning = null;
        for (JsonNode r : run.get("results")) {
            if (r.get("severity").asText().equals("BLOCKING")) {
                blocking = r;
            } else if (r.get("severity").asText().equals("WARNING")) {
                warning = r;
            }
        }
        assertThat(blocking.get("ruleId").asText()).isEqualTo("AC-NUM-001");
        String blockId = opinionSection(p.rev()).get("blocks").get(0).get("blockId").asText();
        assertThat(blocking.get("reportAnchor").get("blockId").asText()).isEqualTo(blockId);
        int s = blocking.get("reportAnchor").get("start").asInt();
        assertThat(opinion.substring(s, blocking.get("reportAnchor").get("end").asInt())).isEqualTo("3개소");
        assertThat(blocking.get("sourceRef").get("key").asText()).isEqualTo("B01/1F/COLUMN/CRACK");
        assertThat(blocking.get("expected").asText()).isEqualTo("2개소");
        assertThat(warning.get("ruleId").asText()).isEqualTo("AC-TXT-002");

        // TC-QAR-001: BLOCKING + 체크리스트 미완료 → 409, 가드 사유를 한 번에 모두
        JsonNode denied = submit(id, 409);
        assertThat(violationCodes(denied)).containsExactlyInAnyOrder("QAR_BLOCKING_EXISTS", "QAR_CHECKLIST_INCOMPLETE");
        assertThat(review(id).get("status").asText()).isEqualTo("SELF_CHECK");
        // 체크리스트 자동판정: 1.1(AC-NUM-001 연계) 은 자동 FAIL
        JsonNode cl = call(get("/api/v1/qar/rounds/" + roundId(review(id)) + "/checklist"), pm, "PM", 200);
        assertThat(cl.get("items").get(0).get("self").get("label").asText()).isEqualTo("자동 FAIL(사람 확인 권장)");

        // TC-QAR-009: BLOCKING waiver 422, WARNING 은 PM · QA 사유 2인
        JsonNode w1 = call(json(post("/api/v1/qar/autocheck-results/" + blocking.get("id").asText() + "/waiver"),
                "{\"reason\":\"합성 사유\"}"), pm, "PM", 422);
        assertThat(w1.get("code").asText()).isEqualTo("QAR-422-002");
        String wid = warning.get("id").asText();
        assertThat(call(json(post("/api/v1/qar/autocheck-results/" + wid + "/waiver"), "{\"reason\":\"관용 표현 — 의미상 안전 보장 아님\"}"),
                pm, "PM", 200).get("status").asText()).isEqualTo("PENDING");
        call(json(post("/api/v1/qar/autocheck-results/" + wid + "/waiver"), "{\"reason\":\"중복\"}"), pm, "PM", 409);
        assertThat(call(json(post("/api/v1/qar/autocheck-results/" + wid + "/waiver"), "{\"reason\":\"QA 동의\"}"),
                qa1, "QA", 200).get("status").asText()).isEqualTo("GRANTED");
    }

    @Test
    @DisplayName("TC-QAR-002 · 012 + 반려 · 재제출: 작성자 QA 수동 배정 403 + 감사 로그, 같은 version PASS/REJECT 동시 → 1건만 성공(409), "
            + "qar.review.rejected → RPT COMPOSING, 수정 없으면 재제출 불가, 수정 후 라운드 2 · 미해결 코멘트 이월")
    void tcQar002_012_rejectResubmit() throws Exception {
        Prepared p = prepare(null, true);
        JsonNode r = toQaReviewing(p.rev());
        UUID id = UUID.fromString(r.get("id").asText());
        UUID round1 = roundId(r);

        // TC-QAR-002: 작성자(compose 실행자 = 섹션 편집 이력) 를 QA 로 배정 → 403 + 감사(롤백돼도 남음)
        JsonNode sod = call(json(post("/api/v1/qar/rounds/" + round1 + "/assignments"),
                "{\"stepCode\":\"QA_REVIEW\",\"assigneeId\":\"" + pm + "\"}"), qa1, "QA", 403);
        assertThat(sod.get("code").asText()).isEqualTo("QAR-403-001");
        assertThat(sod.get("reason").asText()).isEqualTo("QAR_SOD_VIOLATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'QAR' AND target_id = ? "
                + "AND after_json ->> 'violation' = 'QAR_SOD_VIOLATION'", Integer.class, round1)).isEqualTo(1);
        // 다른 QA 로 수동 재배정은 허용
        UUID other = assignee(r).equals(qa1) ? qa2 : qa1;
        call(json(post("/api/v1/qar/rounds/" + round1 + "/assignments"),
                "{\"stepCode\":\"QA_REVIEW\",\"assigneeId\":\"" + other + "\"}"), qa1, "QA", 201);
        assertThat(assignee(review(id))).isEqualTo(other);
        UUID qa = other;

        answerMissing(round1, "QA", qa, "QA");
        JsonNode opinion = opinionSection(p.rev()).get("blocks").get(0);
        int start = opinion.get("text").asText().indexOf(DEFAULT_OPINION_PHRASE);
        call(json(post("/api/v1/qar/rounds/" + round1 + "/comments"), objectMapper.writeValueAsString(Map.of(
                "severity", "MINOR", "issueCode", "QI-STYLE", "body", "표 번호 명시", "anchor", Map.of("type", "TEXT_RANGE",
                        "blockId", opinion.get("blockId").asText(), "start", start,
                        "end", start + DEFAULT_OPINION_PHRASE.length(), "quote", DEFAULT_OPINION_PHRASE)))), qa, "QA", 201);

        // TC-QAR-012: 같은 version 으로 PASS · REJECT 동시 → 1건 성공, 1건 409
        String tag = etag(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (String body : List.of("{\"decision\":\"PASS\"}", "{\"decision\":\"REJECT\",\"rejectSummary\":\"합성 반려\"}")) {
            fs.add(pool.submit(() -> {
                go.await();
                return mvc.perform(as(json(post("/api/v1/qar/rounds/" + round1 + "/decision").header("If-Match", tag), body),
                        qa, "QA")).andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : fs) {
            codes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        if (review(id).get("status").asText().equals("PENDING_APPROVAL")) {
            // PASS 가 이겼으면 책임기술자가 승인 단계에서 반려
            call(json(post("/api/v1/qar/rounds/" + round1 + "/decision").header("If-Match", etag(id)),
                    "{\"decision\":\"REJECT\",\"rejectSummary\":\"합성 반려(승인 단계)\"}"), pm, "PM", 200);
        }
        JsonNode rejected = review(id);
        assertThat(rejected.get("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.get("round").get("outcome").asText()).isEqualTo("REJECTED");
        flow.drainUntilPublished(lastEvent("qar.review.rejected", id));
        assertThat(rptStatus(p.rev())).isEqualTo("COMPOSING");

        // 수정 없이 재제출 준비 → 가드 실패, 수정 · 재렌더 · 검토 요청 후 라운드 2 + 미해결 MINOR 이월
        JsonNode same = call(post("/api/v1/qar/reviews/" + id + "/resubmit").header("If-Match", etag(id)), pm, "PM", 409);
        assertThat(violationCodes(same)).containsExactly("QAR_REVISION_NOT_CHANGED");
        editOpinion(p.rev(), "결함 4건을 조사하였으며 " + DEFAULT_OPINION_PHRASE + "(표 6-1). 보수 우선순위는 낮다.");
        flow.render(pm, p.rev());
        flow.reviewRequest(pm, p.rev());
        JsonNode round2 = call(post("/api/v1/qar/reviews/" + id + "/resubmit").header("If-Match", etag(id)), pm, "PM", 200);
        assertThat(round2.get("status").asText()).isEqualTo("SELF_CHECK");
        assertThat(round2.get("currentRound").asInt()).isEqualTo(2);
        JsonNode carried = call(get("/api/v1/qar/rounds/" + roundId(round2) + "/comments"), pm, "PM", 200);
        assertThat(carried).hasSize(1);
        assertThat(carried.get(0).get("originRoundNo").asInt()).isEqualTo(1);
        assertThat(carried.get(0).get("anchorStatus").asText()).isIn("OK", "REMAPPED");
        assertThat(carried.get(0).get("anchor").get("quote").asText()).isEqualTo(DEFAULT_OPINION_PHRASE);
    }

    @Test
    @DisplayName("TC-QAR-006: AI 응답 시간 초과 → 작업 TIMEOUT, AI_TIMEOUT 으로 QA_REVIEWING + \"AI 검토 미수행\" 배지, QA 배정 정상")
    void tcQar006() throws Exception {
        aiCore.respond(req -> {
            throw new AtomAiCorePort.AiTimeoutException("합성 지연 11분", null);
        });
        Prepared p = prepare(null, true);
        JsonNode r = toQaReviewing(p.rev());
        assertThat(r.get("round").get("aiStatus").asText()).isEqualTo("SKIPPED");
        assertThat(r.get("round").get("aiSkipReason").asText()).isEqualTo("TIMEOUT");
        assertThat(r.get("round").get("aiBadge").asText()).isEqualTo("AI 검토 미수행");
        assertThat(assignee(r)).isIn(qa1, qa2);
        assertThat(jdbc.queryForObject("SELECT status FROM qar.ai_review_job WHERE round_id = ?", String.class, roundId(r)))
                .isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("TC-QAR-015: AI 작업 deadline_at 경과(워커 정지) → 스케줄러가 TIMEOUT + AI_TIMEOUT 명령으로 QA_REVIEWING, 늦은 워커는 무시")
    void tcQar015() throws Exception {
        Prepared p = prepare(null, true);
        UUID id = UUID.fromString(createReview(p.rev()).get("id").asText());
        autocheck(id);
        answerMissing(roundId(review(id)), "SELF", pm, "PM");
        submit(id, 200);
        assertThat(review(id).get("status").asText()).isEqualTo("AI_REVIEWING");
        UUID round = roundId(review(id));
        assertThat(aiService.scanTimeouts()).isZero();   // 아직 마감 전
        clock.advance(Duration.ofMinutes(11));
        assertThat(aiService.scanTimeouts()).isEqualTo(1);
        Map<String, Object> job = jdbc.queryForMap("SELECT status, error FROM qar.ai_review_job WHERE round_id = ?", round);
        assertThat(job).containsEntry("status", "TIMEOUT").containsEntry("error", "DEADLINE_EXCEEDED");
        JsonNode r = review(id);
        assertThat(r.get("status").asText()).isEqualTo("QA_REVIEWING");
        assertThat(r.get("round").get("aiSkipReason").asText()).isEqualTo("TIMEOUT");
        assertThat(aiService.runPending(10).processed()).isZero();
        assertThat(aiCore.calls()).isZero();
    }

    @Test
    @DisplayName("TC-QAR-014(폴백): anchors.json 없는 리비전(렌더 전) — 앵커 API 는 SECTION 목록, TEXT_RANGE 코멘트 앵커는 SECTION 으로 폴백")
    void tcQar014Fallback() throws Exception {
        Prepared p = prepare(null, false);
        JsonNode created = createReview(p.rev());
        UUID round = roundId(created);
        JsonNode anchors = call(get("/api/v1/qar/rounds/" + round + "/anchors"), pm, "PM", 200);
        assertThat(anchors.get("source").asText()).isEqualTo("SECTION_FALLBACK");
        assertThat(anchors.get("anchors")).isNotEmpty();
        assertThat(anchors.get("anchors").get(0).get("type").asText()).isEqualTo("SECTION");
        JsonNode opinion = opinionSection(p.rev()).get("blocks").get(0);
        Map<String, Object> resolved = commentService.resolveAnchor(p.rev(), Map.of("type", "TEXT_RANGE",
                "blockId", opinion.get("blockId").asText(), "quote", DEFAULT_OPINION_PHRASE));
        assertThat(resolved).containsEntry("type", "SECTION").containsEntry("sectionKey", "ch7.opinion")
                .containsEntry("fallback", "NO_ANCHORS_JSON");
        // 렌더 전 제출 → RPT 잠금 · 렌더 가드
        autocheck(UUID.fromString(created.get("id").asText()));
        JsonNode denied = submit(UUID.fromString(created.get("id").asText()), 409);
        assertThat(violationCodes(denied)).contains("QAR_REVISION_NOT_LOCKED");
    }
}
