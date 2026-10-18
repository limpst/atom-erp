package com.atomeng.common.evidence;

import com.atomeng.common.core.util.Hashing;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 증거 해시 (설계 11.2-4·6).
 * chain_hash = SHA-256( prev_hash | sha256 | captured_at(UTC, µs) | owner_id ), 첫 고리는 prev = "GENESIS".
 */
public final class HashChain {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);

    private HashChain() {}

    /** 스트리밍 SHA-256 (대용량 원본도 메모리에 올리지 않음) */
    public static String sha256(InputStream in) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var din = new DigestInputStream(in, md)) {
                din.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String link(String prevHash, String sha256, Instant capturedAt, UUID ownerId) {
        String ts = capturedAt == null ? "" : TS.format(capturedAt);
        return Hashing.sha256Hex(String.join("|", prevHash == null ? "GENESIS" : prevHash, sha256, ts,
                String.valueOf(ownerId)));
    }
}
