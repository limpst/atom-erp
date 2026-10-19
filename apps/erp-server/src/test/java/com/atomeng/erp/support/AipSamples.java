package com.atomeng.erp.support;

import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/** AIP 워커 계약 예시 응답(합성) — 기록된 OpenAPI 응답 스키마를 만족해야 한다(AipIntegrationTest 가 검사). */
public final class AipSamples {

    static final ObjectMapper M = new ObjectMapper();

    static final String PROV = """
            {"task":"p_word","model":"crack","served_model":"crack","base_model":"gemma-4-12b-it","lora":"crack",
             "base_artifact":"gemma-4-12B-it-AWQ-INT4","lora_artifact":"adapter","prompt_hash":"%s","input_hash":"%s",
             "schema_name":null,"schema_hash":null,"labels":["crack","normal"],"client":"atomeng_common.vlm/0.1.1"}"""
            .formatted("a".repeat(64), "b".repeat(64));

    public static final Map<String, String> SAMPLES = Map.of(
            AipWorkerPort.CRACK_SCORE, """
                    {"jobId":null,"pCrack":0.97,"logprobPos":-0.03,"logprobNeg":-3.5,"labels":["crack","normal"],
                     "provenance":%s,"latencyMs":120}""".formatted(PROV),
            AipWorkerPort.FINDINGS, """
                    {"jobId":null,"finding":{"defectType":"CRACK","crackPattern":"HORIZONTAL","confidence":0.8,
                     "description":"벽체 중앙 수평 균열"},"valid":true,"rawText":"{}","provenance":null,"latencyMs":300}""",
            AipWorkerPort.CRACK_MEASURE, """
                    {"jobId":null,"image":{"width":2500,"height":1500,"mmPerPx":0.04284914,"mmPerPxUncert":0.0000259657,
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
                    {"jobId":null,"status":"OK","draft":{"findings":[{"member":"슬래브","text":"균열이 관찰되었다.",
                     "cause":"DRYING_SHRINKAGE","repair":"INJECTION","citations":["chunk:3f0e8a52-1111-5222-8333-444455556666"]}]},
                     "errors":[],"retrievedChunkIds":["3f0e8a52-1111-5222-8333-444455556666"],"keywords":["균열"],"attempts":1}""",
            AipWorkerPort.REPORT_REVIEW, """
                    {"jobId":null,"model":"gemma-4-12b-it","findings":[],"latencyMs":5}""");

    private AipSamples() {}

    /** 경로별 예시 응답(요청의 jobId 에코) */
    public static JsonNode reply(String path, JsonNode request) {
        String s = SAMPLES.get(path);
        if (s == null) {
            throw new IllegalArgumentException("예시 없음: " + path);
        }
        try {
            JsonNode n = M.readTree(s);
            if (n instanceof ObjectNode o && o.has("jobId")) {
                o.set("jobId", request.path("jobId").isMissingNode() ? M.nullNode() : request.get("jobId"));
            }
            return n;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
