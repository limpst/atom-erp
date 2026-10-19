"""스케일 보정 시험 — TC-AIP-003 · 021, 레이저 공식. 합성 마커 이미지만."""
import math

import pytest

from atomeng_aip.vision import segment, synth
from atomeng_aip.vision.measure import measure_mask
from atomeng_aip.vision.scale import (REPROJ_LIMIT_PX, detect_markers, scale_from_aruco, scale_from_laser,
                                      scale_from_markers)


@pytest.mark.parametrize("side", [400, 800, 1167])
def test_tc_aip_003_frontal_marker_within_1pct(side):
    img = synth.crack_photo(width_px=7, marker_side_px=side)
    s, markers = scale_from_aruco(img, marker_mm=50.0)
    gt = 50.0 / side
    assert s.source == "ARUCO" and len(markers) == 1
    assert abs(s.mm_per_px - gt) / gt < 0.01
    assert s.reproj_err_px == pytest.approx(0.0, abs=1e-6)          # 마커 1개(4점)는 항상 ≈0 — 검증 효과 없음(v0.2 주의)
    assert s.mm_per_px_uncert == pytest.approx(s.mm_per_px * 0.5 * math.sqrt(2) / (50.0 / s.mm_per_px), rel=1e-9)


def test_tc_aip_021_coplanar_two_marker_board_is_valid():
    img, layout, mm = synth.two_marker_board(lifted=False)
    markers = detect_markers(img)
    assert [m.marker_id for m in markers] == [0, 1]
    s, H = scale_from_markers(markers, mm, layout)
    assert s.source == "ARUCO" and H is not None
    assert s.reproj_err_px <= REPROJ_LIMIT_PX                        # px 단위


def test_tc_aip_021_lifted_marker_invalidates_scale_and_width():
    img, layout, mm = synth.two_marker_board(lifted=True)
    markers = detect_markers(img)
    assert [m.marker_id for m in markers] == [0, 1]
    s, H = scale_from_markers(markers, mm, layout)
    assert s.source == "NONE" and H is None and s.reproj_err_px > REPROJ_LIMIT_PX
    # 같은 사진에 균열을 그려도 폭은 만들지 않는다(BR-AIP-002)
    synth.draw_band(img, (100, 820), (900, 830), 5)
    res = measure_mask(segment.dark_mask(img, 100, markers), s)
    assert res and all(c["widthMaxMm"] is None and "MEASURE_REQUIRED" in c["flags"] for c in res)


def test_single_marker_ignores_layout_absent():
    img, layout, mm = synth.two_marker_board(lifted=True)
    s, _ = scale_from_markers(detect_markers(img), mm, None)        # 배치 정보 없으면 첫 마커 1개만 사용
    assert s.source == "ARUCO"


def test_laser_formula_and_tilt():
    s = scale_from_laser(1000, 6.17, 4.25, 4000)
    assert s.mm_per_px == pytest.approx(1000 * 6.17 / (4.25 * 4000))
    t = scale_from_laser(1000, 6.17, 4.25, 4000, tilt_deg=20)
    assert t.mm_per_px == pytest.approx(s.mm_per_px / math.cos(math.radians(20)))
    assert t.mm_per_px_uncert > s.mm_per_px_uncert
