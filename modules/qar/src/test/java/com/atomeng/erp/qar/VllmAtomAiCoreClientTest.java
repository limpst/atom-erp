package com.atomeng.erp.qar;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.qar.adapter.out.ai.VllmAtomAiCoreClient;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Atom AI Core(vLLM, OpenAI 호환) 어댑터 매핑 — 실제 vLLM 은 호출하지 않고 WireMock 대역만 쓴다(합성 응답).
 */
class VllmAtomAiCoreClientTest {

    static final WireMockServer VLLM = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    @BeforeAll
    static void start() {
        VLLM.start();
    }

    @AfterAll
    static void stop() {
        VLLM.stop();
    }

    static AtomAiCorePort.Request request(UUID block) throws Exception {
        return new AtomAiCorePort.Request(UUID.randomUUID(), "RT_PRC", "ko", List.of(new AtomAiCorePort.SectionIn(
                "ch7.opinion", List.of(new AtomAiCorePort.BlockIn(block, "1F 기둥 균열이 3개소 확인되었다.")))),
                new ObjectMapper().readTree("{\"quantityTable\":[{\"count\":2}]}"), List.of("NUM_MISMATCH"));
    }

    @Test
    @DisplayName("요청: /v1/chat/completions · temperature 0.1 · response_format json_schema(guided JSON) / 응답 content JSON → 지적 · 인용")
    void mapsRequestAndResponse() throws Exception {
        UUID block = UUID.randomUUID();
        String content = new ObjectMapper().writeValueAsString(java.util.Map.of("findings", List.of(java.util.Map.of(
                "category", "NUM_MISMATCH", "severity", "MAJOR", "statement", "본문 3개소 vs 물량표 2개소",
                "citations", List.of(java.util.Map.of("kind", "report", "blockId", block.toString(), "quote", "3개소"),
                        java.util.Map.of("kind", "data", "ref", "quantityTable[0].count", "value", 2))))));
        VLLM.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody(new ObjectMapper().writeValueAsString(java.util.Map.of("model",
                "gemma-synthetic", "choices", List.of(java.util.Map.of("message", java.util.Map.of("role", "assistant",
                        "content", content))))))));
        var client = new VllmAtomAiCoreClient(VLLM.baseUrl(), "atom-qar-review", Duration.ofSeconds(5));
        AtomAiCorePort.Response r = client.review(request(block));
        assertThat(r.model()).isEqualTo("gemma-synthetic");
        assertThat(r.findings()).hasSize(1);
        assertThat(r.findings().get(0).citations()).hasSize(2);
        assertThat(r.findings().get(0).citations().get(0).blockId()).isEqualTo(block);
        assertThat(r.findings().get(0).citations().get(1).value().toString()).isEqualTo("2");
        VLLM.verify(postRequestedFor(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("atom-qar-review")))
                .withRequestBody(matchingJsonPath("$.temperature", equalTo("0.1")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                .withRequestBody(matchingJsonPath("$.messages[1].content", com.github.tomakehurst.wiremock.client.WireMock
                        .containing(block.toString()))));
    }

    @Test
    @DisplayName("장애: 5xx → AiUnavailableException, 응답 지연 → AiTimeoutException(AI 단계 스킵 경로)")
    void failures() throws Exception {
        UUID block = UUID.randomUUID();
        WireMockServer down = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        down.start();
        try {
            down.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(503)));
            assertThatThrownBy(() -> new VllmAtomAiCoreClient(down.baseUrl(), "m", Duration.ofSeconds(5))
                    .review(request(block))).isInstanceOf(AtomAiCorePort.AiUnavailableException.class);
            down.resetAll();
            down.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withHeader("Content-Type", "application/json").withFixedDelay(1500)
                    .withBody("{}")));
            assertThatThrownBy(() -> new VllmAtomAiCoreClient(down.baseUrl(), "m", Duration.ofMillis(300))
                    .review(request(block))).isInstanceOf(AtomAiCorePort.AiTimeoutException.class);
        } finally {
            down.stop();
        }
    }
}
