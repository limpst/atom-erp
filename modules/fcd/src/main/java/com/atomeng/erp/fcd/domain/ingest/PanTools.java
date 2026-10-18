package com.atomeng.erp.fcd.domain.ingest;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * PAN(카드번호) 보호 — Luhn, HMAC-SHA256 토큰, 텍스트 마스킹, 스캐너 (BR-FCD-001, NFR-FCD-005, TC-FCD-001·030).
 * Python {@code atomeng_common.ingest.pan} 과 같은 규칙(같은 키면 같은 토큰).
 */
public final class PanTools {

    private PanTools() {}

    /** 13–19자리(공백·하이픈 허용) 숫자열. 소수(환율 등)는 제외 */
    private static final Pattern PAN_RE = Pattern.compile("(?<![\\d.])(?:\\d[ -]?){13,19}(?!\\d|\\.\\d)");

    public record CardToken(String token, int keyVersion, String first6, String last4) {
        public String masked() {
            return first6 + "******" + last4;
        }
    }

    public static boolean luhnValid(CharSequence s) {
        int[] d = s.chars().filter(Character::isDigit).map(c -> c - '0').toArray();
        if (d.length < 12) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < d.length; i++) {
            int x = d[d.length - 1 - i];
            if (i % 2 == 1) {
                x *= 2;
                if (x > 9) {
                    x -= 9;
                }
            }
            total += x;
        }
        return total % 10 == 0;
    }

    /** 합성 PAN 생성용 — 체크 디지트를 붙인다 */
    public static String luhnComplete(String prefix) {
        for (char c = '0'; c <= '9'; c++) {
            if (luhnValid(prefix + c)) {
                return prefix + c;
            }
        }
        throw new IllegalStateException("unreachable");
    }

    /** PAN → 토큰. 호출자 버퍼는 즉시 소거한다(PAN 은 메모리에서만 잠시 존재) */
    public static CardToken tokenize(char[] pan, byte[] key, int keyVersion) {
        try {
            StringBuilder sb = new StringBuilder(19);
            for (char c : pan) {
                if (Character.isDigit(c)) {
                    sb.append(c);
                }
            }
            if (sb.length() < 13 || sb.length() > 19 || !luhnValid(sb)) {
                throw new IllegalArgumentException("invalid PAN");
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digits = sb.toString().getBytes(StandardCharsets.US_ASCII);
            String token = HexFormat.of().formatHex(mac.doFinal(digits));
            CardToken t = new CardToken(token, keyVersion, sb.substring(0, 6), sb.substring(sb.length() - 4));
            Arrays.fill(digits, (byte) 0);
            sb.setLength(0);
            return t;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        } finally {
            Arrays.fill(pan, '\0');
        }
    }

    /** 13–19자리 숫자열 중 Luhn 통과분을 앞6 · 뒤4 만 남기고 마스킹 */
    public static String maskPans(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = PAN_RE.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String d = m.group().replaceAll("\\D", "");
            String rep = d.length() >= 13 && d.length() <= 19 && luhnValid(d)
                    ? d.substring(0, 6) + "*".repeat(d.length() - 10) + d.substring(d.length() - 4) : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** NFR-FCD-005 스캐너: 평문 PAN 후보(마스킹된 형태) */
    public static List<String> scanPans(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        Matcher m = PAN_RE.matcher(text);
        while (m.find()) {
            String d = m.group().replaceAll("\\D", "");
            if (d.length() >= 13 && d.length() <= 19 && luhnValid(d)) {
                out.add(d.substring(0, 6) + "******" + d.substring(d.length() - 4));
            }
        }
        return out;
    }
}
