package com.atomeng.erp.aip;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.adapter.out.worker.HttpAipWorkerClient;
import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.application.AipProperties;
import com.atomeng.erp.aip.application.JobExecutor;
import com.atomeng.erp.aip.application.ModelRegistryService;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import com.networknt.schema.ValidationMessage;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Java 클라이언트 ↔ Python 워커 계약 시험(WireMock) — 기록된 OpenAPI(contract/openapi.json)로
 * ① Java(JobExecutor + HttpAipWorkerClient)가 보내는 요청 본문 전부 ② 대역이 돌려주는 응답 예시를 JSON Schema 로 검증한다.
 * 워커 요청 모델은 extra="forbid"(additionalProperties false) — Java 가 계약에 없는 필드를 보내면 여기서 실패한다.
 * 실제 워커를 띄우는 같은 검사는 {@link AipWorkerLiveContractTest}(가능할 때만).
 */
class AipWorkerContractTest {

    static final WireMockServer WM = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    static OpenApiContract contract;

    static final String PROV = """
            {"task":"p_word","model":"crack","served_model":"crack","base_model":"gemma-4-12b-it","lora":"crack",
             "base_artifact":"gemma-4-12B-it-AWQ-INT4","lora_artifact":"adapter","prompt_hash":"%s","input_hash":"%s",
             "schema_name":null,"schema_hash":null,"labels":["crack","normal"],"client":"atomeng_common.vlm/0.1.1"}"""
            .formatted("a".repeat(64), "b".repeat(64));

    static final Map<String, String> SAMPLES = Map.of(
            AipWorkerPort.CRACK_SCORE, """
                    {"jobId":"j","pCrack":0.97,"logprobPos":-0.03,"logprobNeg":-3.5,"labels":["crack","normal"],
                     "provenance":%s,"latencyMs":120}""".formatted(PROV),
            AipWorkerPort.FINDINGS, """
                    {"jobId":"j","finding":{"defectType":"CRACK","crackPattern":"HORIZONTAL","confidence":0.8,
                     "description":"벽체 중앙 수평 균열"},"valid":true,"rawText":"{}","provenance":null,"latencyMs":300}""",
            AipWorkerPort.CRACK_MEASURE, """
                    {"jobId":"j","image":{"width":2500,"height":1500,"mmPerPx":0.04284914,"mmPerPxUncert":0.0000259657,
                     "scaleSource":"ARUCO","scaleReprojErrPx":0.0,"markers":[{"id":0,"cornersPx":[[120,150],[1286,150],
                     [1286,1316],[120,1316]]}]},"components":[{"bbox":[747,1547,754,2447],"areaPx":6300,
                     "skeletonLengthPx":895.414214,"widthMaxPx":7.0,"widthMaxMm":0.3,"widthMeanMm":0.3,"widthP95Mm":0.3,
                     "widthUncertMm":0.043,"lengthM":0.038,"flags":["NEAR_GRADE_BOUNDARY_0_3MM"]}],
                     "timings":{"scaleMs":10,"measureMs":20}}""",
            AipWorkerPort.KB_SEARCH, """
                    {"query":"백태 균열","keywords":["백태","균열"],"mode":"KEYWORD_ONLY","hits":[{"chunkId":"c1",
                     "documentId":"d1","kind":"GUIDELINE","headingPath":"합성지침","score":0.5,"matchedKeywords":["백태"],
                     "content":"백태가 함께 나타난 균열"}]}""",
            AipWorkerPort.REPORT_DRAFT, """
                    {"jobId":"j","status":"OK","draft":{"findings":[]},"errors":[],"retrievedChunkIds":["c1"],
                     "keywords":["균열"],"attempts":1}""",
            AipWorkerPort.REPORT_REVIEW, """
                    {"jobId":"j","model":"gemma-4-12b-it","findings":[{"category":"NUM_MISMATCH","severity":"MAJOR",
                     "statement":"s","citations":[{"kind":"report","blockId":"b","quote":"q"}]}],"latencyMs":5}""");

    @BeforeAll
    static void start() throws Exception {
        WM.start();
        contract = OpenApiContract.load();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    @BeforeEach
    void stubs() {
        WM.resetAll();
        SAMPLES.forEach((path, body) -> WM.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body))));
    }

    static JobExecutor executor(String base) {
        AipWorkerPort worker = new HttpAipWorkerClient(base, Duration.ofSeconds(2), AipJdbc.MAPPER);
        return new JobExecutor(worker, new AipProperties(null, null, null, null));
    }

    static JsonNode json(String s) throws Exception {
        return AipJdbc.MAPPER.readTree(s);
    }

    static ModelRegistryService.Route route(String model) {
        return new ModelRegistryService.Route("X", "production", UUID.randomUUID(), model, "1.0.0", null, null, null, null);
    }

    @Test
    @DisplayName("계약: 응답 예시 6종이 기록된 OpenAPI 응답 스키마를 만족(대역이 실제 계약과 어긋나면 실패)")
    void samplesMatchResponseSchemas() throws Exception {
        for (Map.Entry<String, String> e : SAMPLES.entrySet()) {
            Set<ValidationMessage> errs = contract.validateResponse(e.getKey(), "post", "200", json(e.getValue()));
            assertThat(errs).as(e.getKey()).isEmpty();
        }
        assertThat(contract.validateResponse(AipWorkerPort.HEALTH, "get", "200",
                json("{\"status\":\"UP\",\"version\":\"0.1.0\",\"vllmReachable\":false,\"models\":[]}"))).isEmpty();
        // 음성: 필수 필드 누락 응답은 스키마 위반
        assertThat(contract.validateResponse(AipWorkerPort.CRACK_SCORE, "post", "200", json("{\"jobId\":\"j\"}"))).isNotEmpty();
    }

    @Test
    @DisplayName("계약: Java 가 보내는 요청(CRACK_DETECT 체인 · 측정 · 초안 · 검토 · 검색)이 전부 OpenAPI 요청 스키마를 만족")
    void javaRequestsMatchRequestSchemas() throws Exception {
        JobExecutor ex = executor(WM.baseUrl());
        UUID job = UUID.randomUUID();
        JobExecutor.Execution det = ex.execute(AipServiceCode.CRACK_DETECT, job, json("""
                {"imageB64":"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
                 "chain":["CRACK_MEASURE"],"minConfidence":0.35,"context":{"member":"기둥 C3"},
                 "scale":{"source":"ARUCO","markerSizeMm":50,"dictionary":"DICT_4X4_50"},"boundariesMm":[0.1,0.2,0.3]}"""),
                route("crack"), Duration.ofSeconds(5));
        assertThat(det.output().path("detected").asBoolean()).isTrue();
        assertThat(det.output().path("measure").path("components").get(0).path("widthMaxMm").asDouble()).isEqualTo(0.3);
        assertThat(det.adapter()).isEqualTo("crack");
        ex.execute(AipServiceCode.CRACK_MEASURE, job, json("{\"imageB64\":\"aaaaaaaaaa\",\"scale\":{\"source\":\"MANUAL\","
                + "\"mmPerPx\":0.05,\"mmPerPxUncert\":0.0005}}"), null, Duration.ofSeconds(5));
        JobExecutor.Execution draft = ex.execute(AipServiceCode.REPORT_DRAFT, job,
                json("{\"facts\":{\"crack.count\":4,\"crack.max_width_mm\":0.3},\"query\":\"슬래브 균열 소견\"}"),
                route("gemma-4-12b-it"), Duration.ofSeconds(5));
        assertThat(draft.retrievedChunkIds()).containsExactly("c1");
        ex.execute(AipServiceCode.REPORT_REVIEW, job, json("""
                {"roundId":"r1","reportType":"RT_PRC","locale":"ko","sections":[{"key":"ch7.opinion","blocks":[
                 {"id":"b1","text":"상태평가 결과는 C"}]}],"facts":{"grade":"C"},"checks":[]}"""), route("gemma-4-12b-it"),
                Duration.ofSeconds(5));
        ex.execute(AipServiceCode.KB_SEARCH, job, json("{\"query\":\"백태 균열\",\"scopes\":[\"INTERNAL\"],\"k\":8}"), null,
                Duration.ofSeconds(5));
        int checked = 0;
        for (ServeEvent ev : WM.getAllServeEvents()) {
            String path = ev.getRequest().getUrl();
            Set<ValidationMessage> errs = contract.validateRequest(path, json(ev.getRequest().getBodyAsString()));
            assertThat(errs).as("요청 계약 위반 " + path + " " + ev.getRequest().getBodyAsString()).isEmpty();
            checked++;
        }
        assertThat(checked).isEqualTo(7);   // score · findings · measure(체인) + measure · draft · review · search
    }

    @Test
    @DisplayName("오류 매핑: 503 problem → UNAVAILABLE(코드 포함), 지연 > 마감 → TIMEOUT, 연결 거부 → UNAVAILABLE, 사외 주소 거부(BR-AIP-011)")
    void errorMapping() throws Exception {
        WM.stubFor(post(urlEqualTo(AipWorkerPort.CRACK_SCORE)).willReturn(aResponse().withStatus(503)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"code\":\"AIP-503-001\",\"title\":\"vLLM 응답 없음\",\"detail\":\"connection refused\"}")));
        assertThat(contract.validateResponse(AipWorkerPort.CRACK_SCORE, "post", "503",
                json("{\"code\":\"AIP-503-001\",\"title\":\"vLLM 응답 없음\",\"detail\":\"x\"}"))).isEmpty();
        JobExecutor ex = executor(WM.baseUrl());
        assertThatThrownBy(() -> ex.execute(AipServiceCode.CRACK_DETECT, UUID.randomUUID(), json("{\"imageB64\":\"aaaaaaaa\"}"),
                route("crack"), Duration.ofSeconds(5))).isInstanceOf(AipJobApi.AipUnavailableException.class)
                .hasMessageContaining("AIP-503-001");
        WM.stubFor(post(urlEqualTo(AipWorkerPort.KB_SEARCH)).willReturn(aResponse().withStatus(200).withFixedDelay(1500)
                .withHeader("Content-Type", "application/json").withBody(SAMPLES.get(AipWorkerPort.KB_SEARCH))));
        assertThatThrownBy(() -> ex.execute(AipServiceCode.KB_SEARCH, UUID.randomUUID(), json("{\"query\":\"q\"}"), null,
                Duration.ofMillis(300))).isInstanceOf(AipJobApi.AipTimeoutException.class);
        JobExecutor dead = executor("http://127.0.0.1:1");
        assertThatThrownBy(() -> dead.execute(AipServiceCode.KB_SEARCH, UUID.randomUUID(), json("{\"query\":\"q\"}"), null,
                Duration.ofSeconds(2))).isInstanceOf(AipJobApi.AipUnavailableException.class);
        assertThatThrownBy(() -> new HttpAipWorkerClient("https://api.example.com", Duration.ofSeconds(1), AipJdbc.MAPPER))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("BR-AIP-011");
    }

    @Test
    @DisplayName("미지원 서비스(STT · 드론 · 위험요소)는 워커를 부르지 않고 422 AIP-422-002")
    void unsupportedService() throws Exception {
        JobExecutor ex = executor(WM.baseUrl());
        assertThatThrownBy(() -> ex.execute(AipServiceCode.STT, UUID.randomUUID(), json("{}"), null, Duration.ofSeconds(1)))
                .hasMessageContaining("미지원");
        assertThat(WM.getAllServeEvents()).isEmpty();
    }
}
