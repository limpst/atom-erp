"""균열폭 · 길이 측정 (설계 11 v0.3 B7.3 · 11-IMPL Step 6, BR-AIP-002 · 003, TC-AIP-005 · 006 · 019).

결정론적 기하 알고리즘만 쓴다(학습 모델 없음).
- 중심선: Zhang 세선화(skimage) → 10px 미만 가지(spur) 프루닝(설계 B7.3 P2)
- 길이: 중심선 각 픽셀의 직교 이웃 수 × 1 + 대각 이웃 수 × √2 를 모두 더해 2 로 나눔(간선이 양 끝에서 두 번 세어짐)
- 폭: 중심선 각 점의 국부 폭 = 2 × EDT − 1 (이산화 보정), 최대폭 = P99(스파이크 억제), 평균 · P95
- 불확도: 경계 이산화 ±1px 와 스케일 상대오차를 제곱합으로 전파
- 스케일이 없으면 폭을 만들지 않고 MEASURE_REQUIRED 만 붙인다(BR-AIP-002)

ERP Java 도메인(com.atomeng.erp.aip.domain.measure)이 같은 규칙을 독립 구현하고, tools/aip_golden_gen.py 가 이 모듈로
만든 골든 사례로 두 구현의 일치를 검사한다 — 규칙을 바꾸면 양쪽을 같이 바꾼다.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import Iterable

import numpy as np
from scipy import ndimage

SQRT2 = math.sqrt(2.0)
_ORTHO = np.array([[0, 1, 0], [1, 0, 1], [0, 1, 0]], dtype=np.int32)
_DIAG = np.array([[1, 0, 1], [0, 0, 0], [1, 0, 1]], dtype=np.int32)
# 이웃 순서 고정(행 우선) — Java 구현과 같은 순서로 탐색해야 프루닝 결과가 같다
NEIGHBORS = ((-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1))
DEFAULT_BOUNDARIES_MM = (0.1, 0.2, 0.3)   # ⚠️ 예시 — 운영은 rule_param CRACK_WIDTH_GRADE_BOUNDARIES_MM(세부지침 확인 필요)
LOW_RESOLUTION_MM_PER_PX = 0.05           # 이 값을 넘으면 0.1mm 경계 구분 불가 → "더 가까이 촬영"


def skeleton_length_px(skel: np.ndarray) -> float:
    """설계 v0.2 B7.3 정의: (직교 이웃 수 합 × 1 + 대각 이웃 수 합 × √2) / 2."""
    s = np.asarray(skel, dtype=bool).astype(np.int32)
    ortho = ndimage.convolve(s, _ORTHO, mode="constant", cval=0) * s
    diag = ndimage.convolve(s, _DIAG, mode="constant", cval=0) * s
    return float((ortho.sum() * 1.0 + diag.sum() * SQRT2) / 2.0)


def skeletonize(mask: np.ndarray) -> np.ndarray:
    from skimage.morphology import skeletonize as _sk
    return _sk(np.asarray(mask, dtype=bool), method="zhang")


# 교차수(crossing number) 계산용 원형 순서: N, NE, E, SE, S, SW, W, NW
_RING = ((-1, 0), (-1, 1), (0, 1), (1, 1), (1, 0), (1, -1), (0, -1), (-1, -1))


def _on(s: np.ndarray, r: int, c: int) -> bool:
    h, w = s.shape
    return 0 <= r < h and 0 <= c < w and bool(s[r, c])


def crossing_number(s: np.ndarray, r: int, c: int) -> int:
    """8-이웃을 한 바퀴 돌며 0→1 전이 수. 끝점 1, 선 위 2, 갈림점 3 이상(계단형 대각 연결에 강건)."""
    ring = [_on(s, r + dr, c + dc) for dr, dc in _RING]
    return sum(1 for i in range(8) if not ring[i] and ring[(i + 1) % 8])


def prune_spurs(skel: np.ndarray, min_len: int = 10) -> np.ndarray:
    """끝점에서 갈림점까지의 가지 길이(픽셀 수)가 min_len 미만이면 제거한다(1회, 설계 B7.3 P2).

    - 끝점 = 교차수 1, 갈림점 = 교차수 3 이상. 끝점은 행 우선 순서로 처리한다.
    - 한 걸음마다 아직 지나지 않은 이웃(NEIGHBORS 고정 순서) 중 갈림점이 하나라도 있으면 가지 끝 → 지금까지 경로를 제거 대상으로.
      없으면 첫 이웃으로 진행. 이웃이 없으면(다른 끝점) 성분 전체이므로 지우지 않는다. 경로가 min_len 에 닿으면 유지.
    - 제거는 모든 끝점을 본 뒤 한꺼번에 적용한다(처리 순서가 결과를 바꾸지 않도록).
    Java(com.atomeng.erp.aip.domain.measure.SpurPruner)와 같은 규칙 — 골든 사례로 일치를 검사한다.
    """
    s = np.asarray(skel, dtype=bool).copy()
    if min_len <= 1:
        return s
    remove: set[tuple[int, int]] = set()
    endpoints = [(int(r), int(c)) for r, c in zip(*np.nonzero(s)) if crossing_number(s, int(r), int(c)) == 1]
    for ep in endpoints:
        path = [ep]
        seen = {ep}
        cur = ep
        while len(path) < min_len:
            cands = [(cur[0] + dr, cur[1] + dc) for dr, dc in NEIGHBORS
                     if _on(s, cur[0] + dr, cur[1] + dc) and (cur[0] + dr, cur[1] + dc) not in seen]
            if not cands:                                   # 다른 끝점 — 성분 전체, 지우지 않음
                break
            if any(crossing_number(s, *p) >= 3 for p in cands):   # 갈림점 도달 — 짧은 가지
                remove.update(path)
                break
            cur = cands[0]
            path.append(cur)
            seen.add(cur)
    for p in remove:
        s[p] = False
    return s


def percentile_linear(values: np.ndarray, q: float) -> float:
    """numpy 기본(linear) 백분위 — Java 구현과 같은 정의."""
    return float(np.percentile(values, q))


@dataclass(frozen=True)
class Scale:
    source: str                       # ARUCO | LASER | GAUGE | MANUAL | NONE
    mm_per_px: float | None
    mm_per_px_uncert: float | None
    reproj_err_px: float | None = None

    @property
    def valid(self) -> bool:
        return self.mm_per_px is not None and self.mm_per_px > 0

    @staticmethod
    def none(reproj_err_px: float | None = None) -> "Scale":
        return Scale("NONE", None, None, reproj_err_px)


@dataclass
class Measurement:
    skeleton_length_px: float
    width_max_px: float | None
    width_max_mm: float | None = None
    width_mean_mm: float | None = None
    width_p95_mm: float | None = None
    width_uncert_mm: float | None = None
    length_m: float | None = None
    flags: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {"skeletonLengthPx": round(self.skeleton_length_px, 6),
                "widthMaxPx": None if self.width_max_px is None else round(self.width_max_px, 6),
                "widthMaxMm": self.width_max_mm, "widthMeanMm": self.width_mean_mm, "widthP95Mm": self.width_p95_mm,
                "widthUncertMm": self.width_uncert_mm, "lengthM": self.length_m, "flags": list(self.flags)}


def boundary_flags(wmax: float, unc: float, boundaries: Iterable[float]) -> list[str]:
    """|최대폭 − 경계| ≤ 불확도 → NEAR_GRADE_BOUNDARY_0_3MM 등(BR-AIP-003)."""
    return [f"NEAR_GRADE_BOUNDARY_{_fmt_mm(b)}MM" for b in boundaries if abs(wmax - b) <= unc]


def _fmt_mm(b: float) -> str:
    s = f"{b:.3f}".rstrip("0").rstrip(".")
    return s.replace(".", "_")


def measure_component(mask: np.ndarray, skel: np.ndarray, scale: Scale,
                      boundaries: Iterable[float] = DEFAULT_BOUNDARIES_MM, prune_px: int = 10) -> Measurement:
    """성분 1개(마스크 + 세선화 중심선)를 측정한다. skel 은 프루닝 전 중심선."""
    m = np.asarray(mask, dtype=bool)
    sk = prune_spurs(skel, prune_px) & m
    length_px = skeleton_length_px(sk)
    dist = ndimage.distance_transform_edt(m)
    w_px = 2.0 * dist[sk] - 1.0
    w_px = w_px[w_px > 0]
    if w_px.size == 0:
        return Measurement(length_px, None, flags=["EMPTY_SKELETON"])
    wmax_px = percentile_linear(w_px, 99)
    if not scale.valid:                                   # BR-AIP-002 / TC-AIP-005
        return Measurement(length_px, wmax_px, flags=["MEASURE_REQUIRED"])
    mmpp = float(scale.mm_per_px)
    mmpp_unc = float(scale.mm_per_px_uncert or 0.0)
    w = w_px * mmpp
    wmax = percentile_linear(w, 99)
    unc = math.sqrt((1.0 * mmpp) ** 2 + (wmax * mmpp_unc / mmpp) ** 2)
    out = Measurement(length_px, wmax_px,
                      width_max_mm=round(wmax, 3), width_mean_mm=round(float(w.mean()), 3),
                      width_p95_mm=round(percentile_linear(w, 95), 3), width_uncert_mm=round(unc, 3),
                      length_m=round(length_px * mmpp / 1000.0, 3))
    out.flags = boundary_flags(out.width_max_mm, out.width_uncert_mm, boundaries)
    if mmpp > LOW_RESOLUTION_MM_PER_PX:
        out.flags.append("LOW_RESOLUTION_RETAKE")
    return out


def components(mask: np.ndarray, min_px: int = 50) -> list[tuple[tuple[int, int, int, int], np.ndarray]]:
    """8-연결 성분(면적 min_px 미만은 잡음으로 제거) → [(bbox(r0, c0, r1, c1), 성분 마스크(bbox 잘라냄 + 1px 여백))]."""
    lab, n = ndimage.label(np.asarray(mask, dtype=bool), structure=np.ones((3, 3), dtype=int))
    out = []
    for i, sl in enumerate(ndimage.find_objects(lab), start=1):
        if sl is None:
            continue
        sub = lab[sl] == i
        if int(sub.sum()) < min_px:
            continue
        padded = np.pad(sub, 1)
        out.append(((sl[0].start, sl[1].start, sl[0].stop, sl[1].stop), padded))
    out.sort(key=lambda t: (-int(t[1].sum()), t[0]))       # 큰 성분부터
    return out


def measure_mask(mask: np.ndarray, scale: Scale, boundaries: Iterable[float] = DEFAULT_BOUNDARIES_MM,
                 min_px: int = 50, prune_px: int = 10) -> list[dict]:
    """마스크 전체 → 성분별 측정 결과(dict) 목록."""
    res = []
    for bbox, comp in components(mask, min_px):
        meas = measure_component(comp, skeletonize(comp), scale, boundaries, prune_px)
        d = meas.to_dict()
        d["bbox"] = list(bbox)
        d["areaPx"] = int(comp.sum())
        res.append(d)
    return res
