"""공통 픽스처 — vLLM 은 가짜 HTTP 응답으로 대체(실제 서버 불필요). 이미지는 전부 합성."""
from __future__ import annotations

import json

import httpx
import pytest
from atomeng_common.vlm import client as vlm_client

REQ = httpx.Request("POST", "http://127.0.0.1:8080/v1/chat/completions")


class FakeVllm:
    """httpx.post/get 대체: /models 는 베이스 + crack LoRA, chat 은 logprobs 요청이면 첫 토큰 확률, 아니면 JSON 본문."""

    def __init__(self):
        self.bodies: list[dict] = []
        self.json_replies: list[dict | str] = []
        self.top = [("crack", -0.05), ("normal", -3.0)]
        self.down = False

    def post(self, url, json=None, timeout=None):
        if self.down:
            raise httpx.ConnectError("connection refused", request=REQ)
        self.bodies.append(json)
        if json.get("logprobs"):
            data = {"model": json["model"], "choices": [{"logprobs": {"content": [{"top_logprobs": [
                {"token": t, "logprob": lp} for t, lp in self.top]}]}}]}
        else:
            reply = self.json_replies.pop(0) if self.json_replies else {"findings": []}
            content = reply if isinstance(reply, str) else _dumps(reply)
            data = {"model": json["model"], "choices": [{"message": {"content": content}}]}
        return httpx.Response(200, json=data, request=REQ)

    def get(self, url, timeout=None):
        if self.down:
            raise httpx.ConnectError("connection refused", request=REQ)
        return httpx.Response(200, json={"data": [{"id": "gemma-4-12b-it", "parent": None, "root": "C:/m/base"},
                                                  {"id": "crack", "parent": "gemma-4-12b-it", "root": "C:/m/crack"}]},
                              request=REQ)


def _dumps(o) -> str:
    return json.dumps(o, ensure_ascii=False)


@pytest.fixture()
def fake_vllm(monkeypatch):
    srv = FakeVllm()
    monkeypatch.setattr(vlm_client.httpx, "post", srv.post)
    monkeypatch.setattr(vlm_client.httpx, "get", srv.get)
    return srv


# 보안 검토 AIP-06: 시험 전용 합성 공유 비밀(32바이트 이상) — 실제 비밀 아님
SECRET = b"synthetic-test-secret-0123456789abcdef"


class HmacAuth(httpx.Auth):
    """ERP(Java WorkerRequestSigner)와 같은 규칙으로 요청에 서명한다."""

    requires_request_body = True

    def __init__(self, secret: bytes = SECRET):
        self.secret = secret

    def auth_flow(self, request):
        import secrets
        import time

        from atomeng_aip.auth import signature
        ts, nonce = str(int(time.time())), secrets.token_hex(16)
        request.headers["X-Atom-Timestamp"] = ts
        request.headers["X-Atom-Nonce"] = nonce
        request.headers["X-Atom-Signature"] = "v1=" + signature(self.secret, ts, nonce, request.method,
                                                                request.url.path, request.content)
        yield request


@pytest.fixture()
def client(fake_vllm):
    from fastapi.testclient import TestClient

    from atomeng_aip.app import create_app
    c = TestClient(create_app(secret=SECRET))
    c.auth = HmacAuth()
    return c
