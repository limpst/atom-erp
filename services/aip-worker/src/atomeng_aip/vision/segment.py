"""균열 마스크(고전 분할) — 분할 모델(ONNX, 설계 ADR-AIP-02)이 준비되기 전 합성 이미지용 대체 경로.

어두운 픽셀(gray < threshold)을 균열 후보로 보고, 검출된 ArUco 마커 영역(+여백)은 제외한다.
실제 현장 사진에는 쓰지 않는다(얼룩 · 그림자 구분 불가) — 운영은 seg 모델 마스크를 maskB64 로 넘긴다.
"""
from __future__ import annotations

import cv2
import numpy as np

from .scale import Marker


def dark_mask(gray: np.ndarray, threshold: int = 100, markers: list[Marker] | None = None,
              marker_margin_px: int = 40) -> np.ndarray:
    m = gray < threshold
    if markers:
        excl = np.zeros(gray.shape, np.uint8)
        for mk in markers:
            cv2.fillConvexPoly(excl, np.round(mk.corners_px).astype(np.int32), 1)
        if marker_margin_px > 0:
            k = cv2.getStructuringElement(cv2.MORPH_RECT, (2 * marker_margin_px + 1, 2 * marker_margin_px + 1))
            excl = cv2.dilate(excl, k)
        m &= excl == 0
    return m
