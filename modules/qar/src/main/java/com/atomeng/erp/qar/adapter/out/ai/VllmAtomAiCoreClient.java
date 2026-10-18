package com.atomeng.erp.qar.adapter.out.ai;

import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.atomeng.erp.qar.domain.ai.CitationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Atom AI Core 실제 어댑터 — 로컬 vLLM(OpenAI 호환 {@code POST /v1/chat/completions})에 검토 요청을 보내고
 * guided JSON(response_format json_schema)으로 지적 목록을 받는다(설계 B7.4: temperature 0.1, 스키마 강제).
 * 테스트에서는 호출하지 않는다(대역 사용, 요청/응답 매핑만 WireMock 으로 검증).
 */
public class VllmAtomAiCoreClient implements AtomAiCorePort {

    static final String SYSTEM_PROMPT = """
            너는 건축물 안전진단 보고서 품질 검토자다. 주어진 섹션 블록과 근거 데이터(facts)를 대조해
            수치 불일치(NUM_MISMATCH) · 근거 누락(MISSING_EVIDENCE) · 논리 비약(LOGIC_GAP) · 위험 표현(RISKY_PHRASE) ·
            누락 섹션(MISSING_SECTION)만 지적한다. 모든 지적은 반드시 보고서 블록 id 와 그 블록 원문 그대로의 인용(quote)을
            report 인용으로 포함하고, 수치 지적은 facts 경로(ref)와 값(value)을 data 인용으로 함께 단다. 확실하지 않으면 지적하지 않는다.
            """;

    private final RestClient client;
    private final String model;

    public VllmAtomAiCoreClient(String baseUrl, String model, Duration timeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        f.setReadTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
        this.model = model;
    }

    @Override
    public Response review(Request request) {
        ObjectNode body = QarJdbc.MAPPER.createObjectNode();
        body.put("model", model);
        body.put("temperature", 0.1);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", SYSTEM_PROMPT);
        messages.addObject().put("role", "user").put("content", QarJdbc.json(request));
        ObjectNode rf = body.putObject("response_format");
        rf.put("type", "json_schema");
        ObjectNode js = rf.putObject("json_schema");
        js.put("name", "qar_review");
        js.set("schema", schema());
        JsonNode resp;
        try {
            resp = client.post().uri("/v1/chat/completions").contentType(MediaType.APPLICATION_JSON)
                    .body(QarJdbc.json(body)).retrieve().body(JsonNode.class);
        } catch (RestClientException e) {
            for (Throwable c = e; c != null; c = c.getCause()) {
                if (c instanceof SocketTimeoutException) {
                    throw new AiTimeoutException("vLLM 응답 시간 초과", e);
                }
            }
            throw new AiUnavailableException((e instanceof ResourceAccessException ? "vLLM 연결 실패: " : "vLLM 오류: ")
                    + e.getMessage(), e);
        }
        if (resp == null) {
            throw new AiUnavailableException("vLLM 빈 응답", null);
        }
        String content = resp.path("choices").path(0).path("message").path("content").asText("");
        JsonNode parsed;
        try {
            parsed = QarJdbc.MAPPER.readTree(content);
        } catch (Exception e) {
            throw new AiUnavailableException("vLLM 응답이 JSON 이 아님", e);
        }
        List<CitationGuard.Draft> findings = new ArrayList<>();
        for (JsonNode f : parsed.path("findings")) {
            List<CitationGuard.Citation> cites = new ArrayList<>();
            for (JsonNode c : f.path("citations")) {
                UUID blockId = null;
                try {
                    blockId = c.hasNonNull("blockId") ? UUID.fromString(c.path("blockId").asText()) : null;
                } catch (IllegalArgumentException ignored) {
                    // 잘못된 블록 id → CitationGuard 가 UNKNOWN_BLOCK 으로 거부
                }
                JsonNode v = c.path("value");
                cites.add(new CitationGuard.Citation(c.path("kind").asText(null), blockId, c.path("quote").asText(null),
                        c.path("ref").asText(null), v.isMissingNode() || v.isNull() ? null
                                : v.isNumber() ? v.decimalValue() : v.asText()));
            }
            findings.add(new CitationGuard.Draft(f.path("category").asText(null), f.path("severity").asText("MINOR"),
                    f.path("statement").asText(""), cites));
        }
        return new Response(resp.path("model").asText(model), findings);
    }

    static JsonNode schema() {
        try {
            return QarJdbc.MAPPER.readTree("""
                    {"type":"object","required":["findings"],"properties":{"findings":{"type":"array","items":{
                      "type":"object","required":["category","severity","statement","citations"],"properties":{
                        "category":{"enum":["NUM_MISMATCH","MISSING_EVIDENCE","LOGIC_GAP","RISKY_PHRASE","MISSING_SECTION"]},
                        "severity":{"enum":["CRITICAL","MAJOR","MINOR","INFO"]},
                        "statement":{"type":"string"},
                        "citations":{"type":"array","minItems":1,"items":{"type":"object","required":["kind"],"properties":{
                          "kind":{"enum":["report","data"]},"blockId":{"type":"string"},"quote":{"type":"string"},
                          "ref":{"type":"string"},"value":{}}}}}}}}}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
