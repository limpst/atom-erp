package com.atomeng.erp.aip.adapter.out.worker;

import com.atomeng.erp.aip.api.AipJobApi.AipTimeoutException;
import com.atomeng.erp.aip.api.AipJobApi.AipUnavailableException;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 워커 HTTP 클라이언트 — JDK HttpClient(요청마다 타임아웃). 사내 로컬 주소만 허용(BR-AIP-011 외부 송신 금지):
 * localhost · 127.x · ::1 · 사설망(10/172.16/192.168). mTLS · Resilience4j 서킷브레이커는 운영 전환 Step 15.
 */
public class HttpAipWorkerClient implements AipWorkerPort {

    private final URI base;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public HttpAipWorkerClient(String baseUrl, Duration connectTimeout, ObjectMapper mapper) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        requireInternal(base);
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1).build();
        this.mapper = mapper;
    }

    static void requireInternal(URI u) {
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
        boolean ok = h.equals("localhost") || h.startsWith("127.") || h.equals("[::1]") || h.equals("::1")
                || h.startsWith("10.") || h.startsWith("192.168.") || h.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
        if (!ok) {
            throw new IllegalArgumentException("AIP 워커는 사내망 주소만 허용(BR-AIP-011): " + u);
        }
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public Response post(String path, JsonNode body, Duration timeout) {
        long t0 = System.nanoTime();
        HttpRequest req;
        try {
            req = HttpRequest.newBuilder(base.resolve(base.getPath() + path)).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
        } catch (IOException e) {
            throw new IllegalArgumentException("요청 직렬화 실패", e);
        }
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            throw new AipTimeoutException("AIP 워커 응답 시간 초과(" + timeout + "): " + path, e);
        } catch (ConnectException e) {
            throw new AipUnavailableException("AIP 워커 연결 실패: " + base, e);
        } catch (IOException e) {
            throw new AipUnavailableException("AIP 워커 통신 오류: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AipUnavailableException("AIP 워커 호출 중단", e);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        JsonNode parsed;
        try {
            parsed = resp.body() == null || resp.body().isBlank() ? mapper.nullNode() : mapper.readTree(resp.body());
        } catch (IOException e) {
            throw new AipUnavailableException("AIP 워커 응답이 JSON 이 아님(HTTP " + resp.statusCode() + ")", e);
        }
        if (resp.statusCode() / 100 != 2) {
            String code = parsed.path("code").asText("HTTP-" + resp.statusCode());
            throw new AipUnavailableException("AIP 워커 오류 " + resp.statusCode() + " " + code + ": "
                    + parsed.path("detail").asText(parsed.toString()), null);
        }
        return new Response(resp.statusCode(), parsed, ms);
    }
}
