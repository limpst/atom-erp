package com.atomeng.common.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * S3 호환(MinIO · AWS S3) Object Lock 저장소 — 보안 검토 CMN-07 의 실제 WORM 저장소. JDK HttpClient + SigV4 서명만 쓴다(SDK 없음).
 *
 * <ul>
 *   <li><b>WORM 버킷</b>({@link Buckets#WORM}) PUT: {@code x-amz-object-lock-mode: COMPLIANCE} +
 *       {@code x-amz-object-lock-retain-until-date}(지금 + 보존 기간) + {@code If-None-Match: *}(기존 키 덮어쓰기 금지 —
 *       Object Lock 버킷은 버전 관리라 덮어쓰면 새 버전이 생기므로 조건부 쓰기로 막는다). 먼저 HEAD 로 있으면 즉시
 *       {@link ObjectLockedException}, 서버가 412 를 주어도 같은 예외</li>
 *   <li>무결성: 모든 PUT 에 {@code Content-MD5}(서버가 검증) + 서명된 {@code x-amz-content-sha256}(서버가 본문 해시 검증),
 *       응답 ETag(단일 PUT = MD5)가 다르면 실패</li>
 *   <li>삭제: WORM 버킷 키는 DELETE 요청을 보내지 않고 {@link ObjectLockedException}(COMPLIANCE 는 서버도 거부)</li>
 *   <li>자격 증명은 환경변수에서만(ATOM_EVIDENCE_S3_ACCESS_KEY · ATOM_EVIDENCE_S3_SECRET_KEY, EvidenceAutoConfiguration)</li>
 *   <li>경로 방식 URL({@code endpoint/bucket/key}) — 버킷 이름 = {@code bucketPrefix + 논리 버킷}</li>
 * </ul>
 * 버킷은 Object Lock 을 켠 상태로 미리 만들어 두어야 한다(운영 절차, deploy/prod/erp.env.example).
 */
public class S3ObjectLockStorage implements ObjectStorage {

    private static final Pattern SAFE_KEY = Pattern.compile("^[A-Za-z0-9._\\-/]+$");
    private static final Pattern SAFE_BUCKET = Pattern.compile("^[a-z0-9][a-z0-9.\\-]{1,61}[a-z0-9]$");
    private static final DateTimeFormatter RETAIN = DateTimeFormatter.ISO_INSTANT;
    /** 서버 경유 PUT 한 건 상한(첨부 상한 50MB + 여유) — 메모리 적재 방지 */
    static final long MAX_PUT_BYTES = 256L * 1024 * 1024;

    /**
     * @param endpoint     S3 엔드포인트(예: https://minio.internal:9000)
     * @param region       서명 영역(MinIO 기본 us-east-1)
     * @param bucketPrefix 실제 버킷 이름 접두어(예: "atom-prod-"), 없으면 ""
     * @param retention    COMPLIANCE 보존 기간(예: 10년)
     */
    public record Config(URI endpoint, String region, String bucketPrefix, Duration retention, Duration timeout) {
        public Config {
            if (endpoint == null || endpoint.getHost() == null
                    || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()))) {
                throw new IllegalStateException("atom.evidence.s3.endpoint 는 http(s)://host[:port] 여야 합니다: " + endpoint);
            }
            region = region == null || region.isBlank() ? "us-east-1" : region;
            bucketPrefix = bucketPrefix == null ? "" : bucketPrefix;
            retention = retention == null ? Duration.ofDays(3650) : retention;
            timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
            if (retention.isNegative() || retention.isZero()) {
                throw new IllegalStateException("atom.evidence.s3.retention 은 양수여야 합니다");
            }
        }
    }

    private final Config cfg;
    private final SigV4.Credentials creds;
    private final Set<String> wormBuckets;
    private final Clock clock;
    private final HttpClient http;

    public S3ObjectLockStorage(Config cfg, String accessKey, String secretKey, Set<String> wormBuckets, Clock clock) {
        this.cfg = cfg;
        this.creds = new SigV4.Credentials(accessKey, secretKey);
        this.wormBuckets = Set.copyOf(wormBuckets);
        this.clock = clock;
        this.http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
    }

    // ------------------------------------------------------------------ ObjectStorage

    @Override
    public PresignedUpload presignPut(String bucket, String key, Duration ttl) {
        if (wormBuckets.contains(bucket)) {
            // WORM 버킷에는 클라이언트가 직접 올리지 않는다(잠금 헤더 · 해시 검사는 서버 경유 put/copy 로만)
            throw new IllegalArgumentException("WORM 버킷에는 서명 URL 을 발급하지 않습니다: " + bucket);
        }
        long secs = Math.max(1, Math.min(ttl.toSeconds(), 7 * 24 * 3600));
        Instant now = clock.instant();
        URI base = uri(bucket, key);
        String q = SigV4.presignQuery("PUT", base, secs, SigV4.amzDate(now), cfg.region(), creds);
        return new PresignedUpload(URI.create(base + "?" + q), now.plusSeconds(secs));
    }

    @Override
    public void put(String bucket, String key, InputStream content, String contentType) {
        byte[] body;
        try {
            body = content.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, MAX_PUT_BYTES + 1));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (body.length > MAX_PUT_BYTES) {
            throw new IllegalArgumentException("서버 경유 PUT 상한(" + MAX_PUT_BYTES + " 바이트) 초과: " + bucket + "/" + key);
        }
        boolean worm = wormBuckets.contains(bucket);
        if (worm && exists(bucket, key)) {
            throw new ObjectLockedException(bucket, key);
        }
        byte[] md5 = SigV4.digest("MD5", body);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-MD5", Base64.getEncoder().encodeToString(md5));
        h.put("Content-Type", contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType);
        if (worm) {
            h.put("If-None-Match", "*");
            h.put("x-amz-object-lock-mode", "COMPLIANCE");
            h.put("x-amz-object-lock-retain-until-date",
                    RETAIN.format(clock.instant().plus(cfg.retention()).truncatedTo(ChronoUnit.SECONDS)));
        }
        HttpResponse<byte[]> r = send("PUT", bucket, key, body, h);
        if (worm && (r.statusCode() == 412 || r.statusCode() == 409)) {
            throw new ObjectLockedException(bucket, key);
        }
        requireOk(r, "PUT", bucket, key);
        String etag = r.headers().firstValue("ETag").map(v -> v.replace("\"", "").toLowerCase(Locale.ROOT)).orElse(null);
        if (etag != null && etag.matches("[0-9a-f]{32}") && !etag.equals(HexFormat.of().formatHex(md5))) {
            throw new IllegalStateException("S3 PUT 무결성 불일치(ETag ≠ MD5): " + bucket + "/" + key);
        }
    }

    @Override
    public InputStream get(String bucket, String key) {
        HttpResponse<InputStream> r = exchange("GET", bucket, key, null, Map.of(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() == 200) {
            return r.body();
        }
        try {
            r.body().close();
        } catch (IOException ignored) {
            // 오류 응답 본문은 버린다
        }
        if (r.statusCode() == 404) {
            throw new IllegalArgumentException("object not found: " + bucket + "/" + key);
        }
        throw new IllegalStateException("S3 GET 실패 " + r.statusCode() + ": " + bucket + "/" + key);
    }

    @Override
    public boolean exists(String bucket, String key) {
        HttpResponse<byte[]> r = send("HEAD", bucket, key, null, Map.of());
        if (r.statusCode() == 404) {
            return false;
        }
        requireOk(r, "HEAD", bucket, key);
        return true;
    }

    @Override
    public long size(String bucket, String key) {
        HttpResponse<byte[]> r = send("HEAD", bucket, key, null, Map.of());
        if (r.statusCode() == 404) {
            throw new IllegalArgumentException("object not found: " + bucket + "/" + key);
        }
        requireOk(r, "HEAD", bucket, key);
        return r.headers().firstValueAsLong("Content-Length").orElseThrow();
    }

    /** 서버측 복사 대신 GET → 잠금 · 해시 헤더를 붙인 PUT(대상이 WORM 이면 COMPLIANCE 잠금 · 덮어쓰기 금지 동일 적용) */
    @Override
    public void copy(String srcBucket, String srcKey, String dstBucket, String dstKey) {
        try (InputStream in = get(srcBucket, srcKey)) {
            put(dstBucket, dstKey, in, null);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void delete(String bucket, String key) {
        if (wormBuckets.contains(bucket)) {
            if (exists(bucket, key)) {
                throw new ObjectLockedException(bucket, key);   // DELETE 요청 자체를 보내지 않는다
            }
            return;
        }
        HttpResponse<byte[]> r = send("DELETE", bucket, key, null, Map.of());
        if (r.statusCode() != 404) {
            requireOk(r, "DELETE", bucket, key);
        }
    }

    // ------------------------------------------------------------------ HTTP

    URI uri(String bucket, String key) {
        if (!SAFE_BUCKET.matcher(cfg.bucketPrefix() + bucket).matches() || bucket.contains("..")) {
            throw new IllegalArgumentException("unsafe bucket: " + bucket);
        }
        if (key == null || !SAFE_KEY.matcher(key).matches() || key.contains("..") || key.startsWith("/")) {
            throw new IllegalArgumentException("unsafe key: " + key);
        }
        String base = cfg.endpoint().toString().replaceAll("/+$", "");
        return URI.create(base + "/" + cfg.bucketPrefix() + bucket + "/" + key);
    }

    private HttpResponse<byte[]> send(String method, String bucket, String key, byte[] body, Map<String, String> extra) {
        return exchange(method, bucket, key, body, extra, HttpResponse.BodyHandlers.ofByteArray());
    }

    private <T> HttpResponse<T> exchange(String method, String bucket, String key, byte[] body, Map<String, String> extra,
                                         HttpResponse.BodyHandler<T> handler) {
        URI u = uri(bucket, key);
        byte[] payload = body == null ? new byte[0] : body;
        String payloadHash = body == null ? SigV4.EMPTY_SHA256 : SigV4.sha256Hex(payload);
        String amzDate = SigV4.amzDate(clock.instant());
        Map<String, String> signed = new LinkedHashMap<>(extra);
        signed.put("host", SigV4.host(u));
        signed.put("x-amz-date", amzDate);
        signed.put("x-amz-content-sha256", payloadHash);
        String auth = SigV4.authorization(method, u, signed, payloadHash, amzDate, cfg.region(), creds);
        HttpRequest.Builder b = HttpRequest.newBuilder(u).timeout(cfg.timeout())
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
        signed.forEach((k, v) -> {
            if (!"host".equals(k)) {
                b.header(k, v);
            }
        });
        b.header("Authorization", auth);
        try {
            return http.send(b.build(), handler);
        } catch (IOException e) {
            throw new UncheckedIOException("S3 " + method + " 실패: " + bucket + "/" + key, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("S3 " + method + " 중단: " + bucket + "/" + key, e);
        }
    }

    private static void requireOk(HttpResponse<?> r, String method, String bucket, String key) {
        if (r.statusCode() / 100 != 2) {
            throw new IllegalStateException("S3 " + method + " 실패 " + r.statusCode() + ": " + bucket + "/" + key);
        }
    }
}
