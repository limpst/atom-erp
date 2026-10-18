package com.atomeng.erp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.fcd.adapter.out.aip.HttpFraudServiceAdapter;
import com.atomeng.erp.fcd.application.port.FraudServicePort.TxnRef;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L2/L3 Python 서비스 포트 HTTP 어댑터 — WireMock 대역(합성 응답). TC-FCD-023: L3 서버 지연 → 타임아웃 → l3_status=TIMEOUT
 * (L1+L2 잠정 점수로 진행), 맥락 응답은 ctx 네임스페이스로 전달.
 */
class FcdFraudServiceAdapterTest {

    WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    @Test
    @DisplayName("TC-FCD-023: /v1/fraud/score 2초 지연 · 타임아웃 300ms → TIMEOUT(예외 없이), /v1/fraud/context → ctx 맵 · L3 정상 응답 → OK")
    void timeoutAndContext() {
        UUID t = UUID.randomUUID();
        server.stubFor(post(urlEqualTo("/v1/fraud/context")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody("{\"context\":{\"" + t + "\":{\"on_leave\":true,\"leave_kind\":\"FULL\"}}}")));
        server.stubFor(post(urlEqualTo("/v1/fraud/score")).willReturn(aResponse().withFixedDelay(2000)
                .withHeader("Content-Type", "application/json").withBody("{\"results\":{}}")));
        var adapter = new HttpFraudServiceAdapter(server.baseUrl(), Duration.ofMillis(300));
        var ref = new TxnRef(t, UUID.randomUUID(), UUID.randomUUID(), LocalDateTime.of(2026, 5, 5, 12, 0), "(예시)A", null, 9000);
        assertThat(adapter.context(List.of(ref)).get(t)).containsEntry("on_leave", true);
        long t0 = System.nanoTime();
        var l3 = adapter.anomaly(List.of(ref), Map.of(t, Map.of("amount", 9000.0)));
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(1500));
        assertThat(l3.get(t).status()).isEqualTo("TIMEOUT");
        assertThat(l3.get(t).l3()).isEmpty();

        server.stubFor(post(urlEqualTo("/v1/fraud/score")).willReturn(aResponse().withHeader("Content-Type",
                "application/json").withBody("{\"model_version\":\"fcd-l3-2026.10\",\"results\":{\"" + t
                + "\":{\"s3\":0.97,\"top_text\":\"합성\"}}}")));
        var ok = new HttpFraudServiceAdapter(server.baseUrl(), Duration.ofMillis(800)).anomaly(List.of(ref), Map.of());
        assertThat(ok.get(t).status()).isEqualTo("OK");
        assertThat(ok.get(t).modelVersion()).isEqualTo("fcd-l3-2026.10");
        assertThat(ok.get(t).l3()).containsEntry("s3", 0.97);
    }
}
