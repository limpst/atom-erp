package com.atomeng.common.evidence;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * 썸네일 생성 (설계 11.2-8: 320px). 운영은 libvips 워커(webp)로 교체 예정 — 여기서는 JDK ImageIO(PNG).
 */
public class Thumbnailer {

    public static final int THUMB_EDGE = 320;

    /** 이미지가 아니면 empty */
    public Optional<byte[]> thumbnail(InputStream image, int maxEdge) {
        try {
            BufferedImage src = ImageIO.read(image);
            if (src == null) {
                return Optional.empty();
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
