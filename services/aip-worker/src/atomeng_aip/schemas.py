"""워커 HTTP 계약(요청 · 응답 모델). FastAPI 가 이 모델로 OpenAPI 를 만들고, contract/openapi.json 에 기록한다.
ERP Java aip 모듈(AipWorkerHttpClient)이 같은 기록 파일로 계약 시험을 한다 — 필드를 바꾸면 기록 파일을 다시 만든다
(`uv run python tools/export_openapi.py`) — 그러지 않으면 test_openapi_contract 가 실패한다.
"""
from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class _M(BaseModel):
    model_config = ConfigDict(extra="forbid")


class Provenance(BaseModel):
    """atomeng_common.vlm.Provenance(모델 · LoRA · 프롬프트/입력 해시 · 스키마 해시 · 클라이언트 버전)"""
    model_config = ConfigDict(extra="allow")
    task: str
    model: str
    served_model: str | None = None
    base_model: str | None = None
    lora: str | None = None
    prompt_hash: str
    input_hash: str | None = None
    schema_hash: str | None = None
    client: str


# ---- 균열 점수 ---------------------------------------------------------------------
class CrackScoreRequest(_M):
    jobId: str | None = None
    imageB64: str = Field(min_length=8, description="JPEG/PNG base64")
    model: str = "crack"
    prompt: str | None = None
    labels: list[str] = Field(default_factory=lambda: ["crack", "normal"], min_length=2, max_length=2)


class CrackScoreResponse(_M):
    jobId: str | None
    pCrack: float = Field(ge=0, le=1)
    logprobPos: float
    logprobNeg: float
    labels: list[str]
    provenance: Provenance | None
    latencyMs: int


# ---- 소견(구조화 출력) ----------------------------------------------------------------
class Finding(_M):
    defectType: Literal["CRACK", "SPALL", "SCALING", "EFFLORESCENCE", "REBAR", "LEAK", "NONE"]
    crackPattern: Literal["VERTICAL", "HORIZONTAL", "DIAGONAL", "MAP", "NA"]
    confidence: float = Field(ge=0, le=1)
    description: str = Field(max_length=120)


class FindingRequest(_M):
    jobId: str | None = None
    imageB64: str = Field(min_length=8)
    model: str = "gemma-4-12b-it"
    context: dict[str, str] | None = None
    maxTokens: int = Field(default=200, ge=32, le=1024)


class FindingResponse(_M):
    jobId: str | None
    finding: Finding | None
    valid: bool
    rawText: str
    provenance: Provenance | None
    latencyMs: int


# ---- 측정 ---------------------------------------------------------------------------
class ScaleSpec(_M):
    source: Literal["ARUCO", "MANUAL", "NONE"] = "ARUCO"
    markerSizeMm: float = Field(default=50.0, gt=0)
    dictionary: str = "DICT_4X4_50"
    boardLayoutMm: dict[str, list[float]] | None = Field(default=None,
                                                         description="마커 id → 평면 좌상단 [x, y] mm (2마커 보드)")
    mmPerPx: float | None = Field(default=None, gt=0)
    mmPerPxUncert: float | None = Field(default=None, ge=0)


class MeasureRequest(_M):
    jobId: str | None = None
    imageB64: str = Field(min_length=8)
    maskB64: str | None = Field(default=None, description="분할 모델 이진 마스크 PNG(없으면 합성용 임계값 분할)")
    scale: ScaleSpec = Field(default_factory=ScaleSpec)
    boundariesMm: list[float] = Field(default_factory=lambda: [0.1, 0.2, 0.3])
    darkThreshold: int = Field(default=100, ge=1, le=255)
    minComponentPx: int = Field(default=50, ge=1)
    pruneSpurPx: int = Field(default=10, ge=0)


class MarkerOut(_M):
    id: int
    cornersPx: list[list[float]]


class ImageInfo(_M):
    width: int
    height: int
    mmPerPx: float | None
    mmPerPxUncert: float | None
    scaleSource: str
    scaleReprojErrPx: float | None
    markers: list[MarkerOut]


class ComponentOut(_M):
    bbox: list[int]
    areaPx: int
    skeletonLengthPx: float
    widthMaxPx: float | None
    widthMaxMm: float | None
    widthMeanMm: float | None
    widthP95Mm: float | None
    widthUncertMm: float | None
    lengthM: float | None
    flags: list[str]


class MeasureResponse(_M):
    jobId: str | None
    image: ImageInfo
    components: list[ComponentOut]
    timings: dict[str, int]


# ---- 지식베이스 검색 ------------------------------------------------------------------
class KbSearchRequest(_M):
    query: str = Field(min_length=1)
    scopes: list[str] = Field(default_factory=lambda: ["INTERNAL"])
    k: int = Field(default=8, ge=1, le=30)


class KbHit(_M):
    chunkId: str
    documentId: str
    kind: str
    headingPath: str
    score: float
    matchedKeywords: list[str]
    content: str


class KbSearchResponse(_M):
    query: str
    keywords: list[str]
    mode: Literal["KEYWORD_ONLY"]
    hits: list[KbHit]


# ---- 보고서 초안 · 검토 ---------------------------------------------------------------
class DraftRequest(_M):
    jobId: str | None = None
    facts: dict[str, float]
    query: str | None = None
    scopes: list[str] = Field(default_factory=lambda: ["INTERNAL"])
    model: str = "gemma-4-12b-it"


class DraftResponse(_M):
    jobId: str | None
    status: Literal["OK", "INSUFFICIENT_EVIDENCE", "FAILED"]
    draft: dict[str, Any] | None
    errors: list[str]
    retrievedChunkIds: list[str]
    keywords: list[str]
    attempts: int


class ReviewBlock(_M):
    id: str
    text: str | None = None


class ReviewSection(_M):
    key: str
    blocks: list[ReviewBlock]


class ReviewRequest(_M):
    jobId: str | None = None
    roundId: str | None = None
    reportType: str | None = None
    locale: str = "ko"
    sections: list[ReviewSection]
    facts: Any = None
    checks: list[str] = Field(default_factory=list)
    model: str = "gemma-4-12b-it"


class ReviewCitation(BaseModel):
    model_config = ConfigDict(extra="allow")
    kind: str
    blockId: str | None = None
    quote: str | None = None
    ref: str | None = None
    value: Any = None


class ReviewFinding(BaseModel):
    model_config = ConfigDict(extra="allow")
    category: str | None = None
    severity: str | None = None
    statement: str = ""
    citations: list[ReviewCitation] = Field(default_factory=list)


class ReviewResponse(_M):
    jobId: str | None
    model: str
    findings: list[ReviewFinding]
    latencyMs: int


class Health(_M):
    status: Literal["UP"]
    version: str
    vllmReachable: bool
    models: list[str]


class Problem(BaseModel):
    code: str
    title: str
    detail: str | None = None
