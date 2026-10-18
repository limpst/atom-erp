package com.atomeng.erp.rpt.domain.drawing;

import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 외관조사망도 DXF 작성기 — AutoCAD R12(AC1009) ASCII, 외부 라이브러리 없음 (FR-RPT-004, 06-IMPL Step 7).
 * 06-IMPL 은 R2018(LWPOLYLINE · HATCH)이지만 이번 단계는 가장 호환성이 넓은 R12 의 POLYLINE/VERTEX/SEQEND · LINE ·
 * CIRCLE · TEXT 만 쓴다. 좌표 = 도면 mm. 레이어: 0 · GRID_AXIS · DEF_CRACK · DEF_SPALL · DEF_REBAR · DEF_EFFLOR ·
 * DEF_LEAK · DEF_LABEL · LEGEND. 텍스트는 ASCII 만(한글 STYLE 매핑은 CAD 표준 합의 후 — Step 7 팁).
 */
public final class DxfR12Writer {

    public static final List<String> LAYERS = List.of("0", "GRID_AXIS", "DEF_CRACK", "DEF_SPALL", "DEF_REBAR",
            "DEF_EFFLOR", "DEF_LEAK", "DEF_LABEL", "LEGEND");
    private static final int[] COLORS = {7, 8, 1, 3, 6, 4, 5, 2, 7};
    private static final BigDecimal LABEL_H = new BigDecimal("250");
    private static final BigDecimal MARK = new BigDecimal("300");

    /** 작성 결과: DXF 본문 · 엔티티 수(POLYLINE · LINE · CIRCLE · TEXT) · 결함별 레이어 */
    public record Sheet(String dxf, int entityCount, int defectCount) {}

    private final StringBuilder sb = new StringBuilder();
    private int entities;

    public static Sheet write(String buildingCode, String floorCode, List<Defect> defects) {
        return new DxfR12Writer().build(buildingCode, floorCode, defects);
    }

    private Sheet build(String buildingCode, String floorCode, List<Defect> input) {
        List<Defect> defects = input.stream().sorted(Comparator.comparing(Defect::id)).toList();
        BigDecimal[] ext = extents(defects);
        BigDecimal minX = ext[0].subtract(new BigDecimal("2000"));
        BigDecimal minY = ext[1].subtract(new BigDecimal("2000"));
        BigDecimal maxX = ext[2].add(new BigDecimal("2000"));
        BigDecimal maxY = ext[3].add(new BigDecimal("4000"));
        // HEADER
        pair(0, "SECTION");
        pair(2, "HEADER");
        pair(9, "$ACADVER");
        pair(1, "AC1009");
        pair(9, "$DWGCODEPAGE");
        pair(3, "ANSI_949");
        pair(9, "$INSBASE");
        point(BigDecimal.ZERO, BigDecimal.ZERO);
        pair(9, "$EXTMIN");
        point(minX, minY);
        pair(9, "$EXTMAX");
        point(maxX, maxY);
        pair(0, "ENDSEC");
        // TABLES: LTYPE · LAYER · STYLE
        pair(0, "SECTION");
        pair(2, "TABLES");
        pair(0, "TABLE");
        pair(2, "LTYPE");
        pair(70, "1");
        pair(0, "LTYPE");
        pair(2, "CONTINUOUS");
        pair(70, "0");
        pair(3, "Solid line");
        pair(72, "65");
        pair(73, "0");
        pair(40, "0.0");
        pair(0, "ENDTAB");
        pair(0, "TABLE");
        pair(2, "LAYER");
        pair(70, String.valueOf(LAYERS.size()));
        for (int i = 0; i < LAYERS.size(); i++) {
            pair(0, "LAYER");
            pair(2, LAYERS.get(i));
            pair(70, "0");
            pair(62, String.valueOf(COLORS[i]));
            pair(6, "CONTINUOUS");
        }
        pair(0, "ENDTAB");
        pair(0, "TABLE");
        pair(2, "STYLE");
        pair(70, "1");
        pair(0, "STYLE");
        pair(2, "STANDARD");
        pair(70, "0");
        pair(40, "0.0");
        pair(41, "1.0");
        pair(50, "0.0");
        pair(71, "0");
        pair(42, "2.5");
        pair(3, "txt");
        pair(4, "");
        pair(0, "ENDTAB");
        pair(0, "ENDSEC");
        pair(0, "SECTION");
        pair(2, "BLOCKS");
        pair(0, "ENDSEC");
        // ENTITIES
        pair(0, "SECTION");
        pair(2, "ENTITIES");
        polyline("GRID_AXIS", List.of(xy(minX, minY), xy(maxX, minY), xy(maxX, maxY), xy(minX, maxY)), true);
        text("LEGEND", minX.add(new BigDecimal("500")), maxY.subtract(new BigDecimal("1000")), new BigDecimal("400"),
                ascii("ATOM-ERP RPT DEFECT MAP " + buildingCode + " " + floorCode + " (" + defects.size() + ")"));
        text("LEGEND", minX.add(new BigDecimal("500")), maxY.subtract(new BigDecimal("1800")), LABEL_H,
                "CRACK=DEF_CRACK SPALL=DEF_SPALL REBAR=DEF_REBAR EFFLOR=DEF_EFFLOR LEAK=DEF_LEAK");
        for (Defect d : defects) {
            List<BigDecimal[]> pts = points(d);
            BigDecimal[] p0 = pts.get(0);
            String type = d.defectType() == null ? "CRACK" : d.defectType();
            switch (type) {
                case "CRACK" -> polyline("DEF_CRACK", pts.size() >= 2 ? pts
                        : List.of(p0, xy(p0[0].add(MARK), p0[1])), false);
                case "SPALL" -> {
                    BigDecimal[] p1 = pts.size() >= 2 ? pts.get(1) : xy(p0[0].add(MARK), p0[1].add(MARK));
                    polyline("DEF_SPALL", List.of(p0, xy(p1[0], p0[1]), p1, xy(p0[0], p1[1])), true);
                }
                case "REBAR_EXPOSURE" -> polyline("DEF_REBAR", List.of(p0, xy(p0[0].add(MARK), p0[1]),
                        xy(p0[0].add(MARK.divide(BigDecimal.TWO, RoundingMode.HALF_UP)), p0[1].add(MARK))), true);
                case "LEAK" -> circle("DEF_LEAK", p0, new BigDecimal("250"));
                default -> circle("DEF_EFFLOR", p0, new BigDecimal("200"));
            }
            String label = d.id() + (d.widthMm() != null ? " " + num(d.widthMm()) + "mm" : "")
                    + (d.areaM2() != null ? " " + num(d.areaM2()) + "m2" : "");
            text("DEF_LABEL", p0[0].add(new BigDecimal("100")), p0[1].add(new BigDecimal("150")), LABEL_H, ascii(label));
        }
        pair(0, "ENDSEC");
        pair(0, "EOF");
        return new Sheet(sb.toString(), entities, defects.size());
    }

    private void polyline(String layer, List<BigDecimal[]> pts, boolean closed) {
        entities++;
        pair(0, "POLYLINE");
        pair(8, layer);
        pair(66, "1");
        pair(10, "0.0");
        pair(20, "0.0");
        pair(30, "0.0");
        pair(70, closed ? "1" : "0");
        for (BigDecimal[] p : pts) {
            pair(0, "VERTEX");
            pair(8, layer);
            pair(10, num(p[0]));
            pair(20, num(p[1]));
            pair(30, "0.0");
        }
        pair(0, "SEQEND");
        pair(8, layer);
    }

    private void circle(String layer, BigDecimal[] c, BigDecimal r) {
        entities++;
        pair(0, "CIRCLE");
        pair(8, layer);
        pair(10, num(c[0]));
        pair(20, num(c[1]));
        pair(30, "0.0");
        pair(40, num(r));
    }

    private void text(String layer, BigDecimal x, BigDecimal y, BigDecimal h, String value) {
        entities++;
        pair(0, "TEXT");
        pair(8, layer);
        pair(10, num(x));
        pair(20, num(y));
        pair(30, "0.0");
        pair(40, num(h));
        pair(1, value);
    }

    private void point(BigDecimal x, BigDecimal y) {
        pair(10, num(x));
        pair(20, num(y));
        pair(30, "0.0");
    }

    private void pair(int code, String value) {
        sb.append(String.format(Locale.ROOT, "%3d", code)).append("\r\n").append(value).append("\r\n");
    }

    private static List<BigDecimal[]> points(Defect d) {
        List<BigDecimal[]> out = new ArrayList<>();
        if (d.polyline() != null) {
            for (List<BigDecimal> p : d.polyline()) {
                if (p != null && p.size() >= 2 && p.get(0) != null && p.get(1) != null) {
                    out.add(xy(p.get(0), p.get(1)));
                }
            }
        }
        if (out.isEmpty()) {
            // 좌표 없음 → 결함번호로 정한 격자 위치(그리드 fallback, A8 — VAL-DRAWING-POLYLINE-MISSING 경고)
            int h = Math.floorMod(d.id().hashCode(), 400);
            out.add(xy(BigDecimal.valueOf(1000L + (h % 20) * 1500L), BigDecimal.valueOf(1000L + (h / 20) * 1200L)));
        }
        return out;
    }

    private static BigDecimal[] extents(List<Defect> defects) {
        BigDecimal minX = null;
        BigDecimal minY = null;
        BigDecimal maxX = null;
        BigDecimal maxY = null;
        for (Defect d : defects) {
            for (BigDecimal[] p : points(d)) {
                minX = minX == null || p[0].compareTo(minX) < 0 ? p[0] : minX;
                minY = minY == null || p[1].compareTo(minY) < 0 ? p[1] : minY;
                maxX = maxX == null || p[0].compareTo(maxX) > 0 ? p[0] : maxX;
                maxY = maxY == null || p[1].compareTo(maxY) > 0 ? p[1] : maxY;
            }
        }
        if (minX == null) {
            return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("10000"), new BigDecimal("10000")};
        }
        return new BigDecimal[] {minX, minY, maxX, maxY};
    }

    private static BigDecimal[] xy(BigDecimal x, BigDecimal y) {
        return new BigDecimal[] {x, y};
    }

    /** 지수 표기 없는 소수(최대 3자리) — DXF 실수 값 */
    static String num(BigDecimal v) {
        BigDecimal s = v.setScale(3, RoundingMode.HALF_UP).stripTrailingZeros();
        String t = (s.scale() < 0 ? s.setScale(0) : s).toPlainString();
        return t.contains(".") ? t : t + ".0";
    }

    private static String ascii(String s) {
        return s.replaceAll("[^\\x20-\\x7E]", "?");
    }
}
