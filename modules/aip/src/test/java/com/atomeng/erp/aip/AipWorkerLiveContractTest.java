package com.atomeng.erp.aip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.adapter.out.worker.HttpAipWorkerClient;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.application.AipProperties;
import com.atomeng.erp.aip.application.JobExecutor;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 실제 Python 워커를 이 시험 동안만 로컬(127.0.0.1, 임의 포트)로 띄워 Java 클라이언트와 주고받는 계약 시험.
 * services/aip-worker/.venv 가 없으면(uv sync 전) 건너뛴다 — 그때는 {@link AipWorkerContractTest}(WireMock, 같은 스키마)가 대신한다.
 * vLLM 이 필요 없는 엔드포인트(health · 측정 · 지식 검색)만 호출한다(health 는 vLLM 에 /models 조회 요청만 보냄 — 기동 · 중지 없음).
 * 이미지는 Java 로 그린 합성 띠(폭 7px)이고 스케일은 MANUAL(0.042857 mm/px → 0.300mm).
 */
class AipWorkerLiveContractTest {

    static Process proc;
    static String base;
    static OpenApiContract contract;

    static Path python() {
        Path svc = OpenApiContract.specPath().getParent().getParent();
        Path win = svc.resolve(".venv/Scripts/python.exe");
        Path nix = svc.resolve(".venv/bin/python");
        return Files.isRegularFile(win) ? win : Files.isRegularFile(nix) ? nix : null;
    }

    @BeforeAll
    static void startWorker() throws Exception {
        Path py = python();
        assumeTrue(py != null, "services/aip-worker/.venv 없음 — uv sync 후 실행됨");
        int port;
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }
        File log = Files.createTempFile("aip-worker", ".log").toFile();
        ProcessBuilder pb = new ProcessBuilder(py.toString(), "-m", "uvicorn", "atomeng_aip.app:app", "--host", "127.0.0.1",
                "--port", String.valueOf(port), "--log-level", "warning")
                .directory(OpenApiContract.specPath().getParent().getParent().toFile())
                .redirectErrorStream(true).redirectOutput(log);
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        proc = pb.start();
        base = "http://127.0.0.1:" + port;
        HttpClient http = HttpClient.newHttpClient();
        long until = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean up = false;
        while (System.nanoTime() < until && proc.isAlive()) {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base + AipWorkerPort.HEALTH))
                        .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) {
                    up = true;
                    break;
                }
            } catch (java.io.IOException e) {
                Thread.sleep(300);
            }
        }
        assumeTrue(up, "워커 기동 실패(로그: " + log + ")");
        contract = OpenApiContract.load();
    }

    @AfterAll
    static void stopWorker() {
        if (proc != null) {
            proc.descendants().forEach(ProcessHandle::destroy);
            proc.destroy();
        }
    }

    static String bandPng(int widthPx) throws Exception {
        BufferedImage img = new BufferedImage(600, 200, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(180, 180, 180));
        g.fillRect(0, 0, 600, 200);
        g.setColor(new Color(40, 40, 40));
        g.fillRect(50, 97, 500, widthPx);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }

    @Test
    @DisplayName("실워커 계약: health 응답이 기록 스키마와 일치")
    void health() throws Exception {
        HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(base + AipWorkerPort.HEALTH))
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(contract.validateResponse(AipWorkerPort.HEALTH, "get", "200", AipJdbc.MAPPER.readTree(r.body()))).isEmpty();
    }

    @Test
    @DisplayName("실워커 계약: Java → CRACK_MEASURE(합성 7px 띠, MANUAL 스케일) → 0.300mm · 경계 플래그, 응답 스키마 일치")
    void measure() throws Exception {
        JobExecutor ex = new JobExecutor(new HttpAipWorkerClient(base, Duration.ofSeconds(5), AipJdbc.MAPPER),
                new AipProperties(null, null, null, null));
        ObjectNode p = AipJdbc.MAPPER.createObjectNode();
        p.put("imageB64", bandPng(7));
        p.putObject("scale").put("source", "MANUAL").put("mmPerPx", 0.3 / 7).put("mmPerPxUncert", 0.00003);
        JobExecutor.Execution r = ex.execute(AipServiceCode.CRACK_MEASURE, UUID.randomUUID(), p, null, Duration.ofSeconds(60));
        assertThat(contract.validateResponse(AipWorkerPort.CRACK_MEASURE, "post", "200", r.output())).isEmpty();
        JsonNode c = r.output().path("components").get(0);
        assertThat(c.path("widthMaxMm").asDouble()).isEqualTo(0.3);
        assertThat(c.path("flags").toString()).contains("NEAR_GRADE_BOUNDARY_0_3MM");
        assertThat(r.output().path("image").path("scaleSource").asText()).isEqualTo("MANUAL");
    }

    @Test
    @DisplayName("실워커 계약: Java → KB_SEARCH(kiwipiepy 키워드 OR) — 권한 범위 밖 청크 제외, 응답 스키마 일치(TC-AIP-018)")
    void kbSearch() throws Exception {
        JobExecutor ex = new JobExecutor(new HttpAipWorkerClient(base, Duration.ofSeconds(5), AipJdbc.MAPPER),
                new AipProperties(null, null, null, null));
        JsonNode body = AipJdbc.MAPPER.readTree("{\"query\":\"지하주차장 슬래브 백태를 동반한 균열 소견\",\"scopes\":[\"INTERNAL\"]}");
        JobExecutor.Execution r = ex.execute(AipServiceCode.KB_SEARCH, UUID.randomUUID(), body, null, Duration.ofSeconds(60));
        assertThat(contract.validateResponse(AipWorkerPort.KB_SEARCH, "post", "200", r.output())).isEmpty();
        assertThat(r.output().path("keywords").toString()).contains("백태");
        assertThat(r.output().path("hits").toString()).contains("백태").doesNotContain("권한 범위 밖");
    }
}
