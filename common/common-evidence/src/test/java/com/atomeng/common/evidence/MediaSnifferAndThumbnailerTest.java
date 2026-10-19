package com.atomeng.common.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 보안 검토 CORE-01(c): 매직 바이트 판별 · 썸네일 화소 상한(합성 이미지만) */
class MediaSnifferAndThumbnailerTest {

    static byte[] png(int w, int h) throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    static byte[] jpeg() throws Exception {
        var out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("CORE-01(c): 매직 바이트로 JPEG · PNG · PDF 판별, 신고 MIME 과 다르면 불일치")
    void sniff() throws Exception {
        assertThat(MediaSniffer.sniff(png(2, 2))).contains("image/png");
        assertThat(MediaSniffer.sniff(jpeg())).contains("image/jpeg");
        assertThat(MediaSniffer.sniff("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII))).contains("application/pdf");
        assertThat(MediaSniffer.sniff("<html><script>".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(MediaSniffer.matches("image/png", png(2, 2))).isTrue();
        assertThat(MediaSniffer.matches("image/jpeg", png(2, 2))).isFalse();
        assertThat(MediaSniffer.matches("IMAGE/JPG", jpeg())).isTrue();
        assertThat(MediaSniffer.allowed("text/html")).isFalse();
        assertThat(MediaSniffer.allowed("image/png")).isTrue();
    }

    @Test
    @DisplayName("CORE-01(c): 헤더가 거대한 해상도를 주장하는 PNG(압축 폭탄) → 디코드하지 않고 empty, 정상 이미지는 320px 썸네일")
    void pixelCap() throws Exception {
        byte[] small = png(4, 3);
        // IHDR(오프셋 16: width, 20: height)을 60000 × 60000 으로 고치고 CRC 재계산 — 36억 화소
        ByteBuffer b = ByteBuffer.wrap(small);
        b.putInt(16, 60_000).putInt(20, 60_000);
        CRC32 crc = new CRC32();
        crc.update(small, 12, 17);
        b.putInt(29, (int) crc.getValue());
        Thumbnailer t = new Thumbnailer();
        assertThat(t.thumbnail(new ByteArrayInputStream(small), Thumbnailer.THUMB_EDGE)).isEmpty();
        assertThat(new Thumbnailer(100).thumbnail(new ByteArrayInputStream(png(20, 20)), 320)).isEmpty();
        byte[] thumb = t.thumbnail(new ByteArrayInputStream(png(1600, 1200)), Thumbnailer.THUMB_EDGE).orElseThrow();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(thumb));
        assertThat(Math.max(img.getWidth(), img.getHeight())).isEqualTo(Thumbnailer.THUMB_EDGE);
        assertThat(t.thumbnail(new ByteArrayInputStream("not an image".getBytes(StandardCharsets.UTF_8)), 320)).isEmpty();
    }
}
