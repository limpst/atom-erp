"""워커 OpenAPI 를 contract/openapi.json 에 기록한다(ERP Java 계약 시험이 같은 파일을 읽는다).

    uv run python tools/export_openapi.py          # 기록
    uv run python tools/export_openapi.py --check  # 기록본과 다르면 종료 코드 1
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "contract" / "openapi.json"


def render() -> str:
    from atomeng_aip.app import create_app
    spec = create_app(lambda: None).openapi()   # type: ignore[arg-type,return-value]
    return json.dumps(spec, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    text = render()
    if "--check" in sys.argv:
        same = OUT.exists() and OUT.read_text(encoding="utf-8") == text
        print("contract OK" if same else "contract DRIFT — tools/export_openapi.py 로 다시 기록하세요")
        return 0 if same else 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(text, encoding="utf-8", newline="\n")
    print(f"wrote {OUT}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
