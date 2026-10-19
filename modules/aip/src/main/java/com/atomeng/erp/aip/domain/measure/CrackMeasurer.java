package com.atomeng.erp.aip.domain.measure;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 균열폭 · 길이 측정 (설계 11 v0.3 B7.3 · 11-IMPL Step 6, BR-AIP-002 · 003) — 순수 도메인, 결정론적.
 * <ol>
 *   <li>중심선(워커가 세선화한 것) → 10px 미만 가지 프루닝 → 마스크와 교집합</li>
 *   <li>길이 px = {@link Skeletons#lengthPx}, 국부 폭 px = 2·EDT − 1 (0 이하 제외)</li>
 *   <li>스케일 없음 → 폭 · 길이 mm 없이 MEASURE_REQUIRED</li>
 *   <li>최대폭 = P99, 평균, P95 (numpy linear 백분위), 불확도 = √((1·mm/px)² + (최대폭 · 스케일 상대오차)²)</li>
 *   <li>|최대폭 − 경계| ≤ 불확도 → NEAR_GRADE_BOUNDARY_x_yMM, mm/px &gt; 0.05 → LOW_RESOLUTION_RETAKE</li>
 * </ol>
 * 반올림은 소수 3자리 HALF_EVEN(이진 값 그대로) — Python round() 와 같은 결과.
 */
public final class CrackMeasurer {

    /** ⚠️ 예시 경계 — 운영은 rule_param CRACK_WIDTH_GRADE_BOUNDARIES_MM(세부지침 최신본 확인 필요) */
    public static final List<Double> DEFAULT_BOUNDARIES_MM = List.of(0.1, 0.2, 0.3);
    public static final double LOW_RESOLUTION_MM_PER_PX = 0.05;

    private CrackMeasurer() {}

    public static CrackMeasurement measure(BinaryGrid mask, BinaryGrid skeleton, Scale scale, List<Double> boundariesMm,
                                           int prunePx) {
        BinaryGrid pruned = Skeletons.pruneSpurs(skeleton, prunePx).and(mask);
        int removed = skeleton.count() - pruned.count();
        double lengthPx = Skeletons.lengthPx(pruned);
        double[][] dist = DistanceTransform.edt(mask);
        List<Double> w = new ArrayList<>();
        for (int[] p : pruned.points()) {
            double v = 2.0 * dist[p[0]][p[1]] - 1.0;
            if (v > 0) {
                w.add(v);
            }
        }
        int prunedCount = pruned.count();
        if (w.isEmpty()) {
            return new CrackMeasurement(lengthPx, prunedCount, removed, null, null, null, null, null, null,
                    List.of("EMPTY_SKELETON"));
        }
        double[] px = w.stream().mapToDouble(Double::doubleValue).toArray();
        double wmaxPx = percentile(px, 99);
        if (scale == null || !scale.valid()) {
            return new CrackMeasurement(lengthPx, prunedCount, removed, wmaxPx, null, null, null, null, null,
                    List.of("MEASURE_REQUIRED"));
        }
        double mmpp = scale.mmPerPx();
        double mmppUnc = scale.mmPerPxUncert() == null ? 0.0 : scale.mmPerPxUncert();
        double[] mm = new double[px.length];
        double sum = 0;
        for (int i = 0; i < px.length; i++) {
            mm[i] = px[i] * mmpp;
            sum += mm[i];
        }
        double wmax = percentile(mm, 99);
        double unc = Math.sqrt(Math.pow(1.0 * mmpp, 2) + Math.pow(wmax * mmppUnc / mmpp, 2));
        BigDecimal wmaxR = round3(wmax);
        BigDecimal uncR = round3(unc);
        List<String> flags = new ArrayList<>(boundaryFlags(wmaxR.doubleValue(), uncR.doubleValue(), boundariesMm));
        if (mmpp > LOW_RESOLUTION_MM_PER_PX) {
            flags.add("LOW_RESOLUTION_RETAKE");
        }
        return new CrackMeasurement(lengthPx, prunedCount, removed, wmaxPx, wmaxR, round3(sum / mm.length),
                round3(percentile(mm, 95)), uncR, round3(lengthPx * mmpp / 1000.0), flags);
    }

    /** BR-AIP-003 경계 근접 플래그 */
    public static List<String> boundaryFlags(double wmax, double unc, List<Double> boundaries) {
        List<String> out = new ArrayList<>();
        for (double b : boundaries) {
            if (Math.abs(wmax - b) <= unc) {
                out.add("NEAR_GRADE_BOUNDARY_" + fmt(b) + "MM");
            }
        }
        return out;
    }

    static String fmt(double b) {
        String s = String.format(Locale.ROOT, "%.3f", b).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', '_');
    }

    /** numpy.percentile(linear) 와 같은 정의 */
    public static double percentile(double[] values, double q) {
        double[] v = values.clone();
        Arrays.sort(v);
        if (v.length == 1) {
            return v[0];
        }
        double pos = (v.length - 1) * q / 100.0;
        int lo = (int) Math.floor(pos);
        int hi = Math.min(lo + 1, v.length - 1);
        double frac = pos - lo;
        return v[lo] + (v[hi] - v[lo]) * frac;
    }

    static BigDecimal round3(double v) {
        return new BigDecimal(v).setScale(3, RoundingMode.HALF_EVEN);
    }
}
