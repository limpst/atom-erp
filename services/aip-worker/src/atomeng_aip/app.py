"""AIP 워커 HTTP 앱 (FastAPI) — ERP aip 모듈(Java)만 호출하는 내부 API(설계 B4.4 /internal/v1/*).

실행(로컬, 127.0.0.1 만):  uv run uvicorn atomeng_aip.app:app --host 127.0.0.1 --port 8090
- vLLM 은 요청만 보낸다(기동 · 중지 · 학습 없음). 주소는 ATOM_VLM_BASE_URL(기본 http://127.0.0.1:8080/v1), 로컬 전용.
- vLLM 이 응답하지 않으면 503 AIP-503-001(problem JSON) — ERP 는 Job 을 FAILED/재시도로 처리하고 ERP 기능은 그대로 동작.
"""
from __future__ import annotations

import os
import time
from typing import Any, Callable

import numpy as np
from atomeng_common.vlm import VLMClient, VLMError
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from . import __version__
from .llm import draft as draft_mod
from .llm import review as review_mod
from .llm.search import keyword_search, sample_kb
from .schemas import (CrackScoreRequest, CrackScoreResponse, DraftRequest, DraftResponse, FindingRequest,
                      FindingResponse, Health, KbSearchRequest, KbSearchResponse, MeasureRequest, MeasureResponse,
                      Problem, ReviewRequest, ReviewResponse)
from .vision import crack as crack_mod
from .vision.measure import Scale, measure_mask
from .vision.scale import scale_from_markers, detect_markers
from .vision.segment import dark_mask
from .vision.synth import decode_b64_image

VlmFactory = Callable[[], VLMClient]


class WorkerUnavailable(RuntimeError):
    pass


def _default_vlm() -> VLMClient:
    return VLMClient(timeout=float(os.environ.get("AIP_VLM_TIMEOUT_SEC", "120")))


def _json_llm(client: VLMClient, model: str, name: str) -> Callable[[str, dict[str, Any]], dict[str, Any] | None]:
    def call(prompt: str, schema: dict[str, Any]) -> dict[str, Any] | None:
        res = client.generate_json(None, model, prompt, schema, name=name, max_tokens=1500, temperature=0, seed=7)
        return res.data
    return call


def create_app(vlm_factory: VlmFactory = _default_vlm) -> FastAPI:
    app = FastAPI(title="atomeng-aip worker", version=__version__,
                  description="에이톰 ERP AIP 워커 내부 API — ERP aip 모듈 전용(설계 11 B4.4). 합성 데이터로만 시험.")
    state: dict[str, VLMClient] = {}

    def vlm() -> VLMClient:
        if "c" not in state:
            state["c"] = vlm_factory()
        return state["c"]

    @app.exception_handler(VLMError)
    async def _vlm_down(_: Request, e: VLMError):
        return JSONResponse(status_code=503, content=Problem(code="AIP-503-001", title="vLLM 응답 없음",
                                                             detail=str(e)[:300]).model_dump())

    @app.exception_handler(ValueError)
    async def _bad(_: Request, e: ValueError):
        return JSONResponse(status_code=422, content=Problem(code="AIP-422-001", title="입력 · 출력 형식 오류",
                                                             detail=str(e)[:300]).model_dump())

    @app.get("/internal/v1/health", response_model=Health, tags=["ops"])
    def health() -> Health:
        try:
            models = vlm().models()
            ok = True
        except VLMError:
            models, ok = [], False
        return Health(status="UP", version=__version__, vllmReachable=ok, models=models)

    @app.post("/internal/v1/crack/score", response_model=CrackScoreResponse, tags=["vision"],
              responses={503: {"model": Problem}})
    def crack_score(req: CrackScoreRequest) -> CrackScoreResponse:
        r = crack_mod.score(vlm(), req.imageB64, req.model, req.prompt, (req.labels[0], req.labels[1]))
        return CrackScoreResponse(jobId=req.jobId, **r)

    @app.post("/internal/v1/findings/generate", response_model=FindingResponse, tags=["vision"],
              responses={503: {"model": Problem}})
    def findings_generate(req: FindingRequest) -> FindingResponse:
        r = crack_mod.finding(vlm(), req.imageB64, req.model, req.context, req.maxTokens)
        return FindingResponse(jobId=req.jobId, **r)

    @app.post("/internal/v1/crack/measure", response_model=MeasureResponse, tags=["vision"],
              responses={422: {"model": Problem}})
    def crack_measure(req: MeasureRequest) -> MeasureResponse:
        t0 = time.perf_counter()
        gray = decode_b64_image(req.imageB64)
        markers = detect_markers(gray, req.scale.dictionary) if req.scale.source == "ARUCO" else []
        if req.scale.source == "ARUCO":
            layout = None if req.scale.boardLayoutMm is None else \
                {int(k): (float(v[0]), float(v[1])) for k, v in req.scale.boardLayoutMm.items()}
            scale, _ = scale_from_markers(markers, req.scale.markerSizeMm, layout)
        elif req.scale.source == "MANUAL" and req.scale.mmPerPx:
            scale = Scale("MANUAL", req.scale.mmPerPx, req.scale.mmPerPxUncert or 0.0)
        else:
            scale = Scale.none()
        t1 = time.perf_counter()
        if req.maskB64:
            mask = decode_b64_image(req.maskB64) > 0
            if mask.shape != gray.shape:
                raise ValueError("마스크 크기가 이미지와 다름")
        else:
            mask = dark_mask(gray, req.darkThreshold, markers)
        comps = measure_mask(mask, scale, req.boundariesMm, req.minComponentPx, req.pruneSpurPx)
        t2 = time.perf_counter()
        info = {"width": int(gray.shape[1]), "height": int(gray.shape[0]),
                "mmPerPx": None if scale.mm_per_px is None else round(scale.mm_per_px, 8),
                "mmPerPxUncert": None if scale.mm_per_px_uncert is None else round(scale.mm_per_px_uncert, 10),
                "scaleSource": scale.source,
                "scaleReprojErrPx": None if scale.reproj_err_px is None else round(scale.reproj_err_px, 6),
                "markers": [{"id": m.marker_id, "cornersPx": np.round(m.corners_px, 4).tolist()} for m in markers]}
        return MeasureResponse(jobId=req.jobId, image=info, components=comps,
                               timings={"scaleMs": int((t1 - t0) * 1000), "measureMs": int((t2 - t1) * 1000)})

    @app.post("/internal/v1/kb/search", response_model=KbSearchResponse, tags=["llm"])
    def kb_search(req: KbSearchRequest) -> KbSearchResponse:
        return KbSearchResponse(**keyword_search(sample_kb(), req.query, req.scopes, req.k))

    @app.post("/internal/v1/report/draft", response_model=DraftResponse, tags=["llm"],
              responses={503: {"model": Problem}})
    def report_draft(req: DraftRequest) -> DraftResponse:
        llm = _json_llm(vlm(), req.model, "report_draft_finding")
        r = draft_mod.report_draft(req.facts, sample_kb(), llm, req.scopes, req.query)
        return DraftResponse(jobId=req.jobId, **r)

    @app.post("/internal/v1/report/review", response_model=ReviewResponse, tags=["llm"],
              responses={503: {"model": Problem}, 422: {"model": Problem}})
    def report_review(req: ReviewRequest) -> ReviewResponse:
        t0 = time.perf_counter()
        llm = _json_llm(vlm(), req.model, "qar_review")
        r = review_mod.review(req.model_dump(), llm)
        return ReviewResponse(jobId=req.jobId, model=req.model, findings=r["findings"],
                              latencyMs=int((time.perf_counter() - t0) * 1000))

    return app


app = create_app()
