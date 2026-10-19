package com.atomeng.erp.rpt.domain.drawing;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 보안 검토 2026-10-19 SEC-RPT-01: 극단 좌표로 망도 래스터가 수억 px 로 커지는 OOM 회귀 시험 */
class DefectMapPngBoundsTest {

    static Defect crack(String id, String x1, String y1, String x2, String y2) {
        return new Defect(id, "B01", "1F", "COLUMN", "C1", "CRACK", new BigDecimal("0.3"), null, null, null, null,
                List.of(List.of(new BigDecimal(x1), new BigDecimal(y1)), List.of(new BigDecimal(x2), new BigDecimal(y2))),
                null);
    }

    @Test
    @DisplayName("Y 범위 1e9 mm(같은 X) → 높이는 가로 × 4 이하로 묶이고 PNG 가 정상 생성된다")
    void tallDrawingIsClamped() {
        var png = DefectMapPng.render(List.of(crack("d1", "0", "0", "0", "1000000000")), 1200);
        assertThat(png.widthPx()).isEqualTo(1200);
        assertThat(png.heightPx()).isLessThanOrEqualTo(1200 * DefectMapPng.MAX_ASPECT);
        assertThat(png.bytes()).isNotEmpty();
    }

    @Test
    @DisplayName("double 범위를 넘는 좌표(1e400)는 무시하고, 보통 도면 크기는 그대로")
    void nonFiniteIgnoredAndNormalUnchanged() {
        var huge = DefectMapPng.render(List.of(crack("d1", "0", "0", "1e400", "1e400")), 1200);
        assertThat(huge.heightPx()).isLessThanOrEqualTo(1200 * DefectMapPng.MAX_ASPECT);
        var normal = DefectMapPng.render(List.of(crack("d1", "0", "0", "20000", "10000")), 1200);
        // 23,000 × 13,000 mm (패딩 포함) → 1200 × 679 (기존과 같은 축척)
        assertThat(normal.heightPx()).isEqualTo((int) Math.ceil(13000 * (1200.0 / 23000)));
    }
}
