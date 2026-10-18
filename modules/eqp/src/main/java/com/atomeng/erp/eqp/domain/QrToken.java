package com.atomeng.erp.eqp.domain;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * QR 토큰 (설계 B7.4): {@code eqp1.{code}.{issuedEpoch}.{base64url(HMAC-SHA256(qr_secret, code|issuedEpoch))[0:16]}}.
 * 위조 라벨 · 다른 회사 장비 오인식을 막는다. 라벨 재출력 시 qr_secret 회전 → 이전 라벨 서명 불일치.
 * 서명 비교는 상수 시간(MessageDigest.isEqual).
 */
public final class QrToken {

    public static final String PREFIX = "eqp1";
    private static final int SIG_LEN = 16;
    private static final Pattern CODE = Pattern.compile("^EQ-[A-Z0-9-]{3,24}$");

    /** 서명 검증 전 파싱 결과(코드로 장비를 찾아 그 장비의 secret 으로 검증한다) */
    public record Parsed(String code, long issuedEpoch, String signature) {}

    private QrToken() {}

    public static String issue(String code, long issuedEpoch, byte[] secret) {
        return PREFIX + "." + code + "." + issuedEpoch + "." + sign(code, issuedEpoch, secret);
    }

    /** @throws IllegalArgumentException 형식 오류 */
    public static Parsed parse(String token) {
        if (token == null || token.length() > 200) {
            throw new IllegalArgumentException("QR 토큰 형식 오류");
        }
        String[] p = token.split("\\.", -1);
        if (p.length != 4 || !PREFIX.equals(p[0]) || !CODE.matcher(p[1]).matches() || !p[2].matches("^\\d{1,12}$")
                || p[3].length() != SIG_LEN) {
            throw new IllegalArgumentException("QR 토큰 형식 오류");
        }
        return new Parsed(p[1], Long.parseLong(p[2]), p[3]);
    }

    public static boolean verify(Parsed parsed, byte[] secret) {
        byte[] expected = sign(parsed.code(), parsed.issuedEpoch(), secret).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, parsed.signature().getBytes(StandardCharsets.US_ASCII));
    }

    static String sign(String code, long issuedEpoch, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((code + "|" + issuedEpoch).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, SIG_LEN);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 사용 불가", e);
        }
    }
}
