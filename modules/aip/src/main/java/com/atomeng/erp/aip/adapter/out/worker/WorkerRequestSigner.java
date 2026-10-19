package com.atomeng.erp.aip.adapter.out.worker;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ERP → AIP 워커 요청 서명 (보안 검토 AIP-06). 워커(services/aip-worker atomeng_aip/auth.py)가 같은 규칙으로 검증한다.
 * <pre>
 * X-Atom-Timestamp: 유닉스 초
 * X-Atom-Nonce:     임의 32 hex(재전송 차단 — 워커가 허용 창 동안 기억)
 * X-Atom-Signature: v1=hex(HMAC-SHA256(secret, ts + "\n" + nonce + "\n" + METHOD + "\n" + path + "\n" + hex(sha256(body))))
 * </pre>
 * 비밀은 32바이트 이상(ATOM_AIP_WORKER_SECRET). 값은 로그에 남기지 않는다.
 */
public final class WorkerRequestSigner {

    public static final String TIMESTAMP = "X-Atom-Timestamp";
    public static final String NONCE = "X-Atom-Nonce";
    public static final String SIGNATURE = "X-Atom-Signature";
    public static final int MIN_SECRET_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final Clock clock;

    public WorkerRequestSigner(byte[] secret, Clock clock) {
        if (secret == null || secret.length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("AIP 워커 공유 비밀(atom.aip.worker.shared-secret)은 "
                    + MIN_SECRET_BYTES + "바이트 이상이어야 합니다");
        }
        this.secret = secret.clone();
        this.clock = clock;
    }

    /** 요청 헤더 3종 */
    public Map<String, String> sign(String method, String path, byte[] body) {
        String ts = Long.toString(clock.instant().getEpochSecond());
        byte[] n = new byte[16];
        RANDOM.nextBytes(n);
        String nonce = HexFormat.of().formatHex(n);
        Map<String, String> h = new LinkedHashMap<>();
        h.put(TIMESTAMP, ts);
        h.put(NONCE, nonce);
        h.put(SIGNATURE, "v1=" + signature(secret, ts, nonce, method, path, body));
        return h;
    }

    public static String signature(byte[] secret, String ts, String nonce, String method, String path, byte[] body) {
        try {
            String bodyHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            String canonical = ts + "\n" + nonce + "\n" + method.toUpperCase(java.util.Locale.ROOT) + "\n" + path + "\n"
                    + bodyHash;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
