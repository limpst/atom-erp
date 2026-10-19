package com.atomeng.erp.aip.domain.measure;

import java.math.BigDecimal;
import java.util.List;

/**
 * 균열 1개(연결요소)의 측정 결과 모델 (설계 B4.3 suggestions[] · B7.3). 폭 · 길이는 스케일이 있을 때만 채운다(BR-AIP-002).
 *
 * @param skeletonLengthPx 프루닝 후 중심선 길이(px) — (직교 1 + 대각 √2) 합 / 2
 * @param prunedSkeletonPixels 프루닝 후 중심선 픽셀 수
 * @param removedSpurPixels 프루닝으로 지운 픽셀 수
 * @param widthMaxPx 국부 폭(2·EDT−1) P99 — px
 * @param widthMaxMm 최대폭 = P99(mm, 소수 3자리) · widthMeanMm 평균 · widthP95Mm P95 · widthUncertMm 불확도
 * @param lengthM 길이(m, 소수 3자리)
 * @param flags NEAR_GRADE_BOUNDARY_0_3MM · LOW_RESOLUTION_RETAKE · MEASURE_REQUIRED · EMPTY_SKELETON
 */
public record CrackMeasurement(double skeletonLengthPx, int prunedSkeletonPixels, int removedSpurPixels, Double widthMaxPx,
                               BigDecimal widthMaxMm, BigDecimal widthMeanMm, BigDecimal widthP95Mm,
                               BigDecimal widthUncertMm, BigDecimal lengthM, List<String> flags) {

    public CrackMeasurement {
        flags = List.copyOf(flags);
    }

    public boolean measured() {
        return widthMaxMm != null;
    }
}
