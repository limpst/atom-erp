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
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;

/**
 * 워커 HTTP 클라이언트 — JDK HttpClient(요청마다 타임아웃, 리다이렉트 안 따라감). 보안 검토 AIP-06: 워커 주소는 루프백이
 * 기본이고 그 밖은 atom.aip.worker.allowed-hosts 에 정확히 적은 호스트만(사설망 대역 전체 허용 안 함, BR-AIP-011),
 * 모든 요청에 HMAC 공유 비밀 서명({@link WorkerRequestSigner}). mTLS · Resilience4j 서킷브레이커는 운영 전환 Step 15.
 */
public class HttpAipWorkerClient implements AipWorkerPort {

    private final URI base;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private final WorkerRequestSigner signer;

    /**
     * @param sharedSecret 32바이트 이상 공유 비밀(워커 ATOM_AIP_WORKER_SECRET 과 같은 값)
     * @param allowedHosts 루프백 밖에서 허용할 워커 호스트(정확히 일치, 기본 비어 있음)
     */
    public HttpAipWorkerClient(String baseUrl, Duration connectTimeout, ObjectMapper mapper, byte[] sharedSecret,
                               Collection<String> allowedHosts, Clock clock) {
        this.base = URI.create(baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl);
        requireAllowed(base, allowedHosts);
        this.signer = new WorkerRequestSigner(sharedSecret, clock);
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = mapper;
    }

    static void requireAllowed(URI u, Collection<String> allowedHosts) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String h = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        boolean loopback = h.equals("localhost") || h.matches("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
                || h.equals("[::1]") || h.equals("::1");
        boolean listed = allowedHosts != null && allowedHosts.stream()
                .anyMatch(a -> a != null && a.trim().toLowerCase(Locale.ROOT).equals(h));
        if (!(scheme.equals("http") || scheme.equals("https")) || u.getRawUserInfo() != null || u.getRawQuery() != null
                || h.isEmpty() || !(loopback || listed)) {
            throw new IllegalArgumentException("AIP 워커는 루프백 또는 atom.aip.worker.allowed-hosts 에 적은 주소만 허용"
                    + "(BR-AIP-011 · AIP-06): " + u);
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
            URI target = base.resolve(base.getPath() + path);
            byte[] payload = mapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            HttpRequest.Builder b = HttpRequest.newBuilder(target).timeout(timeout)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
            signer.sign("POST", target.getRawPath(), payload).forEach(b::header);
            req = b.build();
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
