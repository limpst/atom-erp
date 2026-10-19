package com.atomeng.erp.rpt.domain.drawing;

import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 외관조사망도 미리보기 PNG (HWPX 삽입용, 06-IMPL Step 7 의 Batik 300dpi 대신 Java2D 단순 래스터).
 * DXF 와 같은 좌표(mm)를 이미지 픽셀로 축척한다. 결정적(같은 입력 = 같은 그림).
 */
public final class DefectMapPng {

    public record Png(byte[] bytes, int widthPx, int heightPx) {}

    /** 높이 상한 = 가로 × 4 (SEC-RPT-01) */
    static final int MAX_ASPECT = 4;

    private DefectMapPng() {}

    public static Png render(List<Defect> input, int widthPx) {
        List<Defect> defects = input.stream().sorted(Comparator.comparing(Defect::id)).toList();
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        List<List<double[]>> shapes = new ArrayList<>();
        for (Defect d : defects) {
            List<double[]> pts = new ArrayList<>();
            if (d.polyline() != null) {
                for (List<BigDecimal> p : d.polyline()) {
                    if (p != null && p.size() >= 2 && p.get(0) != null && p.get(1) != null
                            && Double.isFinite(p.get(0).doubleValue()) && Double.isFinite(p.get(1).doubleValue())) {
                        pts.add(new double[] {p.get(0).doubleValue(), p.get(1).doubleValue()});
                    }
                }
            }
            shapes.add(pts);
            for (double[] p : pts) {
                minX = Math.min(minX, p[0]);
                minY = Math.min(minY, p[1]);
                maxX = Math.max(maxX, p[0]);
                maxY = Math.max(maxY, p[1]);
            }
        }
        if (minX > maxX) {
            minX = 0;
            minY = 0;
            maxX = 10_000;
            maxY = 10_000;
        }
        double pad = 1500;
        minX -= pad;
        minY -= pad;
        maxX += pad;
        maxY += pad;
        final double x0 = minX;
        final double yTop = maxY;
        // SEC-RPT-01: 세로로 긴 좌표(예: Y 범위 1e9 mm)는 가로 축척만 쓰면 높이가 수억 px 가 되어 OOM.
        // 높이를 가로의 MAX_ASPECT 배로 묶고, 두 축 중 더 작은 축척을 같이 쓴다(가로 · 세로 비율 유지).
        final double scale = Math.min(widthPx / (maxX - minX), (double) widthPx * MAX_ASPECT / (maxY - minY));
        int heightPx = Math.min(widthPx * MAX_ASPECT, Math.max(200, (int) Math.ceil((maxY - minY) * scale)));
        BufferedImage img = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, widthPx, heightPx);
            g.setColor(new Color(200, 200, 200));
            g.drawRect(2, 2, widthPx - 5, heightPx - 5);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            for (int i = 0; i < defects.size(); i++) {
                Defect d = defects.get(i);
                List<double[]> pts = shapes.get(i);
                if (pts.isEmpty()) {
                    continue;
                }
                int[] xs = pts.stream().mapToInt(p -> (int) Math.round((p[0] - x0) * scale)).toArray();
                int[] ys = pts.stream().mapToInt(p -> (int) Math.round((yTop - p[1]) * scale)).toArray();
                String type = d.defectType() == null ? "CRACK" : d.defectType();
                switch (type) {
                    case "CRACK" -> {
                        g.setColor(Color.RED);
                        g.setStroke(new BasicStroke(2f));
                        if (xs.length >= 2) {
                            g.drawPolyline(xs, ys, xs.length);
                        } else {
                            g.drawLine(xs[0], ys[0], xs[0] + 8, ys[0]);
                        }
                    }
                    case "SPALL" -> {
                        g.setColor(new Color(0, 140, 0));
                        int w = xs.length >= 2 ? Math.max(4, Math.abs(xs[1] - xs[0])) : 8;
                        int h = xs.length >= 2 ? Math.max(4, Math.abs(ys[1] - ys[0])) : 8;
                        g.fillRect(Math.min(xs[0], xs.length >= 2 ? xs[1] : xs[0]),
                                Math.min(ys[0], ys.length >= 2 ? ys[1] : ys[0]), w, h);
                    }
                    case "REBAR_EXPOSURE" -> {
                        g.setColor(Color.MAGENTA);
                        g.fillPolygon(new int[] {xs[0], xs[0] + 8, xs[0] + 4}, new int[] {ys[0], ys[0], ys[0] - 8}, 3);
                    }
                    default -> {
                        g.setColor(Color.BLUE);
                        g.drawOval(xs[0] - 4, ys[0] - 4, 8, 8);
                    }
                }
                g.setColor(Color.DARK_GRAY);
                g.drawString(d.id(), xs[0] + 4, ys[0] - 4);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Png(out.toByteArray(), widthPx, heightPx);
    }

}
