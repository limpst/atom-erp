"""측정 단위 시험 — TC-AIP-005 · 006 · 019, 스퍼 프루닝, 잡음 제거. 합성 마스크 · 합성 사진만."""
import math

import numpy as np
import pytest

from atomeng_aip.vision import segment, synth
from atomeng_aip.vision.measure import (Scale, boundary_flags, components, measure_component, measure_mask,
                                        prune_spurs, skeleton_length_px, skeletonize)
from atomeng_aip.vision.scale import scale_from_aruco


def line_skeleton(n: int, diagonal: bool) -> np.ndarray:
    s = np.zeros((n + 4, n + 4), bool)
    for i in range(n):
        s[(2 + i, 2 + i) if diagonal else (n // 2, 2 + i)] = True
    return s


def test_tc_aip_019_horizontal_101px_is_exactly_100():
    assert skeleton_length_px(line_skeleton(101, False)) == 100.0


def test_tc_aip_019_diagonal_101px_is_100_sqrt2_within_1pct():
    got = skeleton_length_px(line_skeleton(101, True))
    assert abs(got - 100 * math.sqrt(2)) / (100 * math.sqrt(2)) < 0.01
    assert got == pytest.approx(100 * math.sqrt(2), abs=1e-9)


def test_prune_removes_short_spur_keeps_main_line():
    s = line_skeleton(61, False)                       # 행 30, 열 2..62
    for k in range(1, 6):                              # 열 30 에서 위로 5px 가지
        s[30 - k, 30] = True
    pruned = prune_spurs(s, 10)
    assert pruned.sum() == 61
    assert skeleton_length_px(pruned) == 60.0
    long_spur = line_skeleton(61, False)
    for k in range(1, 13):                             # 12px 가지는 남는다
        long_spur[30 - k, 30] = True
    assert prune_spurs(long_spur, 10).sum() == 61 + 12


def test_prune_keeps_isolated_short_segment():
    s = np.zeros((10, 10), bool)
    s[5, 2:7] = True                                   # 5px 선분 전체(갈림점 없음) — 지우지 않음
    assert prune_spurs(s, 10).sum() == 5


def test_tc_aip_005_no_scale_no_width():
    img = synth.crack_photo(width_px=7, with_marker=False)
    s, markers = scale_from_aruco(img)
    assert s.source == "NONE" and not markers
    res = measure_mask(segment.dark_mask(img, 100, markers), s)
    assert res and all(c["widthMaxMm"] is None and "MEASURE_REQUIRED" in c["flags"] for c in res)


def test_tc_aip_006_known_width_030mm_with_aruco_scale():
    img = synth.crack_photo(width_px=7, marker_side_px=1167)       # 50mm / 1167px ≈ 0.04285 mm/px → 7px ≈ 0.300mm
    s, markers = scale_from_aruco(img)
    res = measure_mask(segment.dark_mask(img, 100, markers), s)
    assert len(res) == 1
    c = res[0]
    assert abs(c["widthMaxMm"] - 0.30) <= c["widthUncertMm"]
    assert "NEAR_GRADE_BOUNDARY_0_3MM" in c["flags"]
    assert c["lengthM"] == pytest.approx(900 * s.mm_per_px / 1000, rel=0.02)


def test_tc_aip_006_impl_variant_direct_scale():
    mask = np.zeros((40, 200), bool)
    mask[17:23, 20:180] = True                                      # 폭 6px
    m = measure_component(np.pad(mask, 1), skeletonize(np.pad(mask, 1)), Scale("MANUAL", 0.05, 0.0005))
    assert abs(m.width_max_mm - 0.30) <= m.width_uncert_mm
    assert "NEAR_GRADE_BOUNDARY_0_3MM" in boundary_flags(m.width_max_mm, m.width_uncert_mm, [0.1, 0.2, 0.3])


def test_small_noise_components_removed():
    mask = np.zeros((50, 50), bool)
    mask[5:8, 5:8] = True                                            # 9px 잡음
    mask[20:25, 2:48] = True
    comps = components(mask, 50)
    assert len(comps) == 1 and comps[0][1].sum() == 5 * 46


def test_low_resolution_flag():
    mask = np.zeros((30, 120), bool)
    mask[12:17, 10:110] = True
    m = measure_component(np.pad(mask, 1), skeletonize(np.pad(mask, 1)), Scale("MANUAL", 0.1, 0.0))
    assert "LOW_RESOLUTION_RETAKE" in m.flags
