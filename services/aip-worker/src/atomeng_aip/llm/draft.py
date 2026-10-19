"""보고서 소견 초안 REPORT_DRAFT (설계 B7.6 · 11-IMPL Step 13, BR-AIP-004, TC-AIP-008 · 009).

근거 검색(키워드 대체 경로) → JSON 스키마 강제 생성 → 검증기(인용 존재 · 수치 일치 · 금지 표현) →
실패 시 위반 사유를 넣어 1회 재생성 → 그래도 실패하면 FAILED(초안 미노출). 근거 0건이면 INSUFFICIENT_EVIDENCE.
LLM 호출은 함수로 주입한다(시험은 가짜 함수, 운영은 VLMClient.generate_json 텍스트 전용).
"""
from __future__ import annotations

import json
import re
from typing import Any, Callable, Iterable

from .search import Chunk, keyword_search

DRAFT_SCHEMA: dict[str, Any] = {
    "type": "object",
    "required": ["findings"],
    "properties": {"findings": {"type": "array", "items": {
        "type": "object",
        "required": ["member", "text", "cause", "repair", "citations"],
        "properties": {
            "member": {"type": "string"},
            "text": {"type": "string", "maxLength": 400},
            "cause": {"type": "string", "enum": ["DRYING_SHRINKAGE", "SETTLEMENT", "OVERLOAD", "CORROSION", "THERMAL",
                                                 "UNKNOWN"]},
            "repair": {"type": "string", "enum": ["SURFACE_TREATMENT", "INJECTION", "FILLING", "SECTION_RESTORATION",
                                                  "STRENGTHENING", "MONITORING"]},
            "citations": {"type": "array", "minItems": 1,
                          "items": {"type": "string", "pattern": "^chunk:[0-9a-f-]{36}$"}},
            "numbers": {"type": "array", "items": {"type": "object", "properties": {
                "key": {"type": "string"}, "value": {"type": "number"}}}}}}}},
}

BANNED = ("구조적으로 안전", "안전하다", "위험하다", "붕괴 위험 없음")

NUM_RE = re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)(?:\s?(mm|m|㎡|m2|%|개소|EA))?")

PROMPT = """당신은 시설물 정밀안전진단 보고서의 외관조사 소견을 작성하는 책임기술자 보조입니다.
규칙:
1. 아래 [결함 데이터]에 있는 수치만 사용하고, 새로운 수치를 만들지 마십시오.
2. 모든 문장은 [근거] 중 하나 이상의 chunk ID를 citations 에 넣어야 합니다.
3. 원인과 보수방안은 정해진 코드(enum) 중에서만 고르십시오.
4. "안전하다/위험하다" 같은 최종 판정은 쓰지 마십시오. 판정은 기술자가 합니다.
[결함 데이터]
{facts}
[근거]
{evidence}"""

LlmFn = Callable[[str, dict[str, Any]], dict[str, Any] | None]


def validate_draft(out: dict[str, Any], retrieved_ids: set[str], facts: dict[str, float],
                   banned: Iterable[str] = BANNED) -> list[str]:
    errors: list[str] = []
    allowed = {float(v) for v in facts.values()}
    for i, f in enumerate(out.get("findings", [])):
        cites = {str(c).removeprefix("chunk:") for c in f.get("citations", [])}
        if not cites or not cites <= retrieved_ids:
            errors.append(f"finding[{i}] 인용 ID가 검색 결과에 없음: {sorted(cites - retrieved_ids)}")
        for n in f.get("numbers", []) or []:
            if n.get("key") not in facts or float(facts[n["key"]]) != float(n.get("value")):
                errors.append(f"finding[{i}] 수치 불일치 {n.get('key')}={n.get('value')}")
        for m in NUM_RE.finditer(str(f.get("text", ""))):
            if float(m.group(1)) not in allowed:
                errors.append(f"finding[{i}] 본문 수치 {m.group(0)} 가 입력에 없음")
        for b in banned:
            if b in str(f.get("text", "")):
                errors.append(f"finding[{i}] 금지 표현 '{b}'")
    return errors


def build_query(facts: dict[str, Any], hint: str | None = None) -> str:
    if hint:
        return hint
    words = [k.split(".")[0] for k in facts]
    return " ".join(dict.fromkeys(words)) + " 균열 소견"


def report_draft(facts: dict[str, float], chunks: Iterable[Chunk], llm: LlmFn, scopes: Iterable[str] = ("INTERNAL",),
                 query: str | None = None) -> dict[str, Any]:
    found = keyword_search(chunks, build_query(facts, query), scopes, k=8)
    hits = found["hits"]
    if not hits:
        return {"status": "INSUFFICIENT_EVIDENCE", "draft": None, "errors": [], "retrievedChunkIds": [],
                "keywords": found["keywords"], "attempts": 0}
    ids = [h["chunkId"] for h in hits]
    evidence = "\n".join(f"- chunk:{h['chunkId']} ({h['headingPath']}) {h['content'][:600]}" for h in hits)
    prompt = PROMPT.format(facts=json.dumps(facts, ensure_ascii=False, indent=2), evidence=evidence)
    errs: list[str] = []
    for attempt in range(1, 3):
        out = llm(prompt, DRAFT_SCHEMA)
        errs = ["LLM 출력이 JSON 스키마를 따르지 않음"] if not isinstance(out, dict) else \
            validate_draft(out, set(ids), facts)
        if not errs:
            return {"status": "OK", "draft": out, "errors": [], "retrievedChunkIds": ids,
                    "keywords": found["keywords"], "attempts": attempt}
        prompt = prompt + "\n\n[검증 실패 사유 — 수정하여 다시 작성]\n" + "\n".join(errs)
    return {"status": "FAILED", "draft": None, "errors": errs, "retrievedChunkIds": ids,
            "keywords": found["keywords"], "attempts": 2}
