package com.atomeng.common.evidence;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 (S3) — JDK 만으로 구현(SDK 없음). 헤더 서명({@link #authorization})과 쿼리 서명(presign,
 * {@link #presignQuery})을 지원한다. 보안 검토 CMN-07 Object Lock 어댑터({@link S3ObjectLockStorage})용.
 * AWS 문서의 예시 서명값으로 검증한다(S3ObjectLockStorageTest).
 */
final class SigV4 {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    static final String UNSIGNED_PAYLOAD = "UNSIGNED-PAYLOAD";
    static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
    static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private SigV4() {}

    record Credentials(String accessKey, String secretKey) {
        Credentials {
            if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()) {
                throw new IllegalStateException("S3 자격 증명(access key · secret key)이 없습니다");
            }
        }

        @Override
        public String toString() {
            return "Credentials[accessKey=" + accessKey + ", secretKey=***]";
        }
    }

    static String amzDate(Instant t) {
        return AMZ_DATE.format(t);
    }

    /** Host 헤더 값(기본 포트면 포트 생략) */
    static String host(URI uri) {
        int port = uri.getPort();
        boolean dflt = port == -1 || ("http".equals(uri.getScheme()) && port == 80)
                || ("https".equals(uri.getScheme()) && port == 443);
        return dflt ? uri.getHost() : uri.getHost() + ":" + port;
    }

    /**
     * 헤더 서명 Authorization 값. headers 에는 서명할 헤더 전부(host · x-amz-date · x-amz-content-sha256 포함)를 넣는다.
     */
    static String authorization(String method, URI uri, Map<String, String> headers, String payloadHash, String amzDate,
                                String region, Credentials c) {
        SortedMap<String, String> h = lower(headers);
        String signed = String.join(";", h.keySet());
        String canonical = method + "\n" + canonicalUri(uri) + "\n" + canonicalQuery(uri.getRawQuery()) + "\n"
                + h.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue().trim() + "\n").collect(Collectors.joining())
                + "\n" + signed + "\n" + payloadHash;
        String scope = scope(amzDate, region);
        String signature = signature(canonical, amzDate, scope, region, c.secretKey());
        return ALGORITHM + " Credential=" + c.accessKey() + "/" + scope + ", SignedHeaders=" + signed
                + ", Signature=" + signature;
    }

    /** 쿼리 서명(presigned URL) — 서명 헤더는 host 만, 본문 해시는 UNSIGNED-PAYLOAD */
    static String presignQuery(String method, URI uri, long expiresSeconds, String amzDate, String region, Credentials c) {
        String scope = scope(amzDate, region);
        SortedMap<String, String> q = new TreeMap<>();
        q.put("X-Amz-Algorithm", ALGORITHM);
        q.put("X-Amz-Credential", c.accessKey() + "/" + scope);
        q.put("X-Amz-Date", amzDate);
        q.put("X-Amz-Expires", Long.toString(expiresSeconds));
        q.put("X-Amz-SignedHeaders", "host");
        String query = q.entrySet().stream().map(e -> enc(e.getKey()) + "=" + enc(e.getValue()))
                .collect(Collectors.joining("&"));
        String canonical = method + "\n" + canonicalUri(uri) + "\n" + query + "\n" + "host:" + host(uri) + "\n\n"
                + "host\n" + UNSIGNED_PAYLOAD;
        return query + "&X-Amz-Signature=" + signature(canonical, amzDate, scope, region, c.secretKey());
    }

    static String scope(String amzDate, String region) {
        return amzDate.substring(0, 8) + "/" + region + "/s3/aws4_request";
    }

    static String signature(String canonicalRequest, String amzDate, String scope, String region, String secret) {
        String toSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] k = hmac(("AWS4" + secret).getBytes(StandardCharsets.UTF_8), amzDate.substring(0, 8));
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        return HexFormat.of().formatHex(hmac(k, toSign));
    }

    /** S3 경로: 각 세그먼트를 RFC 3986 로 인코딩(슬래시 유지). 키는 이미 SAFE_KEY 문자만이라 대부분 그대로 */
    static String canonicalUri(URI uri) {
        String raw = uri.getRawPath();
        return raw == null || raw.isEmpty() ? "/" : raw;
    }

    static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return "";
        }
        SortedMap<String, String> q = new TreeMap<>();
        for (String part : rawQuery.split("&")) {
            int i = part.indexOf('=');
            q.put(i < 0 ? part : part.substring(0, i), i < 0 ? "" : part.substring(i + 1));
        }
        return q.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("&"));
    }

    /** RFC 3986 인코딩(SigV4 규칙: 공백 %20, ~ 그대로) */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    static SortedMap<String, String> lower(Map<String, String> headers) {
        SortedMap<String, String> h = new TreeMap<>();
        headers.forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v));
        return h;
    }

    static String sha256Hex(byte[] data) {
        return HexFormat.of().formatHex(digest("SHA-256", data));
    }

    static byte[] digest(String alg, byte[] data) {
        try {
            return MessageDigest.getInstance(alg).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
