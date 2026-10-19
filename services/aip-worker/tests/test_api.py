"""HTTP 엔드포인트 시험 — FastAPI TestClient + 가짜 vLLM(atomeng_common.vlm 실제 클라이언트 경유). 합성 이미지만."""
from atomeng_aip.llm.search import sample_kb
from atomeng_aip.vision import synth


def _img(**kw) -> str:
    return synth.to_png_b64(synth.crack_photo(**kw))


def test_health_reports_vllm(client, fake_vllm):
    r = client.get("/internal/v1/health")
    assert r.status_code == 200 and r.json()["vllmReachable"] and "crack" in r.json()["models"]
    fake_vllm.down = True
    from fastapi.testclient import TestClient

    from atomeng_aip.app import create_app
    r2 = TestClient(create_app()).get("/internal/v1/health")
    assert r2.status_code == 200 and r2.json()["vllmReachable"] is False


def test_crack_score_uses_logprob_two_way(client, fake_vllm):
    r = client.post("/internal/v1/crack/score", json={"jobId": "j1", "imageB64": _img(width_px=7)})
    assert r.status_code == 200, r.text
    body = r.json()
    assert 0.9 < body["pCrack"] <= 1.0 and body["labels"] == ["crack", "normal"]
    sent = fake_vllm.bodies[-1]
    assert sent["model"] == "crack" and sent["max_tokens"] == 1 and sent["logprobs"] is True
    assert sent["temperature"] == 0
    assert body["provenance"]["lora"] == "crack" and body["provenance"]["base_model"] == "gemma-4-12b-it"


def test_crack_score_yes_no_labels(client, fake_vllm):
    fake_vllm.top = [("yes", -2.0), ("no", -0.2)]
    r = client.post("/internal/v1/crack/score", json={"imageB64": _img(), "labels": ["yes", "no"],
                                                      "prompt": "Is there a crack? Answer yes or no."})
    assert r.status_code == 200 and r.json()["pCrack"] < 0.5


def test_crack_score_vllm_down_is_503_problem(client, fake_vllm):
    fake_vllm.down = True
    r = client.post("/internal/v1/crack/score", json={"imageB64": _img()})
    assert r.status_code == 503 and r.json()["code"] == "AIP-503-001"


def test_findings_schema_constrained_and_number_stripped(client, fake_vllm):
    fake_vllm.json_replies.append({"defectType": "CRACK", "crackPattern": "HORIZONTAL", "confidence": 0.8,
                                   "description": "벽체 중앙 수평 균열 폭 0.3mm 추정"})
    r = client.post("/internal/v1/findings/generate", json={"imageB64": _img(), "context": {"member": "벽체"}})
    assert r.status_code == 200, r.text
    b = r.json()
    assert b["valid"] and b["finding"]["defectType"] == "CRACK"
    assert "0.3mm" not in b["finding"]["description"]
    sent = fake_vllm.bodies[-1]
    assert sent["response_format"]["type"] == "json_schema"
    assert sent["response_format"]["json_schema"]["schema"]["properties"]["defectType"]["enum"][0] == "CRACK"


def test_findings_invalid_json_is_reported_not_raised(client, fake_vllm):
    fake_vllm.json_replies.append({"defectType": "GHOST", "crackPattern": "NA", "confidence": 2, "description": "x"})
    b = client.post("/internal/v1/findings/generate", json={"imageB64": _img()}).json()
    assert b["valid"] is False and b["finding"] is None


def test_measure_endpoint_aruco(client):
    r = client.post("/internal/v1/crack/measure", json={"jobId": "m1", "imageB64": _img(width_px=7)})
    assert r.status_code == 200, r.text
    b = r.json()
    assert b["image"]["scaleSource"] == "ARUCO" and len(b["image"]["markers"]) == 1
    assert len(b["components"]) == 1 and b["components"][0]["widthMaxMm"] == 0.3


def test_measure_endpoint_lifted_board(client):
    img, layout, mm = synth.two_marker_board(lifted=True)
    synth.draw_band(img, (100, 820), (900, 830), 5)
    r = client.post("/internal/v1/crack/measure", json={"imageB64": synth.to_png_b64(img), "scale": {
        "source": "ARUCO", "markerSizeMm": mm, "boardLayoutMm": {str(k): list(v) for k, v in layout.items()}}})
    b = r.json()
    assert b["image"]["scaleSource"] == "NONE" and b["image"]["scaleReprojErrPx"] > 2.0
    assert b["components"] and all("MEASURE_REQUIRED" in c["flags"] for c in b["components"])


def test_measure_rejects_unknown_fields(client):
    r = client.post("/internal/v1/crack/measure", json={"imageB64": _img(), "bogus": 1})
    assert r.status_code == 422


def test_kb_search_endpoint(client):
    b = client.post("/internal/v1/kb/search", json={"query": "슬래브 백태 균열"}).json()
    assert b["mode"] == "KEYWORD_ONLY" and b["hits"] and "백태" in b["keywords"]


def test_report_draft_endpoint_ok(client, fake_vllm):
    ids = [c.chunk_id for c in sample_kb() if c.access_scope == "INTERNAL" and c.effective_to is None]
    fake_vllm.json_replies.append({"findings": [{"member": "슬래브", "text": "균열 4개소가 관찰되었다.",
                                                 "cause": "DRYING_SHRINKAGE", "repair": "INJECTION",
                                                 "citations": [f"chunk:{ids[1]}"]}]})
    r = client.post("/internal/v1/report/draft", json={"facts": {"crack.count": 4}, "query": "슬래브 백태 균열"})
    assert r.status_code == 200, r.text
    assert r.json()["status"] == "OK"


def test_report_review_endpoint(client, fake_vllm):
    fake_vllm.json_replies.append({"findings": [{"category": "NUM_MISMATCH", "severity": "MAJOR", "statement": "s",
                                                 "citations": [{"kind": "report", "blockId": "b1", "quote": "q"}]}]})
    r = client.post("/internal/v1/report/review", json={"roundId": "r1", "reportType": "RT_PRC", "sections": [
        {"key": "ch7.opinion", "blocks": [{"id": "b1", "text": "q"}]}], "facts": {"grade": "C"}})
    assert r.status_code == 200, r.text
    assert r.json()["findings"][0]["category"] == "NUM_MISMATCH"
    assert fake_vllm.bodies[-1]["response_format"]["json_schema"]["name"] == "qar_review"


def test_report_review_bad_llm_output_is_422(client, fake_vllm):
    fake_vllm.json_replies.append("not json at all")
    r = client.post("/internal/v1/report/review", json={"sections": []})
    assert r.status_code == 422 and r.json()["code"] == "AIP-422-001"
