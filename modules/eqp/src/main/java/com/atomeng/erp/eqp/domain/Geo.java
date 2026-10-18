package com.atomeng.erp.eqp.domain;

import java.util.List;

/**
 * 비행 경로의 승인 구역 이탈 판정 (FR-EQP-018, TC-EQP-010) — PostGIS 없는 환경용 순수 구현.
 * 경위도를 평면으로 보고(소구역 근사) 고도는 무시, 경계선 위 점은 안쪽으로 본다.
 * PostGIS 프로필에서는 설계 B3/Step 11 의 ST_Within(ST_Force2D(track), ST_Buffer(area, 5m)) 로 교체 예정.
 */
public final class Geo {

    private static final double EPS = 1e-12;

    private Geo() {}

    /** @param ring [[lng, lat], …] 외곽 링(닫힘 여부 무관) */
    public static boolean pointInPolygon(double lng, double lat, List<double[]> ring) {
        int n = ring.size();
        if (n < 3) {
            throw new IllegalArgumentException("폴리곤 꼭짓점은 3개 이상");
        }
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = ring.get(i)[0], yi = ring.get(i)[1];
            double xj = ring.get(j)[0], yj = ring.get(j)[1];
            if (onSegment(lng, lat, xi, yi, xj, yj)) {
                return true;
            }
            if ((yi > lat) != (yj > lat) && lng < (xj - xi) * (lat - yi) / (yj - yi) + xi) {
                inside = !inside;
            }
        }
        return inside;
    }

    /** 경로 점 중 하나라도 구역 밖이면 true */
    public static boolean anyOutside(List<double[]> track, List<double[]> ring) {
        return track.stream().anyMatch(p -> !pointInPolygon(p[0], p[1], ring));
    }

    private static boolean onSegment(double px, double py, double ax, double ay, double bx, double by) {
        double cross = (px - ax) * (by - ay) - (py - ay) * (bx - ax);
        if (Math.abs(cross) > EPS) {
            return false;
        }
        return px >= Math.min(ax, bx) - EPS && px <= Math.max(ax, bx) + EPS
                && py >= Math.min(ay, by) - EPS && py <= Math.max(ay, by) + EPS;
    }
}
