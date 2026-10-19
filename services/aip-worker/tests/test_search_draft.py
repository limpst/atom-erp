"""키워드 검색 · 소견 초안 검증기 — TC-AIP-018 · 008 · 009. 합성 지식베이스(kb_sample.json)만."""
from atomeng_aip.llm.draft import report_draft, validate_draft
from atomeng_aip.llm.search import chunks_from_dicts, extract_keywords, keyword_search, sample_kb

QUERY = "지하주차장 슬래브 백태를 동반한 균열 소견"


def test_keywords_are_nouns_two_chars_or_more():
    kws = extract_keywords(QUERY)
    for k in ("백태", "균열", "슬래브", "소견", "주차장", "지하주차장"):
        assert k in kws
    assert "를" not in kws and all(len(k) >= 2 for k in kws)


def test_tc_aip_018_keyword_or_search_finds_partial_match_and_respects_scope():
    kb = sample_kb()
    assert not any(QUERY in c.content for c in kb)                  # 문장 전체 일치 청크는 없음
    res = keyword_search(kb, QUERY, ["INTERNAL"], k=8)
    ids = {h["chunkId"] for h in res["hits"]}
    baektae = [c for c in kb if "백태" in c.content and c.access_scope == "INTERNAL" and c.effective_to is None]
    assert baektae and all(c.chunk_id in ids for c in baektae)      # "백태" 포함 청크가 결과에 있음
    restricted = [c.chunk_id for c in kb if c.access_scope != "INTERNAL"]
    expired = [c.chunk_id for c in kb if c.effective_to is not None]
    assert restricted and not ids & set(restricted)                 # 권한 범위 밖 제외
    assert expired and not ids & set(expired)                       # 효력 종료 제외
    assert res["mode"] == "KEYWORD_ONLY"
    wider = keyword_search(kb, QUERY, ["INTERNAL", "RESTRICTED"], k=8)
    assert set(restricted) <= {h["chunkId"] for h in wider["hits"]}


FACTS = {"crack.max_width_mm": 0.3, "crack.count": 4.0}


def _good(ids):
    return {"findings": [{"member": "슬래브", "text": "최대 균열폭 0.3mm 균열 4개소가 관찰되었다.", "cause": "DRYING_SHRINKAGE",
                          "repair": "INJECTION", "citations": [f"chunk:{ids[0]}"],
                          "numbers": [{"key": "crack.max_width_mm", "value": 0.3}]}]}


def test_tc_aip_008_hallucinated_number_rejected_twice_then_failed():
    calls = []

    def llm(prompt, schema):
        calls.append(prompt)
        ids = [c.chunk_id for c in sample_kb()]
        bad = _good(ids)
        bad["findings"][0]["text"] = "최대 균열폭 0.45mm 균열이 관찰되었다."
        return bad

    r = report_draft(FACTS, sample_kb(), llm, query=QUERY)
    assert r["status"] == "FAILED" and r["draft"] is None and r["attempts"] == 2
    assert any("0.45" in e for e in r["errors"])
    assert "[검증 실패 사유" in calls[1]                                # 위반 사유를 넣어 1회 재생성


def test_draft_ok_on_second_attempt():
    replies = []

    def llm(prompt, schema):
        hits = keyword_search(sample_kb(), QUERY, ["INTERNAL"])["hits"]
        out = _good([h["chunkId"] for h in hits])
        if not replies:
            out["findings"][0]["text"] += " 구조적으로 안전하다."
        replies.append(out)
        return out

    r = report_draft(FACTS, sample_kb(), llm, query=QUERY)
    assert r["status"] == "OK" and r["attempts"] == 2 and r["retrievedChunkIds"]


def test_tc_aip_009_no_evidence_returns_insufficient():
    called = []
    r = report_draft(FACTS, [], lambda p, s: called.append(1), query=QUERY)
    assert r["status"] == "INSUFFICIENT_EVIDENCE" and not called
    r2 = report_draft(FACTS, sample_kb(), lambda p, s: called.append(1), query="태양광 패널 발전량")
    assert r2["status"] == "INSUFFICIENT_EVIDENCE" and not called


def test_validator_flags_unknown_citation_and_number_mismatch():
    out = {"findings": [{"member": "기둥", "text": "균열 4개소", "cause": "UNKNOWN", "repair": "MONITORING",
                         "citations": ["chunk:00000000-0000-0000-0000-000000000000"],
                         "numbers": [{"key": "crack.count", "value": 5}]}]}
    errs = validate_draft(out, {"x"}, FACTS)
    assert any("인용" in e for e in errs) and any("수치 불일치" in e for e in errs)


def test_chunks_from_dicts_defaults():
    c = chunks_from_dicts([{"chunkId": "a", "content": "균열"}])[0]
    assert c.access_scope == "INTERNAL" and c.effective_to is None
