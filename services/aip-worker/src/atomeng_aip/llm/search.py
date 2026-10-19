"""지식베이스 키워드 검색 — pgvector · pg_bigm 없는 대체(fallback) 경로 (설계 B7.5 · 11-IMPL Step 13, TC-AIP-018).

운영 설계는 벡터(bge-m3 + pgvector HNSW) Top-30 ∪ 키워드(pg_bigm) Top-30 → RRF(k=60) → reranker Top-8 이다.
이 워커에는 임베딩 · DB 가 없으므로 **키워드 경로만** 구현한다:
1) kiwipiepy 형태소 분석으로 명사 · 전문용어(2자 이상)를 뽑는다 — 질의 문장 전체 LIKE 는 거의 매칭되지 않으므로(v0.2 정정)
2) 키워드별 OR 매칭(부분 문자열) + 청크별 최대 bigram 유사도(pg_bigm bigm_similarity 근사)로 정렬
3) 효력 종료(effective_to) 문서 · 권한 범위(access_scope) 밖 문서는 제외
결과 모드는 KEYWORD_ONLY 로 표시한다(벡터 경로 미구현 명시).
"""
from __future__ import annotations

import json
from dataclasses import dataclass
from functools import lru_cache
from importlib import resources
from typing import Any, Iterable

NOUN_TAGS = {"NNG", "NNP", "SL", "SH"}


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    document_id: str
    kind: str
    access_scope: str
    effective_to: str | None
    heading_path: str
    content: str


@lru_cache(maxsize=1)
def _kiwi():
    from kiwipiepy import Kiwi
    return Kiwi()


def extract_keywords(query: str) -> list[str]:
    """명사(일반 · 고유) · 외국어 2자 이상 — 순서 유지 · 중복 제거. 붙어 나온 복합명사(지하+주차장)는 합친 형태도 넣는다."""
    toks = _kiwi().tokenize(query)
    out: list[str] = []
    run: list[Any] = []

    def flush():
        if len(run) >= 2:
            joined = "".join(t.form for t in run)
            if joined not in out:
                out.append(joined)
        run.clear()

    prev_end = None
    for t in toks:
        if t.tag in NOUN_TAGS:
            if run and prev_end is not None and t.start != prev_end:
                flush()
            run.append(t)
            if len(t.form) >= 2 and t.form not in out:
                out.append(t.form)
            prev_end = t.start + t.len
        else:
            flush()
            prev_end = None
    flush()
    return out


def _bigrams(s: str) -> set[str]:
    s = f" {s.lower()} "
    return {s[i:i + 2] for i in range(len(s) - 1)}


def bigm_similarity(content: str, kw: str) -> float:
    """pg_bigm bigm_similarity 근사: 키워드 bigram 중 본문에 있는 비율(본문 길이에 둔감)."""
    k = _bigrams(kw)
    return len(k & _bigrams(content)) / len(k) if k else 0.0


def keyword_search(chunks: Iterable[Chunk], query: str, scopes: Iterable[str], k: int = 8) -> dict[str, Any]:
    kws = extract_keywords(query)
    allowed = set(scopes)
    scored: list[tuple[float, Chunk, list[str]]] = []
    for c in chunks:
        if c.effective_to is not None or c.access_scope not in allowed:
            continue
        matched = [kw for kw in kws if kw in c.content]
        if not matched:
            continue
        sim = max(bigm_similarity(c.content, kw) for kw in matched)
        scored.append((sim + 0.01 * len(matched), c, matched))
    scored.sort(key=lambda t: (-t[0], t[1].chunk_id))
    hits = [{"chunkId": c.chunk_id, "documentId": c.document_id, "kind": c.kind, "headingPath": c.heading_path,
             "score": round(s, 6), "matchedKeywords": m, "content": c.content} for s, c, m in scored[:k]]
    return {"query": query, "keywords": kws, "mode": "KEYWORD_ONLY", "hits": hits}


def chunks_from_dicts(items: Iterable[dict[str, Any]]) -> list[Chunk]:
    return [Chunk(str(d["chunkId"]), str(d.get("documentId", "doc")), str(d.get("kind", "GUIDELINE")),
                  str(d.get("accessScope", "INTERNAL")), d.get("effectiveTo"), str(d.get("headingPath", "")),
                  str(d["content"])) for d in items]


@lru_cache(maxsize=1)
def sample_kb() -> tuple[Chunk, ...]:
    """합성 지식베이스(⚠️ 실제 세부지침 원문이 아님 — 시험 · 데모용 문장)."""
    raw = resources.files("atomeng_aip.llm").joinpath("kb_sample.json").read_text(encoding="utf-8")
    return tuple(chunks_from_dicts(json.loads(raw)))
