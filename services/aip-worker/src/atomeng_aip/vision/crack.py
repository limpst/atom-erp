"""균열 점수 · 결함 소견(구조화 출력) — 로컬 vLLM(Atom AI Core) 호출은 atomeng_common.vlm.VLMClient 로만 한다.

- 점수: 서빙 LoRA `crack` 의 첫 토큰 로그우도 2지선다(설계 프로토타입과 같은 프롬프트, 라벨 crack/normal 기본 — 요청에서
  yes/no 등 다른 라벨 쌍을 줄 수 있다). P(pos) = softmax(logprob_pos, logprob_neg)
- 소견: response_format json_schema 로 강제한 JSON(vision.defect.finding) — 폭 수치는 절대 만들지 않는다
  (서술문 속 숫자+단위는 후처리로 제거, 11-IMPL Step 7 함정). 폭은 측정기(measure.py)만 계산한다.
"""
from __future__ import annotations

import re
import time
from typing import Any

from atomeng_common.vlm import VLMClient

SCORE_PROMPT = "Does this image show a crack? Answer with exactly one word: crack or normal."
DEFAULT_LABELS = ("crack", "normal")

FINDING_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["defectType", "crackPattern", "confidence", "description"],
    "additionalProperties": False,
    "properties": {
        "defectType": {"enum": ["CRACK", "SPALL", "SCALING", "EFFLORESCENCE", "REBAR", "LEAK", "NONE"]},
        "crackPattern": {"enum": ["VERTICAL", "HORIZONTAL", "DIAGONAL", "MAP", "NA"]},
        "confidence": {"type": "number", "minimum": 0, "maximum": 1},
        "description": {"type": "string", "maxLength": 120},
    },
}

FINDING_PROMPT = """당신은 콘크리트 구조물 외관조사 전문가입니다. 사진 속 결함 유형과 균열 패턴을 판정하세요.
- 확실하지 않으면 confidence 를 낮게 주세요. 결함이 없으면 defectType 은 NONE, crackPattern 은 NA 입니다.
- 균열폭 · 길이 수치는 절대 추정하지 마세요(폭은 별도 측정기로 계산합니다).
- description 은 위치 · 형태를 120자 이내 한국어로 씁니다.{context}"""

_NUMERIC_MEASURE = re.compile(r"\d+(?:[.,]\d+)?\s*(?:mm|㎜|cm|m|미리|밀리)")


def strip_hallucinated_numbers(text: str) -> str:
    """서술문에 들어간 수치+단위(예: '0.3mm')를 지운다 — 환각 수치 차단."""
    return re.sub(r"\s{2,}", " ", _NUMERIC_MEASURE.sub("", text)).strip()


def score(client: VLMClient, image_b64: str, model: str = "crack", prompt: str | None = None,
          labels: tuple[str, str] = DEFAULT_LABELS) -> dict[str, Any]:
    t0 = time.perf_counter()
    ws = client.score_word(image_b64, model, prompt or SCORE_PROMPT, labels[0], labels[1])
    return {"pCrack": ws.p, "logprobPos": ws.logprob_pos, "logprobNeg": ws.logprob_neg, "labels": list(labels),
            "provenance": ws.provenance.to_dict() if ws.provenance else None,
            "latencyMs": int((time.perf_counter() - t0) * 1000)}


def finding(client: VLMClient, image_b64: str, model: str, context: dict[str, Any] | None = None,
            max_tokens: int = 200) -> dict[str, Any]:
    t0 = time.perf_counter()
    ctx = ""
    if context:
        ctx = "\n- 참고(위치 정보, 수치 아님): " + ", ".join(f"{k}={v}" for k, v in sorted(context.items()))
    res = client.generate_json(image_b64, model, FINDING_PROMPT.format(context=ctx), FINDING_SCHEMA,
                               name="vision_defect_finding", max_tokens=max_tokens, temperature=0, seed=7)
    data = res.data
    valid = data is not None and validate_finding(data)
    if valid:
        data = dict(data)
        data["description"] = strip_hallucinated_numbers(str(data.get("description", "")))[:120]
    return {"finding": data if valid else None, "valid": valid, "rawText": res.text[:2000],
            "provenance": res.provenance.to_dict() if res.provenance else None,
            "latencyMs": int((time.perf_counter() - t0) * 1000)}


def validate_finding(d: dict[str, Any]) -> bool:
    p = FINDING_SCHEMA["properties"]
    try:
        return (d.get("defectType") in p["defectType"]["enum"] and d.get("crackPattern") in p["crackPattern"]["enum"]
                and 0.0 <= float(d.get("confidence")) <= 1.0 and isinstance(d.get("description"), str)
                and set(d) <= set(p))
    except (TypeError, ValueError):
        return False
