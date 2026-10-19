package com.atomeng.erp.aip.domain.measure;

import java.util.List;

/**
 * ArUco 마커 스케일 (설계 11 v0.3 B7.1 · v0.2 정정, TC-AIP-003 · 021) — 순수 도메인.
 * <ul>
 *   <li>mm/px = 마커 한 변(mm) / 검출 코너로 잰 변 길이 평균(px), 불확도 = mm/px × (0.5√2 / 변 px)</li>
 *   <li>재투영 오차(px): 이미지 → 마커 평면(mm) 호모그래피(정규화 DLT 최소제곱)를 맞춘 뒤, 평면 좌표를 역변환해
 *       검출 코너와의 평균 거리(px). mm · px 를 섞지 않는다. 2px 초과 → 스케일 무효(BR-AIP-002)</li>
 *   <li>마커 1개(4점)는 호모그래피가 정확히 맞아 오차 ≈ 0 — 검증 효과가 없으므로 2마커 보드(8점)를 권장</li>
 * </ul>
 * Python 워커는 cv2.findHomography(method 0)를 쓴다 — 오차 값은 미세하게 다를 수 있으나 유효 판정과 mm/px 는 같아야 한다.
 */
public final class ArucoScale {

    public static final double REPROJ_LIMIT_PX = 2.0;

    /** 검출된 마커 1개: 코너 TL · TR · BR · BL(px) + 평면상 좌상단(mm) */
    public record MarkerCorners(int id, double[][] cornersPx, double[] planeTopLeftMm) {}

    private ArucoScale() {}

    public static Scale fromMarkers(List<MarkerCorners> markers, double markerMm) {
        if (markers == null || markers.isEmpty()) {
            return Scale.NONE;
        }
        int n = markers.size() * 4;
        double[][] img = new double[n][];
        double[][] plane = new double[n][];
        double sideSum = 0;
        int i = 0;
        for (MarkerCorners m : markers) {
            double x = m.planeTopLeftMm()[0];
            double y = m.planeTopLeftMm()[1];
            double[][] pc = {{x, y}, {x + markerMm, y}, {x + markerMm, y + markerMm}, {x, y + markerMm}};
            for (int k = 0; k < 4; k++) {
                img[i] = m.cornersPx()[k];
                plane[i] = pc[k];
                double[] a = m.cornersPx()[k];
                double[] b = m.cornersPx()[(k + 1) % 4];
                sideSum += Math.hypot(a[0] - b[0], a[1] - b[1]);
                i++;
            }
        }
        double sidePx = sideSum / n;
        double[][] h = homography(img, plane);
        double[][] inv = invert3(h);
        double err = 0;
        for (int k = 0; k < n; k++) {
            double[] back = apply(inv, plane[k]);
            err += Math.hypot(back[0] - img[k][0], back[1] - img[k][1]);
        }
        err /= n;
        if (err > REPROJ_LIMIT_PX) {
            return Scale.invalid(err);
        }
        double mmPerPx = markerMm / sidePx;
        return new Scale("ARUCO", mmPerPx, mmPerPx * (0.5 * Math.sqrt(2.0)) / sidePx, err);
    }

    /** src → dst 호모그래피(정규화 DLT, h33 = 1 최소제곱) */
    static double[][] homography(double[][] src, double[][] dst) {
        double[][] ts = normalizer(src);
        double[][] td = normalizer(dst);
        int n = src.length;
        double[][] ata = new double[8][8];
        double[] atb = new double[8];
        for (int k = 0; k < n; k++) {
            double[] s = apply(ts, src[k]);
            double[] d = apply(td, dst[k]);
            double x = s[0];
            double y = s[1];
            double u = d[0];
            double v = d[1];
            double[][] rows = {{x, y, 1, 0, 0, 0, -u * x, -u * y}, {0, 0, 0, x, y, 1, -v * x, -v * y}};
            double[] rhs = {u, v};
            for (int r = 0; r < 2; r++) {
                for (int a = 0; a < 8; a++) {
                    atb[a] += rows[r][a] * rhs[r];
                    for (int b = 0; b < 8; b++) {
                        ata[a][b] += rows[r][a] * rows[r][b];
                    }
                }
            }
        }
        double[] p = solve(ata, atb);
        double[][] hn = {{p[0], p[1], p[2]}, {p[3], p[4], p[5]}, {p[6], p[7], 1.0}};
        double[][] h = mul(invert3(td), mul(hn, ts));
        double s = h[2][2];
        for (double[] row : h) {
            for (int c = 0; c < 3; c++) {
                row[c] /= s;
            }
        }
        return h;
    }

    /** Hartley 정규화: 중심 원점 · 평균 거리 √2 */
    private static double[][] normalizer(double[][] pts) {
        double cx = 0;
        double cy = 0;
        for (double[] p : pts) {
            cx += p[0];
            cy += p[1];
        }
        cx /= pts.length;
        cy /= pts.length;
        double md = 0;
        for (double[] p : pts) {
            md += Math.hypot(p[0] - cx, p[1] - cy);
        }
        md /= pts.length;
        double s = md == 0 ? 1.0 : Math.sqrt(2.0) / md;
        return new double[][] {{s, 0, -s * cx}, {0, s, -s * cy}, {0, 0, 1}};
    }

    static double[] apply(double[][] h, double[] p) {
        double w = h[2][0] * p[0] + h[2][1] * p[1] + h[2][2];
        return new double[] {(h[0][0] * p[0] + h[0][1] * p[1] + h[0][2]) / w,
                (h[1][0] * p[0] + h[1][1] * p[1] + h[1][2]) / w};
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] c = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    c[i][j] += a[i][k] * b[k][j];
                }
            }
        }
        return c;
    }

    static double[][] invert3(double[][] m) {
        double a = m[0][0];
        double b = m[0][1];
        double c = m[0][2];
        double d = m[1][0];
        double e = m[1][1];
        double f = m[1][2];
        double g = m[2][0];
        double h = m[2][1];
        double i = m[2][2];
        double det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g);
        if (Math.abs(det) < 1e-300) {
            throw new IllegalArgumentException("특이 행렬(호모그래피 계산 불가)");
        }
        return new double[][] {
                {(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det},
                {(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det},
                {(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det}};
    }

    /** 가우스 소거(부분 피벗) */
    private static double[] solve(double[][] a0, double[] b0) {
        int n = b0.length;
        double[][] a = new double[n][];
        for (int r = 0; r < n; r++) {
            a[r] = a0[r].clone();
        }
        double[] b = b0.clone();
        for (int col = 0; col < n; col++) {
            int piv = col;
            for (int r = col + 1; r < n; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[piv][col])) {
                    piv = r;
                }
            }
            if (Math.abs(a[piv][col]) < 1e-15) {
                throw new IllegalArgumentException("호모그래피 연립방정식이 특이함(점 배치 확인)");
            }
            double[] t = a[col];
            a[col] = a[piv];
            a[piv] = t;
            double tb = b[col];
            b[col] = b[piv];
            b[piv] = tb;
            for (int r = col + 1; r < n; r++) {
                double f = a[r][col] / a[col][col];
                b[r] -= f * b[col];
                for (int c = col; c < n; c++) {
                    a[r][c] -= f * a[col][c];
                }
            }
        }
        double[] x = new double[n];
        for (int r = n - 1; r >= 0; r--) {
            double s = b[r];
            for (int c = r + 1; c < n; c++) {
                s -= a[r][c] * x[c];
            }
            x[r] = s / a[r][r];
        }
        return x;
    }
}
