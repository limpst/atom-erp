"""합성 시험 이미지 생성기 — 회사 · 현장 사진을 쓰지 않는다(시험 · 골든 · 계약 시험 전용).

- 회색 콘크리트 배경 + 알려진 폭(px)의 어두운 '균열' 띠 + ArUco 마커(흰 여백 포함)
- 2마커 보드: 같은 평면(정상) / 마커 하나를 벽에서 들뜬 상태(카메라 쪽으로 가까워져 크게 보임 → 비동일 평면)
"""
from __future__ import annotations

import base64

import cv2
import numpy as np

BACKGROUND = 180
CRACK = 40


def aruco_marker(marker_id: int, side_px: int, dictionary: str = "DICT_4X4_50") -> np.ndarray:
    d = cv2.aruco.getPredefinedDictionary(getattr(cv2.aruco, dictionary))
    return cv2.aruco.generateImageMarker(d, marker_id, side_px)


def paste_marker(img: np.ndarray, marker_id: int, top_left: tuple[int, int], side_px: int, quiet_px: int = 0,
                 dictionary: str = "DICT_4X4_50") -> None:
    """img 위에 마커를 붙인다(흰 여백 quiet_px 포함). top_left = (x, y) 마커 검은 테두리 외곽 기준."""
    x, y = top_left
    q = quiet_px or max(8, side_px // 6)
    cv2.rectangle(img, (x - q, y - q), (x + side_px + q - 1, y + side_px + q - 1), 255, -1)
    img[y:y + side_px, x:x + side_px] = aruco_marker(marker_id, side_px, dictionary)


def crack_photo(width_px: int = 7, marker_side_px: int = 1167, crack_len_px: int = 900, size=(1500, 2500),
                with_marker: bool = True, diagonal: bool = False) -> np.ndarray:
    """마커 1개 + 균열 1개(폭 width_px, LINE_8 = 안티에일리어싱 없음). 회색조 uint8."""
    h, w = size
    img = np.full((h, w), BACKGROUND, np.uint8)
    if with_marker:
        paste_marker(img, 0, (120, 150), marker_side_px)
    x0 = 120 + marker_side_px + 260
    if diagonal:
        draw_band(img, (x0, 200), (x0 + crack_len_px // 2, 200 + crack_len_px // 2), width_px)
    else:
        y = h // 2 - width_px // 2
        img[y:y + width_px, x0:x0 + crack_len_px] = CRACK          # 정확히 width_px 행 두께(안티에일리어싱 없음)
    return img


def draw_band(img: np.ndarray, p0: tuple[int, int], p1: tuple[int, int], width_px: float, value: int = CRACK) -> None:
    """p0 → p1 중심선에 수직 두께 width_px 인 띠(평행사변형)를 칠한다(cv2.line 두께는 실제 폭과 다르므로 쓰지 않음)."""
    a, b = np.array(p0, float), np.array(p1, float)
    d = (b - a) / np.linalg.norm(b - a)
    n = np.array([-d[1], d[0]]) * (width_px / 2.0)
    poly = np.array([a + n, b + n, b - n, a - n])
    cv2.fillConvexPoly(img, np.round(poly * 16).astype(np.int32), value, cv2.LINE_8, 4)


def two_marker_board(lifted: bool = False, side_px: int = 300, gap_px: int = 300, lift_scale: float = 1.15,
                     perspective: bool = True) -> tuple[np.ndarray, dict[int, tuple[float, float]], float]:
    """2마커 보드 합성 사진. 반환: (회색조 이미지, 평면 배치 {id: 좌상단 mm}, 마커 한 변 mm=50).

    평면 배율은 0.1 mm/px(마커 50mm = 500px 평면)로 그린 뒤 약한 원근 변환을 적용한다(동일 평면이면 8점 호모그래피가 맞음).
    lifted=True 면 마커 1 을 원근 변환 뒤 자기 중심 기준 lift_scale 배로 키워 붙인다(벽에서 들떠 카메라에 가까움).
    """
    marker_mm = 50.0
    plane_mmpp = 50.0 / side_px
    h, w = 900, 2 * side_px + gap_px + 600
    plane = np.full((h, w), BACKGROUND, np.uint8)
    p0 = (300, 300)
    p1 = (300 + side_px + gap_px, 300)
    paste_marker(plane, 0, p0, side_px)
    if not lifted:
        paste_marker(plane, 1, p1, side_px)
    layout = {0: (0.0, 0.0), 1: ((p1[0] - p0[0]) * plane_mmpp, (p1[1] - p0[1]) * plane_mmpp)}
    if perspective:
        src = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
        dst = np.float32([[0, 20], [w, 0], [w - 30, h], [10, h - 15]])
        M = cv2.getPerspectiveTransform(src, dst)
        img = cv2.warpPerspective(plane, M, (w, h), flags=cv2.INTER_LINEAR, borderValue=BACKGROUND)
    else:
        M = np.eye(3)
        img = plane
    if lifted:
        # 들뜬 마커: 원근 변환된 위치의 중심을 기준으로 lift_scale 배 큰 정사각형으로 붙인다
        c = np.float32([[p1[0] + side_px / 2, p1[1] + side_px / 2]])
        cx, cy = cv2.perspectiveTransform(c[None], M)[0][0]
        s = int(round(side_px * lift_scale))
        paste_marker(img, 1, (int(round(cx - s / 2)), int(round(cy - s / 2))), s)
    return img, layout, marker_mm


def to_png_b64(img: np.ndarray) -> str:
    ok, buf = cv2.imencode(".png", img)
    if not ok:
        raise ValueError("PNG 인코딩 실패")
    return base64.b64encode(buf.tobytes()).decode()


def decode_b64_image(b64: str, gray: bool = True) -> np.ndarray:
    raw = base64.b64decode(b64)
    arr = np.frombuffer(raw, np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_GRAYSCALE if gray else cv2.IMREAD_COLOR)
    if img is None:
        raise ValueError("이미지 디코딩 실패")
    return img
