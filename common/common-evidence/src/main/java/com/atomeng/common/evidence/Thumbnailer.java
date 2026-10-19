package com.atomeng.common.evidence;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Optional;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 썸네일 생성 (설계 11.2-8: 320px). 운영은 libvips 워커(webp)로 교체 예정 — 여기서는 JDK ImageIO(PNG).
 * <p>보안 검토 CORE-01(c): 압축 폭탄(작은 파일 · 거대한 해상도) 방어 — 헤더의 가로 × 세로를 먼저 읽어
 * {@link #MAX_PIXELS} 를 넘으면 디코드하지 않고 empty. 디코드는 서브샘플링으로 메모리를 줄인다.
 */
public class Thumbnailer {

    public static final int THUMB_EDGE = 320;
    /** 디코드 허용 최대 화소 수(1억 화소 ≈ 12000 × 8000, 현장 카메라 최대치 이상) */
    public static final long MAX_PIXELS = 100_000_000L;

    private final long maxPixels;

    public Thumbnailer() {
        this(MAX_PIXELS);
    }

    public Thumbnailer(long maxPixels) {
        this.maxPixels = maxPixels;
    }

    /** 이미지가 아니거나 화소 상한을 넘으면 empty */
    public Optional<byte[]> thumbnail(InputStream image, int maxEdge) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(image)) {
            if (iis == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(iis);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            BufferedImage src;
            try {
                reader.setInput(iis, true, true);
                long w0 = reader.getWidth(0);
                long h0 = reader.getHeight(0);
                if (w0 <= 0 || h0 <= 0 || w0 * h0 > maxPixels) {
                    return Optional.empty();
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int sub = (int) Math.max(1, Math.max(w0, h0) / (maxEdge * 2L));
                param.setSourceSubsampling(sub, sub, 0, 0);
                src = reader.read(0, param);
            } finally {
                reader.dispose();
            }
            double scale = Math.min(1.0, (double) maxEdge / Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
            int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = dst.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(src, 0, 0, w, h, null);
            } finally {
                g.dispose();
            }
            var out = new ByteArrayOutputStream();
            ImageIO.write(dst, "png", out);
            return Optional.of(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
