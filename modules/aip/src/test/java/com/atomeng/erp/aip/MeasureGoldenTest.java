package com.atomeng.erp.aip;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.atomeng.erp.aip.domain.measure.ArucoScale;
import com.atomeng.erp.aip.domain.measure.BinaryGrid;
import com.atomeng.erp.aip.domain.measure.CrackMeasurement;
import com.atomeng.erp.aip.domain.measure.CrackMeasurer;
import com.atomeng.erp.aip.domain.measure.Scale;
import com.atomeng.erp.aip.domain.measure.Skeletons;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 골든 패리티 — tools/aip_golden_gen.py 가 Python 워커(OpenCV · scikit-image · scipy)로 만든 합성 사례를 Java 도메인이 같은 값으로
 * 재현하는지 검사한다(TC-AIP-006 · 019 · 021). 측정: 같은 중심선 입력 → 프루닝 · 길이 · EDT 폭 · 반올림 · 플래그 완전 일치.
 * 스케일: 같은 코너 → mm/px · 불확도 일치 · 유효 판정 일치, 재투영 오차는 알고리즘 차(cv2 LM 정밀화 vs 정규화 DLT)로
 * 유효 사례 ±0.05px · 무효 사례 상대 3%.
 */
class MeasureGoldenTest {

    static final ObjectMapper M = new ObjectMapper();

    static JsonNode golden() throws Exception {
        try (InputStream in = MeasureGoldenTest.class.getResourceAsStream("/aip/golden/measure-golden.json")) {
            return M.readTree(in);
        }
    }

    static Stream<Arguments> measureCases() throws Exception {
        List<Arguments> out = new ArrayList<>();
        golden().path("measure").forEach(c -> out.add(Arguments.of(c.path("id").asText(), c)));
        return out.stream();
    }

    static Stream<Arguments> scaleCases() throws Exception {
        List<Arguments> out = new ArrayList<>();
        golden().path("scale").forEach(c -> out.add(Arguments.of(c.path("id").asText(), c)));
        return out.stream();
    }

    static void decEq(String what, BigDecimal actual, BigDecimal expected) {
        if (expected == null) {
            assertThat(actual).as(what).isNull();
        } else {
            assertThat(actual).as(what).isEqualByComparingTo(expected);
        }
    }

    static BigDecimal dec(JsonNode n) {
        return n == null || n.isNull() ? null : new BigDecimal(n.asText());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("measureCases")
    @DisplayName("TC-AIP-006 · 019 측정 골든: Java CrackMeasurer = Python measure_component")
    void measureParity(String id, JsonNode c) {
        int h = c.path("height").asInt();
        int w = c.path("width").asInt();
        List<int[]> runs = new ArrayList<>();
        c.path("maskRuns").forEach(r -> runs.add(new int[] {r.get(0).asInt(), r.get(1).asInt(), r.get(2).asInt()}));
        List<int[]> skel = new ArrayList<>();
        c.path("skeleton").forEach(p -> skel.add(new int[] {p.get(0).asInt(), p.get(1).asInt()}));
        JsonNode s = c.path("scale");
        Scale scale = new Scale(s.path("source").asText(), s.path("mmPerPx").isNull() ? null : s.path("mmPerPx").asDouble(),
                s.path("mmPerPxUncert").isNull() ? null : s.path("mmPerPxUncert").asDouble(), null);
        List<Double> bounds = new ArrayList<>();
        c.path("boundariesMm").forEach(b -> bounds.add(b.asDouble()));

        CrackMeasurement m = CrackMeasurer.measure(BinaryGrid.fromRuns(h, w, runs), BinaryGrid.fromPoints(h, w, skel), scale,
                bounds, c.path("prunePx").asInt());
        JsonNode e = c.path("expected");
        assertThat(m.prunedSkeletonPixels()).as(id + " pruned").isEqualTo(e.path("prunedSkeletonPixels").asInt());
        assertThat(m.removedSpurPixels()).as(id + " removed").isEqualTo(e.path("removedSpurPixels").asInt());
        assertThat(m.skeletonLengthPx()).as(id + " length px").isCloseTo(e.path("skeletonLengthPx").asDouble(), within(1e-9));
        if (e.path("widthMaxPx").isNull()) {
            assertThat(m.widthMaxPx()).isNull();
        } else {
            assertThat(m.widthMaxPx()).as(id + " width px").isCloseTo(e.path("widthMaxPx").asDouble(), within(1e-9));
        }
        decEq(id + " wmax", m.widthMaxMm(), dec(e.get("widthMaxMm")));
        decEq(id + " wmean", m.widthMeanMm(), dec(e.get("widthMeanMm")));
        decEq(id + " wp95", m.widthP95Mm(), dec(e.get("widthP95Mm")));
        decEq(id + " unc", m.widthUncertMm(), dec(e.get("widthUncertMm")));
        decEq(id + " length m", m.lengthM(), dec(e.get("lengthM")));
        List<String> flags = new ArrayList<>();
        e.path("flags").forEach(f -> flags.add(f.asText()));
        assertThat(m.flags()).as(id + " flags").containsExactlyElementsOf(flags);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scaleCases")
    @DisplayName("TC-AIP-003 · 021 스케일 골든: Java ArucoScale = Python scale_from_markers(px 단위 재투영 오차)")
    void scaleParity(String id, JsonNode c) {
        List<ArucoScale.MarkerCorners> markers = new ArrayList<>();
        for (JsonNode mk : c.path("markers")) {
            double[][] corners = new double[4][2];
            for (int k = 0; k < 4; k++) {
                corners[k][0] = mk.path("cornersPx").get(k).get(0).asDouble();
                corners[k][1] = mk.path("cornersPx").get(k).get(1).asDouble();
            }
            markers.add(new ArucoScale.MarkerCorners(mk.path("id").asInt(), corners,
                    new double[] {mk.path("planeTopLeftMm").get(0).asDouble(), mk.path("planeTopLeftMm").get(1).asDouble()}));
        }
        Scale s = ArucoScale.fromMarkers(markers, c.path("markerSizeMm").asDouble());
        JsonNode e = c.path("expected");
        assertThat(s.valid()).as(id + " valid").isEqualTo(e.path("valid").asBoolean());
        // 유효(작은 오차)는 ±0.05px, 무효(큰 오차)는 최소제곱 기준 차이(cv2 LM 정밀화 vs 대수 DLT)로 상대 3% 안
        double expErr = e.path("reprojErrPx").asDouble();
        assertThat(s.reprojErrPx()).as(id + " reproj px")
                .isCloseTo(expErr, within(e.path("valid").asBoolean() ? 0.05 : expErr * 0.03));
        if (e.path("valid").asBoolean()) {
            assertThat(s.mmPerPx()).isCloseTo(e.path("mmPerPx").asDouble(), within(1e-12));
            assertThat(s.mmPerPxUncert()).isCloseTo(e.path("mmPerPxUncert").asDouble(), within(1e-15));
            assertThat(s.reprojErrPx()).isLessThanOrEqualTo(ArucoScale.REPROJ_LIMIT_PX);
        } else {
            assertThat(s.mmPerPx()).isNull();
            assertThat(s.reprojErrPx()).isGreaterThan(ArucoScale.REPROJ_LIMIT_PX);
            // 스케일 무효 → 폭 미산출(BR-AIP-002)
            BinaryGrid band = new BinaryGrid(11, 40);
            for (int r = 2; r < 9; r++) {
                for (int col = 2; col < 38; col++) {
                    band.set(r, col, true);
                }
            }
            BinaryGrid line = new BinaryGrid(11, 40);
            for (int col = 3; col < 37; col++) {
                line.set(5, col, true);
            }
            CrackMeasurement m = CrackMeasurer.measure(band, line, s, CrackMeasurer.DEFAULT_BOUNDARIES_MM, 10);
            assertThat(m.widthMaxMm()).isNull();
            assertThat(m.flags()).containsExactly("MEASURE_REQUIRED");
        }
    }

    @org.junit.jupiter.api.Test
    @DisplayName("TC-AIP-019 (Java 단독): 수평 101px = 100 ± 0, 45° 대각 101px = 100√2 ± 1%")
    void skeletonLengthFormula() {
        BinaryGrid h = new BinaryGrid(3, 105);
        BinaryGrid d = new BinaryGrid(105, 105);
        for (int i = 0; i < 101; i++) {
            h.set(1, 2 + i, true);
            d.set(2 + i, 2 + i, true);
        }
        assertThat(Skeletons.lengthPx(h)).isEqualTo(100.0);
        assertThat(Skeletons.lengthPx(d)).isCloseTo(100 * Math.sqrt(2), within(100 * Math.sqrt(2) * 0.01));
    }
}
