package com.atomeng.erp.aip.domain.measure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 중심선(골격) 계산 규칙 (설계 11 v0.3 B7.3 · v0.2 역반영, TC-AIP-019).
 * <ul>
 *   <li>길이 px = (각 중심선 픽셀의 직교 이웃 수 합 × 1 + 대각 이웃 수 합 × √2) / 2 — 간선이 양 끝 픽셀에서 두 번 세어지므로 2 로 나눈다</li>
 *   <li>가지(spur) 프루닝: 끝점(교차수 1)에서 갈림점(교차수 ≥ 3)까지 길이(픽셀 수)가 기준(기본 10px) 미만이면 제거(1회)</li>
 * </ul>
 * Python 워커(atomeng_aip.vision.measure)와 같은 규칙 — 골든 픽스처(tools/aip_golden_gen.py)로 일치를 검사한다.
 */
public final class Skeletons {

    public static final int DEFAULT_PRUNE_PX = 10;
    static final double SQRT2 = Math.sqrt(2.0);

    /** 프루닝 탐색 이웃 순서(행 우선) — Python NEIGHBORS 와 같아야 한다 */
    static final int[][] NEIGHBORS = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 0}, {1, 1}};
    /** 교차수 원형 순서 N, NE, E, SE, S, SW, W, NW */
    static final int[][] RING = {{-1, 0}, {-1, 1}, {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1}, {-1, -1}};

    private Skeletons() {}

    public static double lengthPx(BinaryGrid skel) {
        long ortho = 0;
        long diag = 0;
        for (int r = 0; r < skel.height(); r++) {
            for (int c = 0; c < skel.width(); c++) {
                if (!skel.get(r, c)) {
                    continue;
                }
                ortho += on(skel, r - 1, c) + on(skel, r + 1, c) + on(skel, r, c - 1) + on(skel, r, c + 1);
                diag += on(skel, r - 1, c - 1) + on(skel, r - 1, c + 1) + on(skel, r + 1, c - 1) + on(skel, r + 1, c + 1);
            }
        }
        return (ortho * 1.0 + diag * SQRT2) / 2.0;
    }

    private static int on(BinaryGrid g, int r, int c) {
        return g.get(r, c) ? 1 : 0;
    }

    /** 8-이웃을 한 바퀴 돌며 0→1 전이 수 — 끝점 1, 선 위 2, 갈림점 3 이상 */
    public static int crossingNumber(BinaryGrid s, int r, int c) {
        int n = 0;
        for (int i = 0; i < 8; i++) {
            boolean a = s.get(r + RING[i][0], c + RING[i][1]);
            boolean b = s.get(r + RING[(i + 1) % 8][0], c + RING[(i + 1) % 8][1]);
            if (!a && b) {
                n++;
            }
        }
        return n;
    }

    /**
     * 10px(minLen) 미만 가지 제거. 끝점은 행 우선 순서로 처리하고, 한 걸음마다 아직 지나지 않은 이웃(고정 순서) 중
     * 갈림점이 있으면 지금까지 경로를 제거 대상으로, 없으면 첫 이웃으로 진행한다. 이웃이 없으면(성분 전체) 유지,
     * 경로가 minLen 에 닿으면 유지. 제거는 모든 끝점을 본 뒤 한꺼번에 적용한다.
     */
    public static BinaryGrid pruneSpurs(BinaryGrid skel, int minLen) {
        BinaryGrid s = skel.copy();
        if (minLen <= 1) {
            return s;
        }
        Set<Long> remove = new HashSet<>();
        for (int[] ep : skel.points()) {
            if (crossingNumber(skel, ep[0], ep[1]) != 1) {
                continue;
            }
            List<int[]> path = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            path.add(ep);
            seen.add(key(ep[0], ep[1]));
            int[] cur = ep;
            while (path.size() < minLen) {
                List<int[]> cands = new ArrayList<>();
                for (int[] d : NEIGHBORS) {
                    int rr = cur[0] + d[0];
                    int cc = cur[1] + d[1];
                    if (skel.get(rr, cc) && !seen.contains(key(rr, cc))) {
                        cands.add(new int[] {rr, cc});
                    }
                }
                if (cands.isEmpty()) {
                    break;
                }
                boolean junction = false;
                for (int[] p : cands) {
                    if (crossingNumber(skel, p[0], p[1]) >= 3) {
                        junction = true;
                        break;
                    }
                }
                if (junction) {
                    for (int[] p : path) {
                        remove.add(key(p[0], p[1]));
                    }
                    break;
                }
                cur = cands.get(0);
                path.add(cur);
                seen.add(key(cur[0], cur[1]));
            }
        }
        for (long k : remove) {
            s.set((int) (k >> 32), (int) k, false);
        }
        return s;
    }

    private static long key(int r, int c) {
        return ((long) r << 32) | (c & 0xffffffffL);
    }
}
