package com.atomeng.erp.dem.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 인접건축물 사전/사후 결함 매칭 (설계 10 B7.2, FR-DEM-018, TC-DEM-011) — 순수 도메인.
 * <ol>
 *   <li>결함을 (건물, 층, 벽체/부재) 그룹으로 묶는다</li>
 *   <li>그룹 안에서 도면 좌표 거리 ≤ maxDistance 이고 결함 유형이 같으면 후보 쌍</li>
 *   <li>후보 쌍을 헝가리안 알고리즘으로 1:1 최적 매칭(비용 = 거리)</li>
 *   <li>매칭 쌍: 균열폭 증가 ≥ widthGrowthMm 또는 길이 증가율 ≥ lengthGrowthPct → PROGRESSED, 아니면 SAME</li>
 *   <li>미매칭 사후 결함 = NEW, 미매칭 사전 결함 = REPAIRED_OR_UNCONFIRMED</li>
 * </ol>
 * 결과는 입력 순서와 무관하게 결정적(그룹 · 결함번호 정렬)이다.
 */
public final class AdjacentMatcher {

    public enum Kind { SAME, PROGRESSED, NEW, REPAIRED_OR_UNCONFIRMED }

    /** 결함 1건(좌표 m, 균열폭 mm, 길이 mm) */
    public record Defect(UUID id, String defectNo, String building, String floor, String memberRef, String type,
                         BigDecimal x, BigDecimal y, BigDecimal crackWidthMm, BigDecimal lengthMm) {
        String groupKey() {
            return building + "|" + floor + "|" + memberRef;
        }
    }

    public record Thresholds(BigDecimal maxDistanceM, BigDecimal widthGrowthMm, BigDecimal lengthGrowthPct) {}

    public record Row(Kind kind, String group, UUID preId, String preNo, UUID postId, String postNo,
                      BigDecimal distanceM, BigDecimal widthDeltaMm, BigDecimal lengthGrowthPct) {}

    public record Result(List<Row> rows, Map<Kind, Integer> summary) {}

    private static final double BIG = 1e9;

    private AdjacentMatcher() {}

    public static Result compare(List<Defect> pre, List<Defect> post, Thresholds t) {
        Map<String, List<Defect>> preByGroup = group(pre);
        Map<String, List<Defect>> postByGroup = group(post);
        TreeMap<String, Boolean> keys = new TreeMap<>();
        preByGroup.keySet().forEach(k -> keys.put(k, true));
        postByGroup.keySet().forEach(k -> keys.put(k, true));
        List<Row> rows = new ArrayList<>();
        for (String g : keys.keySet()) {
            rows.addAll(matchGroup(g, preByGroup.getOrDefault(g, List.of()), postByGroup.getOrDefault(g, List.of()), t));
        }
        Map<Kind, Integer> summary = new LinkedHashMap<>();
        for (Kind k : Kind.values()) {
            summary.put(k, 0);
        }
        rows.forEach(r -> summary.merge(r.kind(), 1, Integer::sum));
        return new Result(List.copyOf(rows), summary);
    }

    private static Map<String, List<Defect>> group(List<Defect> ds) {
        Map<String, List<Defect>> m = new TreeMap<>();
        for (Defect d : ds) {
            m.computeIfAbsent(d.groupKey(), k -> new ArrayList<>()).add(d);
        }
        m.values().forEach(l -> l.sort(Comparator.comparing(Defect::defectNo)));
        return m;
    }

    private static List<Row> matchGroup(String g, List<Defect> pre, List<Defect> post, Thresholds t) {
        int n = pre.size();
        int m = post.size();
        int k = Math.max(n, m);
        int[] assign = new int[n];
        java.util.Arrays.fill(assign, -1);
        if (n > 0 && m > 0) {
            double[][] cost = new double[k][k];
            for (int i = 0; i < k; i++) {
                for (int j = 0; j < k; j++) {
                    cost[i][j] = (i < n && j < m) ? cost(pre.get(i), post.get(j), t) : BIG;
                }
            }
            int[] colOfRow = Hungarian.solve(cost);
            for (int i = 0; i < n; i++) {
                int j = colOfRow[i];
                if (j >= 0 && j < m && cost[i][j] < BIG) {
                    assign[i] = j;
                }
            }
        }
        List<Row> rows = new ArrayList<>();
        boolean[] postMatched = new boolean[m];
        for (int i = 0; i < n; i++) {
            Defect a = pre.get(i);
            if (assign[i] < 0) {
                rows.add(new Row(Kind.REPAIRED_OR_UNCONFIRMED, g, a.id(), a.defectNo(), null, null, null, null, null));
                continue;
            }
            Defect b = post.get(assign[i]);
            postMatched[assign[i]] = true;
            BigDecimal dist = BigDecimal.valueOf(distance(a, b)).setScale(3, RoundingMode.HALF_UP);
            BigDecimal widthDelta = a.crackWidthMm() != null && b.crackWidthMm() != null
                    ? b.crackWidthMm().subtract(a.crackWidthMm()) : null;
            BigDecimal lengthPct = a.lengthMm() != null && b.lengthMm() != null && a.lengthMm().signum() > 0
                    ? b.lengthMm().subtract(a.lengthMm()).multiply(BigDecimal.valueOf(100))
                            .divide(a.lengthMm(), 2, RoundingMode.HALF_UP)
                    : null;
            boolean progressed = (widthDelta != null && widthDelta.compareTo(t.widthGrowthMm()) >= 0)
                    || (lengthPct != null && lengthPct.compareTo(t.lengthGrowthPct()) >= 0);
            rows.add(new Row(progressed ? Kind.PROGRESSED : Kind.SAME, g, a.id(), a.defectNo(), b.id(), b.defectNo(),
                    dist, widthDelta, lengthPct));
        }
        for (int j = 0; j < m; j++) {
            if (!postMatched[j]) {
                Defect b = post.get(j);
                rows.add(new Row(Kind.NEW, g, null, null, b.id(), b.defectNo(), null, null, null));
            }
        }
        return rows;
    }

    /** 후보 아님 = BIG. 같은 유형 + 거리 ≤ 임계 → 비용 = 거리 */
    static double cost(Defect a, Defect b, Thresholds t) {
        if (!a.type().equals(b.type())) {
            return BIG;
        }
        double d = distance(a, b);
        return d <= t.maxDistanceM().doubleValue() + 1e-9 ? d : BIG;
    }

    static double distance(Defect a, Defect b) {
        return Math.hypot(a.x().doubleValue() - b.x().doubleValue(), a.y().doubleValue() - b.y().doubleValue());
    }

    /** 정방 비용 행렬 최소 비용 완전 매칭(헝가리안, O(n³), 포텐셜 방식) */
    static final class Hungarian {

        private Hungarian() {}

        /** @return 행 i 에 배정된 열 */
        static int[] solve(double[][] a) {
            int n = a.length;
            double[] u = new double[n + 1];
            double[] v = new double[n + 1];
            int[] p = new int[n + 1];
            int[] way = new int[n + 1];
            for (int i = 1; i <= n; i++) {
                p[0] = i;
                int j0 = 0;
                double[] minv = new double[n + 1];
                boolean[] used = new boolean[n + 1];
                java.util.Arrays.fill(minv, Double.POSITIVE_INFINITY);
                do {
                    used[j0] = true;
                    int i0 = p[j0];
                    int j1 = 0;
                    double delta = Double.POSITIVE_INFINITY;
                    for (int j = 1; j <= n; j++) {
                        if (!used[j]) {
                            double cur = a[i0 - 1][j - 1] - u[i0] - v[j];
                            if (cur < minv[j]) {
                                minv[j] = cur;
                                way[j] = j0;
                            }
                            if (minv[j] < delta) {
                                delta = minv[j];
                                j1 = j;
                            }
                        }
                    }
                    for (int j = 0; j <= n; j++) {
                        if (used[j]) {
                            u[p[j]] += delta;
                            v[j] -= delta;
                        } else {
                            minv[j] -= delta;
                        }
                    }
                    j0 = j1;
                } while (p[j0] != 0);
                do {
                    int j1 = way[j0];
                    p[j0] = p[j1];
                    j0 = j1;
                } while (j0 != 0);
            }
            int[] colOfRow = new int[n];
            java.util.Arrays.fill(colOfRow, -1);
            for (int j = 1; j <= n; j++) {
                if (p[j] != 0) {
                    colOfRow[p[j] - 1] = j - 1;
                }
            }
            return colOfRow;
        }
    }
}
