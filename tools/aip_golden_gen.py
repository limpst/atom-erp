"""AIP 균열 측정 · 스케일 골든 픽스처 생성기 (11-IMPL Step 4 · 6, 설계 B7.1 · B7.3, TC-AIP-006 · 019 · 021).

Python 워커(services/aip-worker, atomeng_aip.vision — OpenCV · scikit-image · scipy)로 **합성** 마스크 · 마커 사례의 기대값을
만들고, Java 도메인(com.atomeng.erp.aip.domain.measure)이 같은 값을 내는지 MeasureGoldenTest 가 검사한다(언어 간 패리티).
회사 · 현장 사진은 쓰지 않는다.

  측정 사례(GOLD-Mxx): 마스크(행별 구간) + 세선화 중심선(skimage Zhang, 프루닝 전) + 스케일 → 기대값
    프루닝(10px 미만 가지) 후 중심선 픽셀 수 · 길이 px((직교 1 + 대각 √2) 합 / 2) · 폭 px P99(2·EDT−1) ·
    최대/평균/P95 폭 mm · 불확도 · 길이 m · 플래그(NEAR_GRADE_BOUNDARY_* · LOW_RESOLUTION_RETAKE · MEASURE_REQUIRED)
  스케일 사례(GOLD-Sxx): 검출된 ArUco 코너(px) + 평면 배치(mm) → mm/px · 불확도 · 재투영 오차(px) · 유효 여부(≤ 2px)

실행(워커 가상환경으로):
  cd services/aip-worker && uv run python ../../tools/aip_golden_gen.py
  → modules/aip/src/test/resources/aip/golden/measure-golden.json
"""
from __future__ import annotations

import json
import math
from pathlib import Path

import numpy as np

from atomeng_aip.vision import synth
from atomeng_aip.vision.measure import Scale, measure_component, prune_spurs, skeletonize
from atomeng_aip.vision.scale import detect_markers, scale_from_markers

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "modules/aip/src/test/resources/aip/golden/measure-golden.json"
BOUNDARIES = [0.1, 0.2, 0.3]   # ⚠️ 예시 경계(세부지침 최신본 확인 필요)


def runs(mask: np.ndarray) -> list[list[int]]:
    out = []
    for r in range(mask.shape[0]):
        row = mask[r]
        c = 0
        while c < row.size:
            if row[c]:
                c0 = c
                while c < row.size and row[c]:
                    c += 1
                out.append([r, c0, c])
            else:
                c += 1
    return out


def band_mask(h: int, w: int, segments: list[tuple[tuple[int, int], tuple[int, int], float]]) -> np.ndarray:
    img = np.zeros((h, w), np.uint8)
    for p0, p1, width in segments:
        synth.draw_band(img, p0, p1, width, 255)
    return img > 0


def measure_case(cid: str, desc: str, mask: np.ndarray, scale: Scale, prune_px: int = 10,
                 skel: np.ndarray | None = None) -> dict:
    mask = np.pad(mask, 2)
    raw = skeletonize(mask) if skel is None else np.pad(skel, 2)
    pruned = prune_spurs(raw, prune_px) & mask
    m = measure_component(mask, raw, scale, BOUNDARIES, prune_px)
    return {
        "id": cid, "description": desc, "height": int(mask.shape[0]), "width": int(mask.shape[1]),
        "maskRuns": runs(mask), "skeleton": [[int(r), int(c)] for r, c in zip(*np.nonzero(raw))],
        "scale": {"source": scale.source, "mmPerPx": scale.mm_per_px, "mmPerPxUncert": scale.mm_per_px_uncert},
        "boundariesMm": BOUNDARIES, "prunePx": prune_px,
        "expected": {"prunedSkeletonPixels": int(pruned.sum()), "removedSpurPixels": int(raw.sum() - pruned.sum()),
                     "skeletonLengthPx": m.skeleton_length_px, "widthMaxPx": m.width_max_px,
                     "widthMaxMm": m.width_max_mm, "widthMeanMm": m.width_mean_mm, "widthP95Mm": m.width_p95_mm,
                     "widthUncertMm": m.width_uncert_mm, "lengthM": m.length_m, "flags": m.flags},
    }


def scale_case(cid: str, desc: str, img: np.ndarray, marker_mm: float, layout: dict | None) -> dict:
    markers = detect_markers(img)
    s, _ = scale_from_markers(markers, marker_mm, layout)
    use = [m for m in markers if layout is None or m.marker_id in layout] if layout else markers[:1]
    return {"id": cid, "description": desc, "markerSizeMm": marker_mm,
            "markers": [{"id": m.marker_id, "cornersPx": m.corners_px.tolist(),
                         "planeTopLeftMm": list(layout[m.marker_id]) if layout else [0.0, 0.0]} for m in use],
            "expected": {"valid": s.source == "ARUCO", "mmPerPx": s.mm_per_px, "mmPerPxUncert": s.mm_per_px_uncert,
                         "reprojErrPx": s.reproj_err_px}}


def main() -> None:
    aruco_mmpp = 50.0 / 1167
    aruco = Scale("ARUCO", aruco_mmpp, aruco_mmpp * 0.5 * math.sqrt(2) / 1167)
    cases = []
    m = np.zeros((7, 150), bool); m[:, :] = True
    cases.append(measure_case("GOLD-M01", "수평 띠 폭 7px · ArUco 50mm/1167px → 0.300mm (TC-AIP-006)", m, aruco))
    m = np.zeros((6, 160), bool); m[:, :] = True
    cases.append(measure_case("GOLD-M02", "수평 띠 폭 6px · 0.05mm/px(11-IMPL 예시) → 2·EDT−1 = 5px", m,
                              Scale("MANUAL", 0.05, 0.0005)))
    cases.append(measure_case("GOLD-M03", "45° 대각 띠 폭 5px · 0.05mm/px",
                              band_mask(160, 160, [((10, 10), (150, 150), 5)]), Scale("MANUAL", 0.05, 0.0005)))
    t = band_mask(90, 200, [((10, 60), (190, 60), 5), ((100, 61), (100, 55), 3)])
    cases.append(measure_case("GOLD-M04", "T자 — 본선 폭 5px + 짧은 곁가지(프루닝 대상)", t, Scale("MANUAL", 0.05, 0.0005)))
    y = band_mask(220, 260, [((10, 200), (120, 110), 7), ((120, 110), (250, 30), 5), ((120, 110), (200, 210), 3)])
    cases.append(measure_case("GOLD-M05", "Y자 분기 균열 — 폭 7/5/3px 혼합 · 0.042849mm/px", y, aruco))
    m = np.zeros((7, 150), bool); m[:, :] = True
    cases.append(measure_case("GOLD-M06", "스케일 없음 → 폭 미산출 MEASURE_REQUIRED (BR-AIP-002, TC-AIP-005)", m,
                              Scale.none()))
    m = np.zeros((5, 120), bool); m[:, :] = True
    cases.append(measure_case("GOLD-M07", "저해상도 0.1mm/px → LOW_RESOLUTION_RETAKE", m, Scale("MANUAL", 0.1, 0.0)))
    line = np.zeros((1, 101), bool); line[0, :] = True
    cases.append(measure_case("GOLD-L01", "중심선 수평 101px → 길이 100 (TC-AIP-019)", line, Scale("MANUAL", 1.0, 0.0),
                              skel=line))
    diag = np.eye(101, dtype=bool)
    cases.append(measure_case("GOLD-L02", "중심선 45° 대각 101px → 길이 100√2 (TC-AIP-019)", diag,
                              Scale("MANUAL", 1.0, 0.0), skel=diag))
    spur = np.zeros((40, 80), bool); spur[20, 5:75] = True
    for k in range(1, 6):
        spur[20 - k, 40] = True
    cases.append(measure_case("GOLD-L03", "중심선 + 5px 곁가지(10px 미만 → 제거)", spur, Scale("MANUAL", 1.0, 0.0), skel=spur))
    stair = np.zeros((40, 80), bool)
    for c in range(5, 75):
        stair[10 + (c - 5) // 3, c] = True
    for k in range(1, 4):
        stair[10 + (40 - 5) // 3 + k, 40 + k] = True
    cases.append(measure_case("GOLD-L04", "계단형 완만 대각 중심선 + 3px 대각 곁가지", stair, Scale("MANUAL", 1.0, 0.0),
                              skel=stair))

    scales = [scale_case("GOLD-S01", "정면 마커 1개 50mm/1167px (TC-AIP-003)", synth.crack_photo(7, 1167), 50.0, None)]
    img, layout, mm = synth.two_marker_board(lifted=False)
    scales.append(scale_case("GOLD-S02", "2마커 보드 동일 평면(원근 포함) → 유효 (TC-AIP-021-1)", img, mm, layout))
    img, layout, mm = synth.two_marker_board(lifted=True)
    scales.append(scale_case("GOLD-S03", "2마커 보드, 마커 1 들뜸(×1.15) → 재투영 > 2px 무효 (TC-AIP-021-2)", img, mm, layout))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"generator": "tools/aip_golden_gen.py", "note": "합성 사례 — 실데이터 아님",
                               "measure": cases, "scale": scales}, ensure_ascii=False, indent=1) + "\n",
                   encoding="utf-8", newline="\n")
    print(f"wrote {OUT} ({len(cases)} measure, {len(scales)} scale)")


if __name__ == "__main__":
    main()
