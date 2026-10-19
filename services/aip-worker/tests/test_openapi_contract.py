"""계약 시험 — 기록된 OpenAPI(contract/openapi.json)가 현재 앱과 같아야 한다. ERP Java 시험이 같은 파일을 읽는다."""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))

import export_openapi  # noqa: E402


def test_recorded_openapi_matches_app():
    recorded = (ROOT / "contract" / "openapi.json").read_text(encoding="utf-8")
    assert recorded == export_openapi.render(), "계약 변경 — uv run python tools/export_openapi.py 로 다시 기록"


def test_contract_has_all_worker_paths():
    spec = json.loads((ROOT / "contract" / "openapi.json").read_text(encoding="utf-8"))
    for p in ("/internal/v1/health", "/internal/v1/crack/score", "/internal/v1/findings/generate",
              "/internal/v1/crack/measure", "/internal/v1/kb/search", "/internal/v1/report/draft",
              "/internal/v1/report/review"):
        assert p in spec["paths"]
