"""ERP → 워커 요청 인증 (보안 검토 AIP-06) — HMAC-SHA256 공유 비밀 + 시각 창 + 재전송(nonce) 차단.

ERP(Java `WorkerRequestSigner`)와 같은 규칙:
    X-Atom-Timestamp: 유닉스 초
    X-Atom-Nonce:     임의 hex(16~64자)
    X-Atom-Signature: v1=hex(HMAC-SHA256(secret, ts + "\n" + nonce + "\n" + METHOD + "\n" + path + "\n" + hex(sha256(body))))

- 비밀은 환경변수 ATOM_AIP_WORKER_SECRET(32바이트 이상). 없거나 짧으면 /internal/v1/* 요청을 전부 401 로 거부한다(실패하는 쪽으로 닫힘).
- 비교는 hmac.compare_digest(타이밍 안전). 시각 차이가 창(기본 300초)을 넘거나 같은 nonce 가 창 안에 다시 오면 거부.
- 비밀 값은 로그 · 응답에 남기지 않는다.
"""
from __future__ import annotations

import hashlib
import hmac
import os
import re
import threading
import time
from typing import Callable, Mapping

MIN_SECRET_BYTES = 32
TIMESTAMP = "x-atom-timestamp"
NONCE = "x-atom-nonce"
SIGNATURE = "x-atom-signature"
_NONCE_RE = re.compile(r"^[0-9a-f]{16,64}$")


class AuthError(Exception):
    """인증 실패 — 사유(reason)는 응답 detail 로만 쓰고 비밀은 담지 않는다."""


def signature(secret: bytes, ts: str, nonce: str, method: str, path: str, body: bytes) -> str:
    canonical = "\n".join([ts, nonce, method.upper(), path, hashlib.sha256(body).hexdigest()])
    return hmac.new(secret, canonical.encode("utf-8"), hashlib.sha256).hexdigest()


def secret_from_env() -> bytes | None:
    v = os.environ.get("ATOM_AIP_WORKER_SECRET", "")
    return v.encode("utf-8") if v else None


class RequestVerifier:
    def __init__(self, secret: bytes | None, window_sec: int = 300, clock: Callable[[], float] = time.time):
        self._secret = secret if secret is not None and len(secret) >= MIN_SECRET_BYTES else None
        self._window = window_sec
        self._clock = clock
        self._seen: dict[str, float] = {}
        self._lock = threading.Lock()

    @property
    def configured(self) -> bool:
        return self._secret is not None

    def verify(self, method: str, path: str, body: bytes, headers: Mapping[str, str]) -> None:
        if self._secret is None:
            raise AuthError("워커 공유 비밀(ATOM_AIP_WORKER_SECRET, 32바이트 이상)이 설정되지 않음")
        ts, nonce, sig = headers.get(TIMESTAMP), headers.get(NONCE), headers.get(SIGNATURE)
        if not ts or not nonce or not sig:
            raise AuthError("서명 헤더 누락")
        if not ts.isdigit() or not _NONCE_RE.match(nonce) or not sig.startswith("v1="):
            raise AuthError("서명 헤더 형식 오류")
        now = self._clock()
        if abs(now - int(ts)) > self._window:
            raise AuthError("요청 시각이 허용 창을 벗어남")
        expected = signature(self._secret, ts, nonce, method, path, body)
        if not hmac.compare_digest(expected.encode("ascii"), sig[3:].encode("ascii", "replace")):
            raise AuthError("서명 불일치")
        with self._lock:
            for k in [k for k, exp in self._seen.items() if exp < now]:
                del self._seen[k]
            if nonce in self._seen:
                raise AuthError("재전송된 요청(nonce 재사용)")
            self._seen[nonce] = now + 2 * self._window
