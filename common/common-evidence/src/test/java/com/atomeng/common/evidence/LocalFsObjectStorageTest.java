package com.atomeng.common.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFsObjectStorageTest {

    @TempDir
    Path root;

    private LocalFsObjectStorage storage() {
        return new LocalFsObjectStorage(root, Buckets.WORM,
                Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC));
    }

    private static ByteArrayInputStream bytes(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Step8: WORM 버킷(field-original)은 덮어쓰기·삭제 거부 (Object Lock 모사), temp-upload 는 자유")
    void wormBucket() {
        var s = storage();
        s.put(Buckets.TEMP_UPLOAD, "a/b.jpg", bytes("one"), "image/jpeg");
        s.put(Buckets.TEMP_UPLOAD, "a/b.jpg", bytes("two"), "image/jpeg");
        s.copy(Buckets.TEMP_UPLOAD, "a/b.jpg", Buckets.FIELD_ORIGINAL, "2026/x/b.jpg");
        assertThat(s.size(Buckets.FIELD_ORIGINAL, "2026/x/b.jpg")).isEqualTo(3);
        assertThatThrownBy(() -> s.put(Buckets.FIELD_ORIGINAL, "2026/x/b.jpg", bytes("evil"), null))
                .isInstanceOf(ObjectLockedException.class).hasMessageContaining("WORM");
        assertThatThrownBy(() -> s.delete(Buckets.FIELD_ORIGINAL, "2026/x/b.jpg"))
                .isInstanceOf(ObjectLockedException.class);
        s.delete(Buckets.TEMP_UPLOAD, "a/b.jpg");
        assertThat(s.exists(Buckets.TEMP_UPLOAD, "a/b.jpg")).isFalse();
    }

    @Test
    @DisplayName("경로 조작(../) 키 거부, presign 만료시각 = now + ttl")
    void safety() {
        var s = storage();
        assertThatThrownBy(() -> s.put(Buckets.TEMP_UPLOAD, "../escape.txt", bytes("x"), null))
                .isInstanceOf(IllegalArgumentException.class);
        var p = s.presignPut(Buckets.TEMP_UPLOAD, "k/1.jpg", Duration.ofMinutes(15));
        assertThat(p.expiresAt()).isEqualTo(Instant.parse("2026-10-19T00:15:00Z"));
        assertThat(p.url().toString()).startsWith("local://temp-upload/");
    }

    @Test
    @DisplayName("설계 11.2-6: 해시 체인 — 결정적, 이전 해시·캡처 시각이 바뀌면 다른 값")
    void chain() {
        UUID owner = UUID.fromString("0192f000-0000-7000-8000-00000000a001");
        String sha = HashChain.sha256(bytes("synthetic photo"));
        assertThat(sha).hasSize(64);
        Instant t = Instant.parse("2026-10-19T01:02:03.123456Z");
        String g = HashChain.link(null, sha, t, owner);
        assertThat(HashChain.link(null, sha, t, owner)).isEqualTo(g);
        assertThat(HashChain.link(g, sha, t, owner)).isNotEqualTo(g);
        assertThat(HashChain.link(null, sha, t.plusNanos(1000), owner)).isNotEqualTo(g);
    }

    @Test
    @DisplayName("썸네일: 1600×1200 → 최대 변 320, 이미지가 아니면 empty")
    void thumbnail() throws Exception {
        var img = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        var thumb = new Thumbnailer().thumbnail(new ByteArrayInputStream(out.toByteArray()), 320).orElseThrow();
        var read = ImageIO.read(new ByteArrayInputStream(thumb));
        assertThat(read.getWidth()).isEqualTo(320);
        assertThat(read.getHeight()).isEqualTo(240);
        assertThat(new Thumbnailer().thumbnail(bytes("not an image"), 320)).isEmpty();
    }
}
