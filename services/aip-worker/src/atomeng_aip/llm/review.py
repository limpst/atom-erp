"""보고서 AI 검토 REPORT_REVIEW — QAR 이 aip::api(동기 Job)로 요청 (설계 v0.3 B6 동기 호출 · 07 B8).

QAR CitationGuard 계약과 같은 지적 스키마(category · severity · statement · citations[kind report/data])로
스키마 강제 생성을 한다. 결과 검증(인용 존재 · 원문 일치)은 QAR CitationGuard 가 다시 한다(이중 방어).
"""
from __future__ import annotations

import json
from typing import Any, Callable

REVIEW_SCHEMA: dict[str, Any] = {
    "type": "object", "required": ["findings"],
    "properties": {"findings": {"type": "array", "items": {
        "type": "object", "required": ["category", "severity", "statement", "citations"],
        "properties": {
            "category": {"enum": ["NUM_MISMATCH", "MISSING_EVIDENCE", "LOGIC_GAP", "RISKY_PHRASE", "MISSING_SECTION"]},
            "severity": {"enum": ["CRITICAL", "MAJOR", "MINOR", "INFO"]},
            "statement": {"type": "string"},
            "citations": {"type": "array", "minItems": 1, "items": {
                "type": "object", "required": ["kind"], "properties": {
                    "kind": {"enum": ["report", "data"]}, "blockId": {"type": "string"}, "quote": {"type": "string"},
                    "ref": {"type": "string"}, "value": {}}}}}}}},
}

SYSTEM = """너는 건축물 안전진단 보고서 품질 검토자다. 주어진 섹션 블록과 근거 데이터(facts)를 대조해
수치 불일치(NUM_MISMATCH) · 근거 누락(MISSING_EVIDENCE) · 논리 비약(LOGIC_GAP) · 위험 표현(RISKY_PHRASE) ·
누락 섹션(MISSING_SECTION)만 지적한다. 모든 지적은 반드시 보고서 블록 id 와 그 블록 원문 그대로의 인용(quote)을
report 인용으로 포함하고, 수치 지적은 facts 경로(ref)와 값(value)을 data 인용으로 함께 단다. 확실하지 않으면 지적하지 않는다.
JSON 으로만 답한다."""

LlmFn = Callable[[str, dict[str, Any]], dict[str, Any] | None]


def review(request: dict[str, Any], llm: LlmFn) -> dict[str, Any]:
    payload = {k: request.get(k) for k in ("roundId", "reportType", "locale", "sections", "facts", "checks")}
    out = llm(SYSTEM + "\n\n[검토 대상]\n" + json.dumps(payload, ensure_ascii=False), REVIEW_SCHEMA)
    if not isinstance(out, dict) or not isinstance(out.get("findings"), list):
        raise ValueError("LLM 출력이 검토 스키마를 따르지 않음")
    return {"findings": out["findings"]}
