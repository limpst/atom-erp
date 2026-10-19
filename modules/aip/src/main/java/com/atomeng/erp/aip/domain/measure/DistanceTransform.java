package com.atomeng.erp.aip.domain.measure;

/**
 * 정확한 유클리드 거리변환(EDT) — 켜진 칸마다 가장 가까운 꺼진 칸(격자 안)까지의 거리(픽셀 중심 기준).
 * scipy.ndimage.distance_transform_edt 와 같은 정의. Felzenszwalb–Huttenlocher 분리형 알고리즘(행 → 열, 제곱거리).
 */
public final class DistanceTransform {

    private static final double INF = 1e20;

    private DistanceTransform() {}

    public static double[][] edt(BinaryGrid mask) {
        int h = mask.height();
        int w = mask.width();
        double[][] f = new double[h][w];
        // 1단계: 열 방향
        double[] col = new double[h];
        double[] colOut = new double[h];
        for (int c = 0; c < w; c++) {
            for (int r = 0; r < h; r++) {
                col[r] = mask.get(r, c) ? INF : 0.0;
            }
            dt1d(col, colOut, h);
            for (int r = 0; r < h; r++) {
                f[r][c] = colOut[r];
            }
        }
        // 2단계: 행 방향
        double[] row = new double[w];
        double[] rowOut = new double[w];
        double[][] out = new double[h][w];
        for (int r = 0; r < h; r++) {
            System.arraycopy(f[r], 0, row, 0, w);
            dt1d(row, rowOut, w);
            for (int c = 0; c < w; c++) {
                out[r][c] = mask.get(r, c) ? Math.sqrt(rowOut[c]) : 0.0;
            }
        }
        return out;
    }

    /** 1차원 제곱거리 하한 포락선(lower envelope of parabolas) */
    private static void dt1d(double[] f, double[] d, int n) {
        int[] v = new int[n];
        double[] z = new double[n + 1];
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            while (s <= z[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[v[k]] + (double) v[k] * v[k])) / (2.0 * q - 2.0 * v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            double dq = q - v[k];
            d[q] = dq * dq + f[v[k]];
        }
    }
}
