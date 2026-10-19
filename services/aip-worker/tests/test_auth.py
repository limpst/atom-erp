"""보안 검토 AIP-06 — 워커 HMAC 인증(서명 · 시각 창 · 재전송 · 비밀 미설정 시 거부). 합성 비밀 · 합성 본문만."""
import secrets
import time

import pytest
from conftest import SECRET, HmacAuth
from fastapi.testclient import TestClient

from atomeng_aip.app import create_app
from atomeng_aip.auth import AuthError, RequestVerifier, signature

PATH = "/internal/v1/kb/search"
BODY = b'{"query":"\xea\xb7\xa0\xec\x97\xb4"}'


def _headers(ts=None, nonce=None, secret=SECRET, body=BODY, path=PATH, method="POST"):
    ts = str(int(time.time()) if ts is None else ts)
    nonce = nonce or secrets.token_hex(16)
    return {"x-atom-timestamp": ts, "x-atom-nonce": nonce,
            "x-atom-signature": "v1=" + signature(secret, ts, nonce, method, path, body)}


def test_verifier_accepts_valid_and_rejects_replay():
    v = RequestVerifier(SECRET)
    h = _headers()
    v.verify("POST", PATH, BODY, h)
    with pytest.raises(AuthError, match="재전송"):
        v.verify("POST", PATH, BODY, h)


@pytest.mark.parametrize("mutate", [
    lambda h: {**h, "x-atom-signature": "v1=" + "0" * 64},
    lambda h: {k: v for k, v in h.items() if k != "x-atom-signature"},
    lambda h: {**h, "x-atom-nonce": "not-hex!"},
])
def test_verifier_rejects_bad_headers(mutate):
    with pytest.raises(AuthError):
        RequestVerifier(SECRET).verify("POST", PATH, BODY, mutate(_headers()))


def test_verifier_rejects_tampered_body_path_and_wrong_secret():
    v = RequestVerifier(SECRET)
    with pytest.raises(AuthError, match="서명 불일치"):
        v.verify("POST", PATH, b'{"query":"x"}', _headers())
    with pytest.raises(AuthError, match="서명 불일치"):
        v.verify("POST", "/internal/v1/report/draft", BODY, _headers())
    with pytest.raises(AuthError, match="서명 불일치"):
        v.verify("POST", PATH, BODY, _headers(secret=b"another-synthetic-secret-0123456789"))


def test_verifier_time_window():
    now = 1_800_000_000
    v = RequestVerifier(SECRET, window_sec=300, clock=lambda: now)
    with pytest.raises(AuthError, match="창"):
        v.verify("POST", PATH, BODY, _headers(ts=now - 301))
    with pytest.raises(AuthError, match="창"):
        v.verify("POST", PATH, BODY, _headers(ts=now + 301))
    v.verify("POST", PATH, BODY, _headers(ts=now - 299))


def test_missing_or_short_secret_fails_closed():
    assert not RequestVerifier(None).configured
    assert not RequestVerifier(b"short").configured
    with pytest.raises(AuthError, match="설정되지 않음"):
        RequestVerifier(b"short").verify("POST", PATH, BODY, _headers(secret=b"short"))


def test_http_unsigned_is_401_problem_and_health_stays_open(fake_vllm):
    app = create_app(secret=SECRET)
    raw = TestClient(app)
    r = raw.post(PATH, json={"query": "균열"})
    assert r.status_code == 401 and r.json()["code"] == "AIP-401-001"
    assert raw.get("/internal/v1/health").status_code == 200
    signed = TestClient(app)
    signed.auth = HmacAuth()
    assert signed.post(PATH, json={"query": "균열"}).status_code == 200
    # 비밀이 없는 워커는 서명이 있어도 거부(실패하는 쪽으로 닫힘)
    unconfigured = TestClient(create_app(secret=b""))
    unconfigured.auth = HmacAuth()
    assert unconfigured.post(PATH, json={"query": "균열"}).status_code == 401


def test_openapi_documents_hmac_security():
    spec = create_app(lambda: None, secret=SECRET).openapi()  # type: ignore[arg-type,return-value]
    assert "AtomHmac" in spec["components"]["securitySchemes"]
    assert spec["paths"][PATH]["post"]["security"] == [{"AtomHmac": []}]
    assert "security" not in spec["paths"]["/internal/v1/health"]["get"]
