"""선택적 실서버 시험 — 로컬 vLLM(127.0.0.1:8080)이 응답하고 `crack` LoRA 를 서빙할 때만 실행, 아니면 skip.

요청만 보낸다(서버 기동 · 중지 · 재시작 · 학습 없음). 합성 이미지만 사용(회사 · 현장 사진 미사용).
"""
import pytest
from atomeng_common.vlm import VLMClient
from fastapi.testclient import TestClient

from atomeng_aip.app import create_app
from atomeng_aip.vision import synth

pytestmark = pytest.mark.live


@pytest.fixture(scope="module")
def live():
    c = VLMClient(timeout=120)
    if not c.available("crack"):
        pytest.skip("로컬 vLLM 미기동 또는 crack LoRA 미서빙")
    from conftest import SECRET, HmacAuth
    t = TestClient(create_app(lambda: VLMClient(timeout=120), secret=SECRET))
    t.auth = HmacAuth()
    return t


def test_live_health(live):
    b = live.get("/internal/v1/health").json()
    assert b["vllmReachable"] and "crack" in b["models"]


def test_live_crack_score_on_synthetic_image(live):
    img = synth.to_png_b64(synth.crack_photo(width_px=7, marker_side_px=400, size=(800, 1500), crack_len_px=600))
    b = live.post("/internal/v1/crack/score", json={"imageB64": img}).json()
    assert 0.0 <= b["pCrack"] <= 1.0
    assert b["provenance"]["lora"] == "crack"


def test_live_finding_schema_constrained(live):
    img = synth.to_png_b64(synth.crack_photo(width_px=7, marker_side_px=400, size=(800, 1500), crack_len_px=600))
    b = live.post("/internal/v1/findings/generate", json={"imageB64": img, "maxTokens": 200}).json()
    assert "valid" in b
    if b["valid"]:
        assert b["finding"]["defectType"] in {"CRACK", "SPALL", "SCALING", "EFFLORESCENCE", "REBAR", "LEAK", "NONE"}
