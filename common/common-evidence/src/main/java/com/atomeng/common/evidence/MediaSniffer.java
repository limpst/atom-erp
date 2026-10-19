package com.atomeng.common.evidence;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * 매직 바이트로 실제 형식을 판별한다(보안 검토 CORE-01(c) · FLD-05) — 클라이언트가 신고한 MIME 을 믿지 않는다.
 * 허용 형식: JPEG · PNG · HEIC/HEIF · WebP · PDF.
 */
public final class MediaSniffer {

    /** 첨부로 받는 MIME 허용 목록 */
    public static final Set<String> ALLOWED = Set.of("image/jpeg", "image/png", "image/heic", "image/heif",
            "image/webp", "application/pdf");

    /** 판별에 읽는 앞부분 길이 */
    public static final int HEAD_BYTES = 16;

    private MediaSniffer() {}

    public static String normalize(String mime) {
        String m = mime == null ? "" : mime.trim().toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';');
        m = semi < 0 ? m : m.substring(0, semi).trim();
        return "image/jpg".equals(m) ? "image/jpeg" : m;
    }

    public static boolean allowed(String mime) {
        return ALLOWED.contains(normalize(mime));
    }

    /** 앞부분 바이트로 판별한 MIME (모르면 empty) */
    public static Optional<String> sniff(byte[] head) {
        if (head == null) {
            return Optional.empty();
        }
        if (starts(head, 0xFF, 0xD8, 0xFF)) {
            return Optional.of("image/jpeg");
        }
        if (starts(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of("image/png");
        }
        if (starts(head, '%', 'P', 'D', 'F', '-')) {
            return Optional.of("application/pdf");
        }
        if (head.length >= 12 && starts(head, 'R', 'I', 'F', 'F')
                && "WEBP".equals(new String(head, 8, 4, StandardCharsets.US_ASCII))) {
            return Optional.of("image/webp");
        }
        if (head.length >= 12 && "ftyp".equals(new String(head, 4, 4, StandardCharsets.US_ASCII))) {
            String brand = new String(head, 8, 4, StandardCharsets.US_ASCII);
            if (Set.of("heic", "heix", "hevc", "heim", "heis").contains(brand)) {
                return Optional.of("image/heic");
            }
            if (Set.of("mif1", "msf1").contains(brand)) {
                return Optional.of("image/heif");
            }
        }
        return Optional.empty();
    }

    /** 신고 MIME 과 실제 형식이 같은가(heic/heif 는 같은 계열로 본다) */
    public static boolean matches(String declared, byte[] head) {
        String d = normalize(declared);
        return sniff(head).map(s -> s.equals(d)
                || (s.startsWith("image/hei") && d.startsWith("image/hei"))).orElse(false);
    }

    public static byte[] head(InputStream in) {
        try (in) {
            return in.readNBytes(HEAD_BYTES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean starts(byte[] b, int... sig) {
        if (b.length < sig.length) {
            return false;
        }
        for (int i = 0; i < sig.length; i++) {
            if ((b[i] & 0xFF) != sig[i]) {
                return false;
            }
        }
        return true;
    }
}
