package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.application.AsyncJobProcessor;
import com.atomeng.erp.aip.application.InferenceAuditService;
import com.atomeng.erp.aip.application.JobExecutor;
import com.atomeng.erp.aip.application.JobService;
import com.atomeng.erp.aip.application.ModelRegistryService;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.atomeng.erp.qar.adapter.out.ai.AipAtomAiCoreAdapter;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.atomeng.erp.support.AipContract;
import com.atomeng.erp.support.AipSamples;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.RptFlow;
import com.atomeng.erp.support.TestBeans.FakeAipWorker;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 11 AIP 통합 테스트 — 내장 PostgreSQL + 전체 컨텍스트. Python 워커는 대역(FakeAipWorker — 실제 워커 · vLLM 미호출)이고,
 * 대역이 받은 요청 본문은 기록된 워커 계약(OpenAPI)으로 검증한다. 데이터는 전부 합성.
 * TC-AIP-001 · 002 · 017(누락 탐지) · 020 + NFR-AIP-03(워커 비활성) · 재시도/DEAD · 마감 · 우선순위 · 제안 생성 · 승격 · RPT/QAR 동기 호출.
 */
class AipIntegrationTest extends IntegrationTestBase {

    static final String PNG_B64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    @Autowired
    FakeAipWorker worker;
    @Autowired
    JobService jobs;
    @Autowired
    AsyncJobProcessor processor;
    @Autowired
    InferenceAuditService audit;
    @Autowired
    ModelRegistryService registry;
    @Autowired
    AipJobApi aipApi;
    @Autowired
    OutboxRelay relay;

    RptFlow flow;
    AipContract contract;

    @BeforeEach
    void setUp() throws Exception {
        worker.reset();
        flow = new RptFlow(mvc, jdbc, objectMapper, relay);
        contract = AipContract.load();
        // 다른 시험이 남긴 대기 Job 이 디큐 순서를 흔들지 않게 정리(시험 위생)
        jdbc.update("UPDATE aip.job SET status = 'CANCELLED' WHERE status IN ('QUEUED','RUNNING','FAILED','PREEMPTED')");
    }

    private JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String role, int expect) throws Exception {
        return flow.call(b, actor, role, expect);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void assertWorkerRequestsMatchContract() {
        for (Map.Entry<String, JsonNode> e : worker.requests()) {
            assertThat(contract.request(e.getKey(), e.getValue())).as("워커 요청 계약 " + e.getKey() + " " + e.getValue())
                    .isEmpty();
        }
    }

    /** 합성 PNG 원본의 SHA-256 */
    static final String PNG_SHA = com.atomeng.common.evidence.HashChain.sha256(
            new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(PNG_B64)));

    @Autowired
    com.atomeng.common.evidence.ObjectStorage storage;

    /**
     * 보안 검토 AIP-03: 입력 첨부는 그 프로젝트의 실제 첨부여야 하고(소유자 해석 = FLD 조사 회차 → 프로젝트), 워커는 저장 원본을
     * 분석한다 — 합성 프로젝트 회차에 합성 PNG 원본을 저장하고 첨부 행을 만든다.
     */
    private UUID attachment(UUID pm, UUID project) throws Exception {
        UUID building = flow.facBuilding(pm, "합성 AIP 건물", project);
        JsonNode round = call(json(post("/api/v1/fld/rounds"), """
                {"projectId":"%s","buildingId":"%s","surveyType":"PRECISE_INSPECTION"}""".formatted(project, building)),
                pm, "PM", 201);
        UUID id = UUID.randomUUID();
        String key = "aip-it/" + id + ".png";
        storage.put(com.atomeng.common.evidence.Buckets.FIELD_ORIGINAL, key,
                new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(PNG_B64)), "image/png");
        jdbc.update("""
                INSERT INTO core.attachment(id, bucket, object_key, file_name, mime_type, size_bytes, sha256, chain_hash,
                       owner_module, owner_type, owner_id, chain_scope, created_by, captured_at)
                VALUES (?, ?, ?, 'synthetic.png', 'image/png', 68, ?, ?, 'FLD', 'PHOTO', ?, ?, ?, now())""",
                id, com.atomeng.common.evidence.Buckets.FIELD_ORIGINAL, key, PNG_SHA, "d".repeat(64), UUID.randomUUID(),
                UUID.fromString(round.get("id").asText()), pm);
        return id;
    }

    private JsonNode outbox(String type, UUID aggregate) throws Exception {
        List<String> rows = jdbc.queryForList("SELECT payload::text FROM core.outbox_event WHERE event_type = ? "
                + "AND aggregate_id = ? ORDER BY occurred_at DESC", String.class, type, aggregate);
        return rows.isEmpty() ? null : objectMapper.readTree(rows.get(0));
    }

    private UUID createJob(UUID actor, String role, String body, String key, int expect) throws Exception {
        MockHttpServletRequestBuilder b = json(post("/api/v1/aip/jobs"), body);
        if (key != null) {
            b = b.header("Idempotency-Key", key);
        }
        JsonNode r = call(b, actor, role, expect);
        return expect == 202 ? UUID.fromString(r.get("id").asText()) : null;
    }

    @Test
    @DisplayName("보안 검토 AIP-03: 다른 프로젝트 첨부 · 기록과 다른 sha · 프로젝트 없는 사진 작업 · 클라이언트 imageB64/prompt/labels/model → 422, "
            + "Job 조회는 투입 인원만(해석기)")
    void aip03AttachmentMustBelongToProject() throws Exception {
        UUID pm = flow.employee("AIP03-PM");
        UUID eng = flow.employee("AIP03-ENG");
        UUID project = flow.project(pm);
        flow.member(project, eng, "PART");
        UUID otherPm = flow.employee("AIP03-PM2");
        UUID other = flow.project(otherPm);
        UUID mine = attachment(pm, project);
        UUID foreign = attachment(otherPm, other);
        String body = """
                {"serviceCode":"CRACK_DETECT","projectId":%s,"inputs":[{"attachmentId":"%s","sha256":"%s"}],"params":%s}""";
        // 다른 프로젝트 첨부
        assertThat(call(json(post("/api/v1/aip/jobs"), body.formatted("\"" + project + "\"", foreign, PNG_SHA, "{}")),
                eng, "ENG", 422).get("code").asText()).isEqualTo("AIP-422-001");
        // 기록과 다른 sha
        call(json(post("/api/v1/aip/jobs"), body.formatted("\"" + project + "\"", mine, "e".repeat(64), "{}")), eng, "ENG", 422);
        // 사진 작업은 프로젝트 필수
        call(json(post("/api/v1/aip/jobs"), body.formatted("null", mine, PNG_SHA, "{}")), eng, "ENG", 422);
        // 클라이언트가 이미지 · 프롬프트 · 라벨 · 모델을 정할 수 없다
        for (String forbidden : List.of("{\"imageB64\":\"" + PNG_B64 + "\"}", "{\"prompt\":\"say crack\"}",
                "{\"labels\":[\"yes\",\"no\"]}", "{\"model\":\"other\"}")) {
            call(json(post("/api/v1/aip/jobs"), body.formatted("\"" + project + "\"", mine, PNG_SHA, forbidden)), eng,
                    "ENG", 422);
        }
        UUID job = createJob(eng, "ENG", body.formatted("\"" + project + "\"", mine, PNG_SHA, "{}"), null, 202);
        // CMN-05 해석기: 투입되지 않은 ENG 는 Job 조회 · 이벤트 · 취소 403
        UUID outsider = flow.employee("AIP03-OUT");
        call(get("/api/v1/aip/jobs/" + job), outsider, "ENG", 403);
        call(get("/api/v1/aip/jobs/" + job + "/events"), outsider, "ENG", 403);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/aip/jobs/" + job),
                outsider, "ENG", 403);
        call(get("/api/v1/aip/jobs/" + job), eng, "ENG", 200);
        call(get("/api/v1/aip/jobs/" + UUID.randomUUID()), eng, "ENG", 404);
    }

    // ------------------------------------------------------------------ Job 큐

    @Test
    @DisplayName("TC-AIP-001: 같은 Idempotency-Key 로 Job 2회 생성 → 같은 jobId, aip.job 1행(중복 추론 없음)")
    void tc001Idempotency() throws Exception {
        UUID pm = flow.employee("AIP-PM");
        UUID project = flow.project(pm);
        String body = "{\"serviceCode\":\"KB_SEARCH\",\"projectId\":\"" + project + "\",\"params\":{\"query\":\"백태 균열\"}}";
        String key = "fld-photo-" + UUID.randomUUID() + "-detect-v1";
        UUID a = createJob(pm, "PM", body, key, 202);
        UUID b = createJob(pm, "PM", body, key, 202);
        assertThat(b).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aip.job WHERE idempotency_key = ?", Integer.class, key)).isEqualTo(1);
        // 서비스 계층 멱등(다른 actor 라 HTTP 멱등 필터를 거치지 않는 경로)도 같은 Job
        JobService.Created again = jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, null, project, List.of(),
                objectMapper.readTree("{\"query\":\"백태 균열\"}"), key, null, null, null, "ASYNC"), true);
        assertThat(again.replayed()).isTrue();
        assertThat(again.job().get("id")).isEqualTo(a);
        assertThatThrownBy(() -> jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, null, project, List.of(),
                objectMapper.readTree("{\"query\":\"다른 질의\"}"), key, null, null, null, "ASYNC"), true))
                .hasMessageContaining("다른 입력");
        assertThat(outbox("aip.job.queued", a)).isNotNull();
        // 권한: LABELER 는 Job 생성 불가, 담당 외 ENG 는 프로젝트 범위 밖
        createJob(UUID.randomUUID(), "LABELER", body, null, 403);
        createJob(flow.employee("AIP-ENG-OUT"), "ENG", body, null, 403);
    }

    @Test
    @DisplayName("SEC-2026-10-19: 프로젝트 없는 Job 은 요청자 본인 · ALL 범위만 조회/취소, 남의 Idempotency-Key 는 409 · jobId 비노출")
    void secProjectlessJobOwnerOnlyAndKeyIsolation() throws Exception {
        UUID engA = flow.employee("AIP-SEC-A");
        UUID engB = flow.employee("AIP-SEC-B");
        String body = "{\"serviceCode\":\"KB_SEARCH\",\"params\":{\"query\":\"합성 질의\"}}";
        String key = "sec-" + UUID.randomUUID();
        UUID job = createJob(engA, "ENG", body, key, 202);
        call(get("/api/v1/aip/jobs/" + job), engA, "ENG", 200);
        call(get("/api/v1/aip/jobs/" + job), engB, "ENG", 403);
        call(get("/api/v1/aip/jobs/" + job + "/events"), engB, "ENG", 403);
        call(delete("/api/v1/aip/jobs/" + job), engB, "ENG", 403);
        call(get("/api/v1/aip/jobs/" + job), UUID.randomUUID(), "QA", 200);   // ALL 범위
        // 다른 사용자가 같은 키 · 같은 본문 → 남의 Job 을 재반환하지 않고 409, 응답에 jobId 없음
        JsonNode conflict = call(json(post("/api/v1/aip/jobs"), body).header("Idempotency-Key", key), engB, "ENG", 409);
        assertThat(conflict.get("code").asText()).isEqualTo("AIP-409-003");
        assertThat(conflict.toString()).doesNotContain(job.toString());
        // 본인 재전송은 여전히 같은 Job
        assertThat(createJob(engA, "ENG", body, key, 202)).isEqualTo(job);
    }

    @Test
    @DisplayName("NFR-AIP-03: 워커 비활성(미설정)이어도 Job 생성 202 · QUEUED 대기, 동기 호출은 DISABLED")
    void workerDisabledKeepsErpWorking() throws Exception {
        worker.setEnabled(false);
        UUID pm = flow.employee("AIP-PM");
        UUID project = flow.project(pm);
        UUID id = createJob(pm, "PM", "{\"serviceCode\":\"KB_SEARCH\",\"projectId\":\"" + project
                + "\",\"params\":{\"query\":\"균열\"}}", null, 202);
        assertThat(processor.runNext()).isEmpty();
        JsonNode j = call(get("/api/v1/aip/jobs/" + id), pm, "PM", 200);
        assertThat(j.get("status").asText()).isEqualTo("QUEUED");
        assertThatThrownBy(() -> aipApi.runSync(new AipJobApi.SyncRequest(AipServiceCode.KB_SEARCH, "TST", null, null,
                objectMapper.readTree("{\"query\":\"q\"}"), Duration.ofSeconds(5))))
                .isInstanceOf(AipJobApi.AipDisabledException.class).hasMessageStartingWith("DISABLED");
        assertThat(worker.requests()).isEmpty();
    }

    @Test
    @DisplayName("TC-AIP-002: RUNNING 중 워커 강제 종료 → lease 만료 후 QUEUED 복귀 · attempts 증가 · 결과 1회만 저장")
    void tc002LeaseExpiry() throws Exception {
        UUID id = (UUID) jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, 1, null, List.of(),
                objectMapper.readTree("{\"query\":\"백태\"}"), null, null, null, null, "ASYNC"), true).job().get("id");
        Map<String, Object> taken = jobs.dequeue("worker-A", List.of("KB_SEARCH"), 4500, 120).orElseThrow();
        assertThat(taken.get("id")).isEqualTo(id);
        // worker-A 가 kill -9 로 죽었다 — lease 만료 모사
        jdbc.update("UPDATE aip.job SET lease_until = now() - interval '1 second' WHERE id = ?", id);
        JobService.ReapSummary s = jobs.reap();
        assertThat(s.requeued()).isEqualTo(1);
        Map<String, Object> q = jobs.get(id);
        assertThat(q.get("status")).isEqualTo("QUEUED");
        assertThat(((Number) q.get("attempts")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT not_before > now() FROM aip.job WHERE id = ?", Boolean.class, id)).isTrue();
        jdbc.update("UPDATE aip.job SET not_before = now() WHERE id = ?", id);   // 백오프(2^1초) 경과 모사
        assertThat(processor.runNext("worker-B")).contains(id);
        Map<String, Object> done = jobs.get(id);
        assertThat(done.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) done.get("attempts")).intValue()).isEqualTo(2);
        // 늦게 끝난 worker-A 의 결과는 버린다(조건부 UPDATE)
        boolean late = jobs.complete(id, "worker-A", new JobExecutor.Execution(objectMapper.readTree("{\"late\":true}"),
                null, null, null, 1));
        assertThat(late).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aip.inference_audit WHERE job_id = ?", Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aip.job_event WHERE job_id = ? AND payload->>'to' = 'SUCCEEDED'",
                Integer.class, id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT result_ref->>'late' FROM aip.job WHERE id = ?", String.class, id)).isNull();
        assertWorkerRequestsMatchContract();
    }

    @Test
    @DisplayName("A6.1 재시도: 워커 장애 → FAILED → QUEUED(백오프) → 시도 소진 DEAD + aip.job.failed(DEAD)")
    void retryThenDead() throws Exception {
        worker.on(AipWorkerPort.KB_SEARCH, b -> {
            throw new AipJobApi.AipUnavailableException("synthetic worker down", null);
        });
        UUID id = (UUID) jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, 2, null, List.of(),
                objectMapper.readTree("{\"query\":\"q\"}"), null, null, 2, null, "ASYNC"), true).job().get("id");
        processor.runNext();
        Map<String, Object> j1 = jobs.get(id);
        assertThat(j1.get("status")).isEqualTo("QUEUED");
        assertThat(objectMapper.readTree(j1.get("error").toString()).get("code").asText()).isEqualTo("WORKER_UNAVAILABLE");
        jdbc.update("UPDATE aip.job SET not_before = now() WHERE id = ?", id);
        processor.runNext();
        assertThat(jobs.get(id).get("status")).isEqualTo("DEAD");
        assertThat(jdbc.queryForList("SELECT payload->>'to' FROM aip.job_event WHERE job_id = ? AND kind = 'STATE' ORDER BY id",
                String.class, id)).containsExactly("QUEUED", "RUNNING", "FAILED", "QUEUED", "RUNNING", "FAILED", "DEAD");
        JsonNode ev = outbox("aip.job.failed", id);
        assertThat(ev.get("status").asText()).isEqualTo("DEAD");
        assertThat(ev.get("reason").asText()).isEqualTo("WORKER_UNAVAILABLE");
    }

    @Test
    @DisplayName("마감 · 우선순위: 마감 지난 QUEUED → DEAD(DEADLINE_EXCEEDED), 디큐는 P1 먼저 · 취소는 QUEUED 만")
    void deadlinePriorityCancel() throws Exception {
        UUID late = (UUID) jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, 1, null, List.of(),
                objectMapper.readTree("{\"query\":\"a\"}"), null, Duration.ofSeconds(60), null, null, "ASYNC"), true)
                .job().get("id");
        jdbc.update("UPDATE aip.job SET deadline_at = now() - interval '1 second' WHERE id = ?", late);
        assertThat(jobs.reap().deadlineExceeded()).isEqualTo(1);
        assertThat(jobs.get(late).get("status")).isEqualTo("DEAD");
        assertThat(outbox("aip.job.failed", late).get("reason").asText()).isEqualTo("DEADLINE_EXCEEDED");

        UUID p3 = (UUID) jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, 3, null, List.of(),
                objectMapper.readTree("{\"query\":\"b\"}"), null, null, null, null, "ASYNC"), true).job().get("id");
        UUID p1 = (UUID) jobs.create(new JobService.CreateCommand(AipServiceCode.KB_SEARCH, 1, null, List.of(),
                objectMapper.readTree("{\"query\":\"c\"}"), null, null, null, null, "ASYNC"), true).job().get("id");
        assertThat(jobs.queuePosition(p1)).isEqualTo(1);
        assertThat(jobs.dequeue("w", List.of("KB_SEARCH"), 4500, 60).orElseThrow().get("id")).isEqualTo(p1);
        UUID sys = UUID.randomUUID();
        // 프로젝트 없는 Job 취소는 요청자 본인만(SEC-2026-10-19) — 시험 Job 의 요청자를 sys 로 둔다
        jdbc.update("UPDATE aip.job SET requested_by = ? WHERE id = ?", sys, p3);
        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/aip/jobs/" + p3),
                UUID.randomUUID(), "PM", 403);
        JsonNode c = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/aip/jobs/" + p3),
                sys, "PM", 200);
        assertThat(c.get("status").asText()).isEqualTo("CANCELLED");
        JsonNode again = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/aip/jobs/" + p3),
                sys, "PM", 409);
        assertThat(again.get("code").asText()).isEqualTo("AIP-409-001");
    }

    // ------------------------------------------------------------------ 제안 · HITL · 데이터셋 (TC-AIP-020)

    @Test
    @DisplayName("TC-AIP-020: 동의 · 블러 충족 제안 MODIFIED → 결정 직후 false + 라벨 큐, 검수 승인 → 커밋 후 true / 동의 없는 프로젝트는 큐 미등록")
    void tc020TrainingEligibleTwoStage() throws Exception {
        UUID pm = flow.employee("AIP-PM");
        UUID eng = flow.employee("AIP-ENG");
        UUID project = flow.project(pm);
        flow.member(project, eng, "PART");
        UUID sys = UUID.randomUUID();
        UUID photo = attachment(pm, project);
        call(json(put("/api/v1/aip/projects/" + project + "/consent"), "{\"trainingConsent\":true,\"source\":\"SAL_CONTRACT\"}"),
                sys, "SYSADMIN", 200);
        call(json(put("/api/v1/aip/attachments/" + photo + "/privacy"), "{\"blurDone\":true,\"facesBlurred\":1}"),
                sys, "SYSADMIN", 200);

        UUID job = createJob(eng, "ENG", """
                {"serviceCode":"CRACK_DETECT","priority":1,"projectId":"%s","inputs":[{"attachmentId":"%s","sha256":"%s"}],
                 "params":{"chain":["CRACK_MEASURE"],"minConfidence":0.35,
                           "scale":{"source":"ARUCO","markerSizeMm":50,"dictionary":"DICT_4X4_50"}}}"""
                .formatted(project, photo, PNG_SHA), "fld-photo-" + photo + "-detect-v1", 202);
        assertThat(processor.runNext()).contains(job);
        JsonNode j = call(get("/api/v1/aip/jobs/" + job), eng, "ENG", 200);
        assertThat(j.get("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(j.get("result").get("detected").asBoolean()).isTrue();
        assertWorkerRequestsMatchContract();
        assertThat(worker.calls(AipWorkerPort.CRACK_SCORE)).isEqualTo(1);
        assertThat(worker.requests().get(0).getValue().get("model").asText()).isEqualTo("crack");   // 레지스트리 production 별칭
        // AIP-03: 워커가 받은 이미지는 서버가 첨부에서 읽은 원본
        assertThat(worker.requests().get(0).getValue().get("imageB64").asText()).isEqualTo(PNG_B64);

        // 감사: 모델 · 프롬프트 버전 · 입력 해시 · 이미지 본문 제외(해시만)
        Map<String, Object> a = jdbc.queryForMap("SELECT * FROM aip.inference_audit WHERE job_id = ?", job);
        assertThat(a.get("model_name")).isEqualTo("crack");
        assertThat(a.get("prompt_key")).isEqualTo("vision.crack.score");
        assertThat(a.get("adapter")).isEqualTo("crack");
        assertThat(a.get("params").toString()).doesNotContain(PNG_B64);
        assertThat(jobs.get(job).get("input_refs").toString()).contains(PNG_SHA);
        // 제안: 측정 성분 1건 → 폭 0.300mm(ARUCO) · 경계 플래그 · SUGGESTED, aip.inference.completed 에 suggestionIds
        JsonNode sugg = call(get("/api/v1/aip/photos/" + photo + "/suggestions"), eng, "ENG", 200);
        assertThat(sugg).hasSize(1);
        JsonNode s0 = sugg.get(0);
        assertThat(s0.get("status").asText()).isEqualTo("SUGGESTED");
        assertThat(s0.get("width_max_mm").decimalValue()).isEqualByComparingTo("0.300");
        assertThat(s0.get("scale_source").asText()).isEqualTo("ARUCO");
        assertThat(s0.get("flags").toString()).contains("NEAR_GRADE_BOUNDARY_0_3MM");
        UUID sid = UUID.fromString(s0.get("id").asText());
        assertThat(outbox("aip.inference.completed", job).get("suggestionIds").toString()).contains(sid.toString());

        JsonNode fb = call(json(post("/api/v1/aip/suggestions/" + sid + "/decision"),
                "{\"decision\":\"MODIFIED\",\"reasonCode\":\"WIDTH_OFF\",\"modified\":{\"widthMaxMm\":0.25}}"), eng, "ENG", 200);
        assertThat(fb.get("training_eligible").asBoolean()).isFalse();   // BR-AIP-012 ①
        assertThat(fb.get("label_queued").asBoolean()).isTrue();
        assertThat(fb.get("diff").toString()).contains("widthMaxMm").contains("0.3").contains("0.25");
        UUID task = UUID.fromString(fb.get("labelTaskId").asText());
        assertThat(outbox("aip.suggestion.decided", sid).get("decision").asText()).isEqualTo("MODIFIED");
        call(json(post("/api/v1/aip/suggestions/" + sid + "/decision"), "{\"decision\":\"ACCEPTED\"}"), eng, "ENG", 409);

        // 커밋 전에는 false 유지(검수 미완료 → 커밋 대상 아님)
        JsonNode empty = call(json(post("/api/v1/aip/datasets/crack-seg/commits"), "{\"tag\":\"v-pre-" + task.toString().substring(0, 8) + "\"}"),
                sys, "SYSADMIN", 200);
        assertThat(jdbc.queryForObject("SELECT training_eligible FROM aip.feedback WHERE suggestion_id = ?", Boolean.class, sid))
                .isFalse();
        assertThat(empty.get("count").asInt()).isGreaterThanOrEqualTo(0);
        call(json(post("/api/v1/aip/datasets/crack-seg/commits"), "{\"tag\":\"" + "x".repeat(41) + "\"}"), sys, "SYSADMIN", 422);
        call(json(post("/api/v1/aip/label-tasks/" + task + "/adjudication"), "{\"approved\":true,\"finalResult\":{\"class\":\"CRACK\"},"
                + "\"agreementIou\":0.82}"), sys, "QA", 200);
        JsonNode ds = call(json(post("/api/v1/aip/datasets/crack-seg/commits"), "{\"tag\":\"v-" + task + "\"}"), sys,
                "SYSADMIN", 200);
        assertThat(ds.get("count").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(ds.get("lakefs_commit").asText()).hasSize(64);
        assertThat(jdbc.queryForObject("SELECT training_eligible FROM aip.feedback WHERE suggestion_id = ?", Boolean.class, sid))
                .isTrue();   // BR-AIP-012 ②
        assertThat(outbox("aip.dataset.committed", UUID.fromString(ds.get("id").asText()))).isNotNull();

        // 동의 없는 프로젝트: 거절해도 라벨 큐 미등록
        UUID project2 = flow.project(pm);
        flow.member(project2, eng, "PART");
        UUID photo2 = attachment(pm, project2);
        call(json(put("/api/v1/aip/attachments/" + photo2 + "/privacy"), "{\"blurDone\":true}"), sys, "SYSADMIN", 200);
        UUID job2 = createJob(eng, "ENG", """
                {"serviceCode":"CRACK_DETECT","projectId":"%s","inputs":[{"attachmentId":"%s","sha256":"%s"}],
                 "params":{}}""".formatted(project2, photo2, PNG_SHA), null, 202);
        processor.runNext();
        assertThat(jobs.get(job2).get("status")).isEqualTo("SUCCEEDED");
        JsonNode s2 = call(get("/api/v1/aip/photos/" + photo2 + "/suggestions"), eng, "ENG", 200).get(0);
        assertThat(s2.get("width_max_mm").isNull()).isTrue();   // 측정 체인 없음 → 폭 없음(MEASURE_REQUIRED)
        assertThat(s2.get("flags").toString()).contains("MEASURE_REQUIRED");
        JsonNode fb2 = call(json(post("/api/v1/aip/suggestions/" + s2.get("id").asText() + "/decision"),
                "{\"decision\":\"REJECTED\",\"reasonCode\":\"FALSE_POSITIVE\"}"), eng, "ENG", 200);
        assertThat(fb2.get("label_queued").asBoolean()).isFalse();
        assertThat(fb2.get("labelTaskId").isNull()).isTrue();
        // 담당 외 기술자는 결정 불가(프로젝트 범위 밖)
        UUID other = flow.employee("AIP-ENG-OUT");
        call(json(post("/api/v1/aip/suggestions/" + sid + "/decision"), "{\"decision\":\"REJECTED\"}"), other, "ENG", 403);
    }

    // ------------------------------------------------------------------ 감사 · 레지스트리

    @Test
    @DisplayName("TC-AIP-017: 감사 누락 Job(성공했는데 감사 없음) → 누락 탐지 배치가 경보 기록(멱등) · 감사 조회 권한")
    void tc017AuditGapDetection() throws Exception {
        UUID ghost = jdbc.queryForObject("""
                INSERT INTO aip.job(service_code, status, requested_by, input_sha256, vram_budget_mb, result_ref, finished_at)
                VALUES ('KB_SEARCH', 'SUCCEEDED', ?, ?, 0, '{}'::jsonb, now() - interval '1 hour') RETURNING id""", UUID.class,
                UUID.randomUUID(), "f".repeat(64));
        assertThat(audit.detectGaps(Duration.ofMinutes(10))).contains(ghost);
        assertThat(audit.detectGaps(Duration.ofMinutes(10))).doesNotContain(ghost);   // 같은 Job 은 한 번만 경보
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aip.audit_gap_alert WHERE job_id = ?", Integer.class, ghost))
                .isEqualTo(1);
        assertThat(audit.ensurePartitions(3)).isZero();   // 현재월 + 3개월은 이미 있음
        call(get("/api/v1/aip/audit"), UUID.randomUUID(), "AUDITOR", 200);
        call(get("/api/v1/aip/audit"), UUID.randomUUID(), "ENG", 403);
    }

    @Test
    @DisplayName("A6.3 승격: TRAINED→EVALUATED→(QA 승인 필요)SHADOW→CANARY 10%→(EXEC 승인 필요)PRODUCTION, 이전 production ARCHIVED · aip.model.promoted")
    void modelPromotion() throws Exception {
        UUID old = jdbc.queryForObject("""
                INSERT INTO aip.model_version(name, version, artifact_uri, artifact_sha256, stage) VALUES
                ('hazard-lora', ?, 'mlflow:/models/hazard/1', ?, 'PRODUCTION') RETURNING id""", UUID.class,
                "0." + System.nanoTime() % 100000, "1".repeat(64));
        jdbc.update("INSERT INTO aip.deployment(service_code, alias, model_version_id, traffic_pct, active_from) "
                + "VALUES ('SITE_HAZARD', 'production', ?, 100, now() - interval '1 day')", old);
        UUID m = jdbc.queryForObject("""
                INSERT INTO aip.model_version(name, version, artifact_uri, artifact_sha256) VALUES
                ('hazard-lora', ?, 'mlflow:/models/hazard/2', ?) RETURNING id""", UUID.class,
                "1." + System.nanoTime() % 100000, "2".repeat(64));
        UUID ml = UUID.randomUUID();
        String base = "/api/v1/aip/models/" + m;
        call(json(post(base + "/promote"), "{\"targetStage\":\"SHADOW\",\"serviceCode\":\"SITE_HAZARD\"}"), ml, "SYSADMIN", 409);
        call(json(post(base + "/promote"), "{\"targetStage\":\"EVALUATED\"}"), ml, "SYSADMIN", 200);
        JsonNode denied = call(json(post(base + "/promote"), "{\"targetStage\":\"SHADOW\",\"serviceCode\":\"SITE_HAZARD\"}"),
                ml, "SYSADMIN", 409);
        assertThat(denied.get("code").asText()).isEqualTo("AIP-409-002");
        // SEC-2026-10-19: 본문 role 은 호출자의 실제 역할이어야 함(QA 가 EXEC 승인 기록 불가) · 기록된 승인은 덮어쓰기 불가
        call(json(post(base + "/approval"), "{\"role\":\"EXEC\"}"), UUID.randomUUID(), "QA", 403);
        assertThat(jdbc.queryForMap("SELECT exec_approved_by FROM aip.model_version WHERE id = ?", m)
                .get("exec_approved_by")).isNull();
        UUID qa = UUID.randomUUID();
        call(json(post(base + "/approval"), "{\"role\":\"QA\"}"), qa, "QA", 200);
        call(json(post(base + "/approval"), "{\"role\":\"QA\"}"), UUID.randomUUID(), "QA", 409);
        assertThat(jdbc.queryForObject("SELECT approved_by FROM aip.model_version WHERE id = ?", UUID.class, m)).isEqualTo(qa);
        call(json(post(base + "/promote"), "{\"targetStage\":\"SHADOW\",\"serviceCode\":\"SITE_HAZARD\"}"), ml, "SYSADMIN", 200);
        assertThat(registry.resolve("SITE_HAZARD", UUID.randomUUID()).shadowModel()).isEqualTo("hazard-lora");
        JsonNode canary = call(json(post(base + "/promote"), "{\"targetStage\":\"CANARY\",\"serviceCode\":\"SITE_HAZARD\","
                + "\"trafficPct\":10}"), ml, "SYSADMIN", 200);
        assertThat(canary.get("trafficPct").asInt()).isEqualTo(10);
        long canaryHits = java.util.stream.IntStream.range(0, 400).filter(i -> "canary".equals(
                registry.resolve("SITE_HAZARD", UUID.nameUUIDFromBytes(("s" + i).getBytes())).alias())).count();
        assertThat(canaryHits).isBetween(15L, 80L);
        call(json(post(base + "/promote"), "{\"targetStage\":\"PRODUCTION\",\"serviceCode\":\"SITE_HAZARD\"}"), ml, "SYSADMIN", 409);
        call(json(post(base + "/approval"), "{\"role\":\"EXEC\"}"), UUID.randomUUID(), "EXEC", 200);
        call(json(post(base + "/promote"), "{\"targetStage\":\"PRODUCTION\",\"serviceCode\":\"SITE_HAZARD\"}"), ml, "SYSADMIN", 200);
        assertThat(jdbc.queryForObject("SELECT stage FROM aip.model_version WHERE id = ?", String.class, old)).isEqualTo("ARCHIVED");
        assertThat(registry.resolve("SITE_HAZARD", UUID.randomUUID()).modelVersionId()).isEqualTo(m);
        assertThat(outbox("aip.model.promoted", m).get("alias").asText()).isEqualTo("production");
        call(json(post(base + "/promote"), "{\"targetStage\":\"EVALUATED\"}"), ml, "PM", 403);
    }

    // ------------------------------------------------------------------ RPT · QAR 동기 호출(aip::api)

    @Test
    @DisplayName("QAR → aip::api: AI 검토가 REPORT_REVIEW 동기 Job 으로 실행 · 지적 매핑 · 같은 라운드 재호출은 재추론 없음 · 타임아웃/비활성 매핑")
    void qarReviewThroughAip() throws Exception {
        UUID block = UUID.randomUUID();
        worker.on(AipWorkerPort.REPORT_REVIEW, b -> {
            try {
                return objectMapper.readTree("""
                        {"jobId":"%s","model":"gemma-4-12b-it","latencyMs":3,"findings":[{"category":"NUM_MISMATCH",
                         "severity":"MAJOR","statement":"표와 본문 균열 수 불일치","citations":[{"kind":"report","blockId":"%s",
                         "quote":"균열 5개소"},{"kind":"data","ref":"quantityTable.crack.count","value":4}]}]}"""
                        .formatted(b.get("jobId").asText(), block));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        AtomAiCorePort qar = new AipAtomAiCoreAdapter(aipApi, Duration.ofSeconds(30));
        UUID round = UUID.randomUUID();
        AtomAiCorePort.Request req = new AtomAiCorePort.Request(round, "RT_PRC", "ko", List.of(new AtomAiCorePort.SectionIn(
                "ch7.opinion", List.of(new AtomAiCorePort.BlockIn(block, "균열 5개소가 관찰되었다.")))),
                objectMapper.readTree("{\"quantityTable\":{\"crack\":{\"count\":4}}}"), List.of("NUM_MISMATCH"));
        AtomAiCorePort.Response r = qar.review(req);
        assertThat(r.model()).startsWith("aip:gemma-4-12b-it@1.0.0#job=");
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).citations()).hasSize(2);
        assertThat(r.findings().get(0).citations().get(0).blockId()).isEqualTo(block);
        assertWorkerRequestsMatchContract();
        assertThat(worker.calls(AipWorkerPort.REPORT_REVIEW)).isEqualTo(1);
        Map<String, Object> job = jobs.byKey("qar-review-" + round).orElseThrow();
        assertThat(job.get("mode")).isEqualTo("SYNC");
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("requester_module")).isEqualTo("QAR");
        assertThat(jdbc.queryForObject("SELECT prompt_key FROM aip.inference_audit WHERE job_id = ?", String.class,
                job.get("id"))).isEqualTo("report.review");
        qar.review(req);   // 같은 라운드 → 멱등 재사용
        assertThat(worker.calls(AipWorkerPort.REPORT_REVIEW)).isEqualTo(1);

        worker.on(AipWorkerPort.REPORT_REVIEW, b -> {
            throw new AipJobApi.AipTimeoutException("synthetic 11분 지연", null);
        });
        UUID round2 = UUID.randomUUID();
        AtomAiCorePort.Request req2 = new AtomAiCorePort.Request(round2, req.reportType(), "ko", req.sections(), req.facts(),
                req.checks());
        assertThatThrownBy(() -> qar.review(req2)).isInstanceOf(AtomAiCorePort.AiTimeoutException.class);
        Map<String, Object> failed = jobs.byKey("qar-review-" + round2).orElseThrow();
        assertThat(failed.get("status")).isEqualTo("FAILED");
        assertThat(failed.get("error").toString()).contains("TIMEOUT");
        worker.setEnabled(false);
        assertThatThrownBy(() -> qar.review(new AtomAiCorePort.Request(UUID.randomUUID(), "RT_PRC", "ko", req.sections(),
                req.facts(), req.checks()))).isInstanceOf(AtomAiCorePort.AiUnavailableException.class)
                .hasMessageStartingWith("DISABLED");
    }

    @Test
    @DisplayName("RPT → aip::api: AI 소견 초안(REPORT_DRAFT 동기 Job) — 결함 집계 facts 전송 · 제안만 반환, 워커 비활성 → 503 RPT-503-002")
    void rptDraftThroughAip() throws Exception {
        UUID pm = flow.employee("AIP-RPT-PM");
        UUID project = flow.project(pm);
        UUID building = flow.facBuilding(pm, "합성 AIP 동", project);
        JsonNode report = flow.createReport(pm, project, building, "RT_PRC", LocalDate.now().minusDays(3));
        UUID reportId = UUID.fromString(report.get("id").asText());
        UUID rev = UUID.fromString(report.get("currentRevisionId").asText());
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "C", null));
        JsonNode d = call(post("/api/v1/rpt/revisions/" + rev + "/ai-draft"), pm, "PM", 200);
        assertThat(d.get("status").asText()).isEqualTo("OK");
        assertThat(d.get("suggestionOnly").asBoolean()).isTrue();
        assertThat(d.get("facts").get("crack.count").asInt()).isEqualTo(4);
        assertThat(d.get("facts").get("crack.max_width_mm").decimalValue()).isEqualByComparingTo("0.25");
        assertThat(d.get("draft").get("findings")).hasSize(1);
        assertWorkerRequestsMatchContract();
        JsonNode sent = worker.requests().get(0).getValue();
        assertThat(sent.get("query").asText()).contains("균열");
        assertThat(sent.get("model").asText()).isEqualTo("gemma-4-12b-it");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM aip.job WHERE requester_module = 'RPT' AND project_id = ?",
                Integer.class, project)).isEqualTo(1);
        worker.setEnabled(false);
        UUID rev2Report = UUID.fromString(flow.createReport(pm, project, building, "RT_PRC", LocalDate.now().minusDays(2))
                .get("currentRevisionId").asText());
        JsonNode err = call(post("/api/v1/rpt/revisions/" + rev2Report + "/ai-draft"), pm, "PM", 503);
        assertThat(err.get("code").asText()).isEqualTo("RPT-503-002");
    }

    @Test
    @DisplayName("계약: 대역 예시 응답이 기록된 워커 OpenAPI 응답 스키마를 만족")
    void samplesMatchContract() throws Exception {
        for (Map.Entry<String, String> e : AipSamples.SAMPLES.entrySet()) {
            assertThat(contract.response(e.getKey(), objectMapper.readTree(e.getValue()))).as(e.getKey()).isEmpty();
        }
    }
}
