package com.atomeng.erp.aip.domain.measure;

/**
 * 사진 스케일(mm/px)과 그 불확도. source = ARUCO · LASER · GAUGE · MANUAL · NONE.
 * NONE(또는 mm/px 없음)이면 폭 수치를 만들지 않는다(BR-AIP-002).
 */
public record Scale(String source, Double mmPerPx, Double mmPerPxUncert, Double reprojErrPx) {

    public static final Scale NONE = new Scale("NONE", null, null, null);

    public Scale {
        if (source == null || source.isBlank()) {
            source = "NONE";
        }
        if (mmPerPx != null && !(mmPerPx > 0)) {
            throw new IllegalArgumentException("mm/px 는 양수여야 합니다");
        }
    }

    public static Scale manual(double mmPerPx, double uncert) {
        return new Scale("MANUAL", mmPerPx, uncert, null);
    }

    public static Scale invalid(Double reprojErrPx) {
        return new Scale("NONE", null, null, reprojErrPx);
    }

    public boolean valid() {
        return !"NONE".equals(source) && mmPerPx != null;
    }
}
