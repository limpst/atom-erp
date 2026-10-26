package com.atomeng.common.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 보안 검토 CMN-07 — S3 호환 Object Lock 어댑터를 프로세스 안 가짜 S3(com.sun.net.httpserver)로 검증한다.
 * 가짜 S3 는 서명(SigV4 헤더 · 쿼리)을 이 시험의 <b>독립 구현</b>으로 다시 계산하고, Content-MD5 · x-amz-content-sha256 ·
 * Object Lock 헤더 · 조건부 쓰기(If-None-Match)를 실제 S3/MinIO 처럼 검사한다. 자격 증명은 합성 값.
 */
class S3ObjectLockStorageTest {

    static final String AK = "SYNTHETIC-TEST-AK-0001";
    static final String SK = "synthetic/secret/key/for/fake/s3/0000000";
    static final String REGION = "us-east-1";

    FakeS3 s3;
    S3ObjectLockStorage storage;
    final Clock clock = Clock.fixed(Instant.parse("2026-10-26T01:02:03Z"), ZoneOffset.UTC);

    @BeforeEach
    void start() throws IOException {
        s3 = new FakeS3(AK, SK, Set.of("atom-field-original", "atom-drawings", "atom-reports"));
        storage = new S3ObjectLockStorage(new S3ObjectLockStorage.Config(s3.endpoint(), REGION, "atom-",
                Duration.ofDays(3650), Duration.ofSeconds(10)), AK, SK, Buckets.WORM, clock);
    }

    @AfterEach
    void stop() {
        s3.stop();
    }

    static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("CMN-07: SigV4 서명이 AWS 문서 예시값과 같다(헤더 서명 GET · presigned URL)")
    void sigV4KnownAnswers() {
        var c = new SigV4.Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY");
        URI u = URI.create("https://examplebucket.s3.amazonaws.com/test.txt");
        Map<String, String> h = new TreeMap<>(Map.of("host", "examplebucket.s3.amazonaws.com", "range", "bytes=0-9",
                "x-amz-content-sha256", SigV4.EMPTY_SHA256, "x-amz-date", "20130524T000000Z"));
        assertThat(SigV4.authorization("GET", u, h, SigV4.EMPTY_SHA256, "20130524T000000Z", "us-east-1", c))
                .endsWith("Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41")
                .contains("SignedHeaders=host;range;x-amz-content-sha256;x-amz-date");
        assertThat(SigV4.presignQuery("GET", u, 86400, "20130524T000000Z", "us-east-1", c))
                .endsWith("X-Amz-Signature=aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404");
        assertThat(c.toString()).doesNotContain("wJalr");
    }

    @Test
    @DisplayName("CMN-07: WORM 버킷 PUT = COMPLIANCE 잠금 · 보존 기한 · If-None-Match · Content-MD5 · sha256 서명, GET/HEAD 왕복")
    void wormPutCarriesLockAndIntegrityHeaders() throws Exception {
        byte[] pdf = bytes("%PDF-1.7 synthetic report");
        storage.put(Buckets.REPORTS, "rpt/2026/r-1.pdf", new ByteArrayInputStream(pdf), "application/pdf");
        FakeS3.Req put = s3.last("PUT");
        assertThat(put.path()).isEqualTo("/atom-reports/rpt/2026/r-1.pdf");
        assertThat(put.header("x-amz-object-lock-mode")).isEqualTo("COMPLIANCE");
        assertThat(Instant.parse(put.header("x-amz-object-lock-retain-until-date")))
                .isEqualTo(clock.instant().plus(Duration.ofDays(3650)));
        assertThat(put.header("if-none-match")).isEqualTo("*");
        assertThat(put.header("content-md5")).isEqualTo(Base64.getEncoder().encodeToString(md5(pdf)));
        assertThat(put.header("x-amz-content-sha256")).isEqualTo(HashChain.sha256(new ByteArrayInputStream(pdf)));
        assertThat(put.header("authorization")).contains("SignedHeaders=").contains("x-amz-object-lock-mode")
                .contains("content-md5");
        assertThat(s3.verified).isGreaterThanOrEqualTo(2);   // HEAD + PUT 모두 서명 검증 통과

        try (InputStream in = storage.get(Buckets.REPORTS, "rpt/2026/r-1.pdf")) {
            assertThat(in.readAllBytes()).isEqualTo(pdf);
        }
        assertThat(storage.exists(Buckets.REPORTS, "rpt/2026/r-1.pdf")).isTrue();
        assertThat(storage.size(Buckets.REPORTS, "rpt/2026/r-1.pdf")).isEqualTo(pdf.length);
        assertThat(storage.exists(Buckets.REPORTS, "rpt/2026/none.pdf")).isFalse();
        assertThatThrownBy(() -> storage.get(Buckets.REPORTS, "rpt/2026/none.pdf"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CMN-07: 잠긴 키 덮어쓰기 · 삭제 금지 — 덮어쓰기는 PUT 전 거부(경합 시 412 도 거부), WORM 삭제는 DELETE 를 보내지 않음")
    void noOverwriteOrDeleteOfLockedKeys() {
        storage.put(Buckets.FIELD_ORIGINAL, "fld/a.jpg", new ByteArrayInputStream(bytes("jpeg-1")), "image/jpeg");
        int puts = s3.count("PUT");
        assertThatThrownBy(() -> storage.put(Buckets.FIELD_ORIGINAL, "fld/a.jpg",
                new ByteArrayInputStream(bytes("jpeg-2")), "image/jpeg")).isInstanceOf(ObjectLockedException.class);
        assertThat(s3.count("PUT")).isEqualTo(puts);
        s3.hideFromHead = true;   // HEAD 와 PUT 사이에 다른 프로세스가 같은 키를 쓴 경합
        assertThatThrownBy(() -> storage.put(Buckets.FIELD_ORIGINAL, "fld/a.jpg",
                new ByteArrayInputStream(bytes("jpeg-3")), "image/jpeg")).isInstanceOf(ObjectLockedException.class);
        s3.hideFromHead = false;
        assertThat(s3.object("atom-field-original/fld/a.jpg")).isEqualTo(bytes("jpeg-1"));

        assertThatThrownBy(() -> storage.delete(Buckets.FIELD_ORIGINAL, "fld/a.jpg")).isInstanceOf(ObjectLockedException.class);
        assertThat(s3.count("DELETE")).isZero();
        assertThat(s3.object("atom-field-original/fld/a.jpg")).isNotNull();

        // 비 WORM(temp-upload)은 덮어쓰기 · 삭제 가능, 잠금 헤더 없음
        storage.put(Buckets.TEMP_UPLOAD, "u/1", new ByteArrayInputStream(bytes("t1")), null);
        storage.put(Buckets.TEMP_UPLOAD, "u/1", new ByteArrayInputStream(bytes("t2")), null);
        assertThat(s3.last("PUT").header("x-amz-object-lock-mode")).isNull();
        storage.delete(Buckets.TEMP_UPLOAD, "u/1");
        assertThat(s3.count("DELETE")).isEqualTo(1);
        assertThat(s3.object("atom-temp-upload/u/1")).isNull();
    }

    @Test
    @DisplayName("CMN-07: temp-upload → field-original 복사는 COMPLIANCE 잠금으로 쓰고, presign PUT 은 쿼리 서명이 유효(WORM 버킷 presign 거부)")
    void copyAndPresign() throws Exception {
        var p = storage.presignPut(Buckets.TEMP_UPLOAD, "u/photo-1", Duration.ofMinutes(15));
        assertThat(p.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(15)));
        assertThat(p.url().getQuery()).contains("X-Amz-Signature=").doesNotContain(SK);
        // 가짜 S3 의 시각 검사는 고정 시계 기준 — 클라이언트가 서명 URL 로 직접 PUT
        var r = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
                .send(HttpRequest.newBuilder(p.url()).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes("raw-photo"))).build(),
                        HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThatThrownBy(() -> storage.presignPut(Buckets.REPORTS, "x", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);

        storage.copy(Buckets.TEMP_UPLOAD, "u/photo-1", Buckets.FIELD_ORIGINAL, "fld/photo-1.jpg");
        assertThat(s3.last("PUT").header("x-amz-object-lock-mode")).isEqualTo("COMPLIANCE");
        assertThat(s3.object("atom-field-original/fld/photo-1.jpg")).isEqualTo(bytes("raw-photo"));
    }

    @Test
    @DisplayName("CMN-07: 서명 불일치(잘못된 비밀) · ETag 무결성 불일치 · 위험한 키는 실패")
    void failures() {
        var wrong = new S3ObjectLockStorage(new S3ObjectLockStorage.Config(s3.endpoint(), REGION, "atom-", null, null),
                AK, "not-the-secret", Buckets.WORM, clock);
        assertThatThrownBy(() -> wrong.put(Buckets.TEMP_UPLOAD, "u/x", new ByteArrayInputStream(bytes("x")), null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("403");
        s3.corruptEtag = true;
        assertThatThrownBy(() -> storage.put(Buckets.TEMP_UPLOAD, "u/y", new ByteArrayInputStream(bytes("y")), null))
                .hasMessageContaining("무결성");
        assertThatThrownBy(() -> storage.exists(Buckets.TEMP_UPLOAD, "../etc/passwd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.exists(".", "a")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("CMN-07: 선택 · 운영 가드 — prod 는 store=s3 만(로컬은 allow-local-in-prod 명시 재정의), 자격 증명은 환경변수에서만")
    void selectionAndProdGuard() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> EvidenceAutoConfiguration.requireObjectLockStoreInProd(prod, "local"))
                .hasMessageContaining("CMN-07");
        assertThatCode(() -> EvidenceAutoConfiguration.requireObjectLockStoreInProd(prod, "s3")).doesNotThrowAnyException();
        MockEnvironment ack = new MockEnvironment();
        ack.setActiveProfiles("production");
        ack.setProperty("atom.evidence.allow-local-in-prod", "true");
        assertThatCode(() -> EvidenceAutoConfiguration.requireObjectLockStoreInProd(ack, "local")).doesNotThrowAnyException();
        assertThatCode(() -> EvidenceAutoConfiguration.requireObjectLockStoreInProd(new MockEnvironment(), "local"))
                .doesNotThrowAnyException();

        MockEnvironment cfg = new MockEnvironment();
        cfg.setProperty("atom.evidence.s3.endpoint", s3.endpoint().toString());
        Map<String, String> osEnv = Map.of(EvidenceAutoConfiguration.ACCESS_KEY_ENV, AK,
                EvidenceAutoConfiguration.SECRET_KEY_ENV, SK);
        S3ObjectLockStorage fromEnv = EvidenceAutoConfiguration.s3(cfg, osEnv, clock);
        fromEnv.put(Buckets.DRAWINGS, "d/1.dxf", new ByteArrayInputStream(bytes("0\nSECTION")), null);
        assertThat(s3.object("drawings/d/1.dxf")).isNotNull();   // 접두어 없음 = 논리 버킷 이름 그대로
        assertThatThrownBy(() -> EvidenceAutoConfiguration.s3(cfg, Map.of(), clock)).hasMessageContaining("CMN-07");
        cfg.setProperty("atom.evidence.s3.secret-key", "from-config-file");
        assertThatThrownBy(() -> EvidenceAutoConfiguration.s3(cfg, osEnv, clock)).hasMessageContaining("환경변수");
        assertThatThrownBy(() -> EvidenceAutoConfiguration.s3(new MockEnvironment(), osEnv, clock))
                .hasMessageContaining("endpoint");
    }

    static byte[] md5(byte[] b) {
        try {
            return MessageDigest.getInstance("MD5").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ================================================================== 가짜 S3 (독립 SigV4 검증)

    static final class FakeS3 {
        record Req(String method, String path, Map<String, String> headers) {
            String header(String n) {
                return headers.get(n.toLowerCase(Locale.ROOT));
            }
        }

        private final HttpServer server;
        private final String ak;
        private final String sk;
        private final Set<String> lockedBuckets;
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
        final List<Req> requests = new ArrayList<>();
        volatile int verified;
        volatile boolean hideFromHead;
        volatile boolean corruptEtag;

        FakeS3(String ak, String sk, Set<String> lockedBuckets) throws IOException {
            this.ak = ak;
            this.sk = sk;
            this.lockedBuckets = lockedBuckets;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        void stop() {
            server.stop(0);
        }

        byte[] object(String bucketKey) {
            return objects.get(bucketKey);
        }

        synchronized Req last(String method) {
            for (int i = requests.size() - 1; i >= 0; i--) {
                if (requests.get(i).method().equals(method)) {
                    return requests.get(i);
                }
            }
            throw new AssertionError("요청 없음: " + method);
        }

        synchronized int count(String method) {
            return (int) requests.stream().filter(r -> r.method().equals(method)).count();
        }

        private void handle(HttpExchange ex) throws IOException {
            try (ex) {
                byte[] body = ex.getRequestBody().readAllBytes();
                Map<String, String> h = new TreeMap<>();
                ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.get(0)));
                String method = ex.getRequestMethod();
                String path = ex.getRequestURI().getRawPath();
                String rawQuery = ex.getRequestURI().getRawQuery();
                synchronized (this) {
                    requests.add(new Req(method, path, h));
                }
                String authError = rawQuery != null && rawQuery.contains("X-Amz-Signature=")
                        ? verifyPresigned(method, path, rawQuery, h) : verifyHeaderAuth(method, path, h, body);
                if (authError != null) {
                    reply(ex, 403, authError);
                    return;
                }
                verified++;
                String key = path.substring(1);   // bucket/key
                String bucket = key.substring(0, key.indexOf('/'));
                switch (method) {
                    case "PUT" -> {
                        if (h.containsKey("content-md5")
                                && !h.get("content-md5").equals(Base64.getEncoder().encodeToString(md5(body)))) {
                            reply(ex, 400, "BadDigest");
                            return;
                        }
                        if (lockedBuckets.contains(bucket)) {
                            if (!"COMPLIANCE".equals(h.get("x-amz-object-lock-mode"))
                                    || !h.containsKey("x-amz-object-lock-retain-until-date")) {
                                reply(ex, 400, "MissingObjectLockHeaders");
                                return;
                            }
                            if (objects.containsKey(key)) {
                                reply(ex, "*".equals(h.get("if-none-match")) ? 412 : 409, "PreconditionFailed");
                                return;
                            }
                        }
                        objects.put(key, body);
                        String etag = corruptEtag ? "0".repeat(32) : HexFormat.of().formatHex(md5(body));
                        ex.getResponseHeaders().set("ETag", "\"" + etag + "\"");
                        ex.sendResponseHeaders(200, -1);
                    }
                    case "GET" -> {
                        byte[] o = objects.get(key);
                        if (o == null) {
                            reply(ex, 404, "NoSuchKey");
                            return;
                        }
                        ex.sendResponseHeaders(200, o.length);
                        ex.getResponseBody().write(o);
                    }
                    case "HEAD" -> {
                        byte[] o = hideFromHead ? null : objects.get(key);
                        if (o == null) {
                            ex.sendResponseHeaders(404, -1);
                            return;
                        }
                        ex.getResponseHeaders().set("Content-Length", Integer.toString(o.length));
                        ex.sendResponseHeaders(200, -1);
                    }
                    case "DELETE" -> {
                        if (lockedBuckets.contains(bucket) && objects.containsKey(key)) {
                            reply(ex, 403, "AccessDenied: object is WORM protected");
                            return;
                        }
                        objects.remove(key);
                        ex.sendResponseHeaders(204, -1);
                    }
                    default -> reply(ex, 405, "MethodNotAllowed");
                }
            }
        }

        private static void reply(HttpExchange ex, int status, String msg) throws IOException {
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            if ("HEAD".equals(ex.getRequestMethod())) {
                ex.sendResponseHeaders(status, -1);
                return;
            }
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
        }

        private String verifyHeaderAuth(String method, String path, Map<String, String> h, byte[] body) {
            String auth = h.get("authorization");
            if (auth == null || !auth.startsWith("AWS4-HMAC-SHA256 ")) {
                return "MissingAuth";
            }
            Map<String, String> parts = new TreeMap<>();
            for (String p : auth.substring("AWS4-HMAC-SHA256 ".length()).split(",\\s*")) {
                parts.put(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
            }
            String[] cred = parts.get("Credential").split("/");
            if (!cred[0].equals(ak) || !cred[2].equals(REGION) || !cred[3].equals("s3")) {
                return "InvalidAccessKeyId";
            }
            String payloadHash = h.get("x-amz-content-sha256");
            if (!hex(sha256(body)).equals(payloadHash)) {
                return "XAmzContentSHA256Mismatch";
            }
            String amzDate = h.get("x-amz-date");
            String signedHeaders = parts.get("SignedHeaders");
            for (String must : List.of("host", "x-amz-date", "x-amz-content-sha256")) {
                if (!List.of(signedHeaders.split(";")).contains(must)) {
                    return "UnsignedRequiredHeader:" + must;
                }
            }
            String canonical = method + "\n" + path + "\n\n"
                    + java.util.Arrays.stream(signedHeaders.split(";")).map(n -> n + ":" + h.getOrDefault(n, "").trim() + "\n")
                            .collect(Collectors.joining())
                    + "\n" + signedHeaders + "\n" + payloadHash;
            String expect = sign(canonical, amzDate, cred[1]);
            return expect.equals(parts.get("Signature")) ? null : "SignatureDoesNotMatch";
        }

        private String verifyPresigned(String method, String path, String rawQuery, Map<String, String> h) {
            Map<String, String> q = new TreeMap<>();
            for (String p : rawQuery.split("&")) {
                q.put(p.substring(0, p.indexOf('=')), p.substring(p.indexOf('=') + 1));
            }
            String sig = q.remove("X-Amz-Signature");
            String amzDate = q.get("X-Amz-Date");
            String date = amzDate.substring(0, 8);
            if (!q.get("X-Amz-Credential").equals(ak + "%2F" + date + "%2F" + REGION + "%2Fs3%2Faws4_request")) {
                return "InvalidCredential";
            }
            String canonical = method + "\n" + path + "\n"
                    + q.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"))
                    + "\nhost:" + h.get("host") + "\n\nhost\nUNSIGNED-PAYLOAD";
            return sign(canonical, amzDate, date).equals(sig) ? null : "SignatureDoesNotMatch";
        }

        private String sign(String canonical, String amzDate, String date) {
            String scope = date + "/" + REGION + "/s3/aws4_request";
            String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
            byte[] k = mac(("AWS4" + sk).getBytes(StandardCharsets.UTF_8), date);
            k = mac(k, REGION);
            k = mac(k, "s3");
            k = mac(k, "aws4_request");
            return hex(mac(k, toSign));
        }

        private static byte[] mac(byte[] key, String data) {
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(key, "HmacSHA256"));
                return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static byte[] sha256(byte[] b) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(b);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        private static String hex(byte[] b) {
            return HexFormat.of().formatHex(b);
        }
    }
}
