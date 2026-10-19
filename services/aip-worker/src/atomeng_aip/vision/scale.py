"""스케일 보정 mm/px (설계 11 v0.3 B7.1 · 11-IMPL Step 4, BR-AIP-002, TC-AIP-003 · 021).

- ArUco: 마커 코너(서브픽셀) → 한 변 평균 px → mm/px = 마커 한 변 mm / 변 px, 불확도 = mm/px × (0.5√2 / 변 px)
- 재투영 오차(px): 이미지 → 마커 평면(mm) 호모그래피를 맞춘 뒤 평면 좌표를 이미지로 역투영해 **px 단위로** 비교
  (설계 v0.2 정정 — mm · px 혼용 금지). 마커 1개(4점)는 항상 ≈0 이므로 2마커 보드(8점)를 권장한다.
  오차가 2px 를 넘으면 스케일 무효(NONE) → 폭 미산출(BR-AIP-002, TC-AIP-021)
- 레이저: mm/px = D · s_w / (f · W), 촬영각 10° 초과 시 cos 보정 + 불확도 가중
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

from .measure import Scale

REPROJ_LIMIT_PX = 2.0


@dataclass(frozen=True)
class Marker:
    marker_id: int
    corners_px: np.ndarray        # (4, 2) TL · TR · BR · BL


def detect_markers(gray: np.ndarray, dictionary: str = "DICT_4X4_50") -> list[Marker]:
    params = cv2.aruco.DetectorParameters()
    params.cornerRefinementMethod = cv2.aruco.CORNER_REFINE_SUBPIX
    det = cv2.aruco.ArucoDetector(cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, dictionary)), params)
    corners, ids, _ = det.detectMarkers(gray)
    if ids is None or len(ids) == 0:
        return []
    out = [Marker(int(i), c.reshape(4, 2).astype(np.float64)) for c, i in zip(corners, ids.flatten())]
    out.sort(key=lambda m: m.marker_id)
    return out


def plane_corners(top_left_mm: tuple[float, float], marker_mm: float) -> np.ndarray:
    x, y = top_left_mm
    return np.array([[x, y], [x + marker_mm, y], [x + marker_mm, y + marker_mm], [x, y + marker_mm]], np.float64)


def scale_from_markers(markers: list[Marker], marker_mm: float,
                       layout_mm: dict[int, tuple[float, float]] | None = None) -> tuple[Scale, np.ndarray | None]:
    """검출된 마커(들)로 스케일을 계산한다. layout_mm = {마커 id: 평면상 좌상단(mm)} — 2마커 보드면 8점 호모그래피.

    반환: (Scale, 이미지 → 평면 호모그래피 H 또는 None)
    """
    if not markers:
        return Scale.none(), None
    use = [m for m in markers if layout_mm is None or m.marker_id in layout_mm]
    if not use:
        use = markers[:1]
        layout_mm = None
    if layout_mm is None:
        use = use[:1]
        layout_mm = {use[0].marker_id: (0.0, 0.0)}
    img_pts = np.concatenate([m.corners_px for m in use]).astype(np.float64)
    plane = np.concatenate([plane_corners(layout_mm[m.marker_id], marker_mm) for m in use])
    sides = [float(np.linalg.norm(m.corners_px[i] - m.corners_px[(i + 1) % 4])) for m in use for i in range(4)]
    side_px = float(np.mean(sides))
    H, _ = cv2.findHomography(img_pts, plane, 0)
    if H is None:
        return Scale.none(), None
    back = cv2.perspectiveTransform(plane[None], np.linalg.inv(H))[0]
    err_px = float(np.linalg.norm(back - img_pts, axis=1).mean())
    if err_px > REPROJ_LIMIT_PX:                           # A8 · TC-AIP-021: 비동일 평면 · 원근 왜곡 심함
        return Scale.none(err_px), None
    mm_per_px = marker_mm / side_px
    rel = (0.5 * math.sqrt(2.0)) / side_px
    return Scale("ARUCO", mm_per_px, mm_per_px * rel, err_px), H


def scale_from_aruco(gray: np.ndarray, marker_mm: float = 50.0, dictionary: str = "DICT_4X4_50",
                     layout_mm: dict[int, tuple[float, float]] | None = None) -> tuple[Scale, list[Marker]]:
    markers = detect_markers(gray, dictionary)
    s, _ = scale_from_markers(markers, marker_mm, layout_mm)
    return s, markers


def scale_from_laser(distance_mm: float, sensor_width_mm: float, focal_mm: float, image_width_px: int,
                     tilt_deg: float = 0.0, dist_uncert_mm: float = 2.0) -> Scale:
    mmpp = distance_mm * sensor_width_mm / (focal_mm * image_width_px)
    rel = dist_uncert_mm / distance_mm
    if tilt_deg > 10:
        c = math.cos(math.radians(tilt_deg))
        mmpp /= c
        rel += 1 / c - 1
    return Scale("LASER", mmpp, mmpp * rel)
