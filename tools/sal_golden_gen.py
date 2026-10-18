"""SAL 대가 산정 골든 픽스처 생성기 (01-IMPL Step 0·5, FR-SAL-009).

Java FeeEngine 과 **독립적으로** 같은 규칙(설계 B7.1 + 01-IMPL Step 5 확정 2가지)을 Python Decimal 로 다시 구현해
기대값을 만든다. 실제 과거 수기 견적 20건이 들어오기 전까지 쓰는 **합성 사례**다(실데이터 아님).

  인·일   : gradeMd = (기준인일 × 보정계수(공종 override 우선) × 등급 배분).quantize(0.01, HALF_UP)
  라인    : amount = round_line(노임단가 × gradeMd)          (FLOOR_1 · FLOOR_10 · HALF_UP_1)
  경비    : amount = round_line(단가 × 수량)
  제경비  : round_line(직접인건비 × 제경비율)
  기술료  : round_line((직접인건비 + 제경비) × 기술료율)
  합계    : round_total(직접인건비 + 직접경비 + 제경비 + 기술료)   (FLOOR_1 · FLOOR_1000 · FLOOR_10000)
  VAT     : floor(합계 × 0.1)

실행:  python tools/sal_golden_gen.py   → modules/sal/src/test/resources/golden/estimates/G-xx.json
"""
from __future__ import annotations

import json
import re
from decimal import ROUND_FLOOR, ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "modules/sal/src/test/resources/golden"
RULES = GOLDEN / "rules"
OUT = GOLDEN / "estimates"

COND = re.compile(r"^(\w+) (>=|<=|==|>|<) ('?)([\w.]+)\3$")


def d(x) -> Decimal:
    return Decimal(str(x))


def round_unit(v: Decimal, mode: str) -> Decimal:
    if mode == "FLOOR_1":
        return v.quantize(Decimal(1), rounding=ROUND_FLOOR)
    if mode == "HALF_UP_1":
        return v.quantize(Decimal(1), rounding=ROUND_HALF_UP)
    unit = {"FLOOR_10": 10, "FLOOR_1000": 1000, "FLOOR_10000": 10000}[mode]
    return (v / unit).quantize(Decimal(1), rounding=ROUND_FLOOR) * unit


def variables(fac: dict, as_of: str) -> dict:
    return {
        "years": int(as_of[:4]) - fac["completionYear"],
        "structure": fac["structure"],
        "basementFloors": fac["basementFloors"],
        "inUse": fac["inUse"],
        "grossAreaM2": d(fac["grossAreaM2"]),
        "floors": fac["floors"],
    }


def matches(when: str, vars_: dict) -> bool:
    m = COND.match(when)
    name, op, lit = m.group(1), m.group(2), m.group(4)
    left = vars_[name]
    if isinstance(left, bool):
        return op == "==" and left == (lit == "true")
    if isinstance(left, str):
        return op == "==" and left == lit
    left, right = d(left), d(lit)
    return {">=": left >= right, "<=": left <= right, "==": left == right, ">": left > right, "<": left < right}[op]


def calculate(rule: dict, fac: dict, cmd: dict, as_of: str) -> dict:
    svc = next(s for s in rule["baseManDays"]
               if s["serviceType"] == cmd["serviceType"] and s["facilityClass"] == fac["facilityClass"])
    area = d(fac["grossAreaM2"])
    band = next(b for b in svc["areaBands"] if d(b["fromM2"]) <= area < d(b["toM2"]))
    vars_ = variables(fac, as_of)
    adj = Decimal(1)
    for f in rule["adjustmentFactors"]:
        if matches(f["when"], vars_):
            adj *= d(f["factor"])
    overrides = {o["workItem"]: d(o["adjFactor"]) for o in cmd.get("manDayOverrides", [])}
    line_mode, total_mode = rule["rounding"]["line"], rule["rounding"]["total"]
    lines, labor = [], Decimal(0)
    for wi, base in band["workItems"].items():
        item_adj = overrides.get(wi, adj)
        md = d(base) * item_adj
        for grade, mix in svc["gradeMix"].items():
            gmd = (md * d(mix)).quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
            unit = d(rule["laborUnitPrices"][grade])
            amt = round_unit(unit * gmd, line_mode)
            labor += amt
            lines.append({"workItem": wi, "grade": grade, "manDays": str(gmd), "amount": int(amt)})
    expense = Decimal(0)
    for e in cmd.get("expenses", []):
        expense += round_unit(d(e["unitPrice"]) * d(e["quantity"]), line_mode)
    oh_rate = d(cmd.get("overheadRate") or rule["overheadRate"]["default"])
    tf_rate = d(cmd.get("techFeeRate") or rule["techFeeRate"]["default"])
    overhead = round_unit(labor * oh_rate, line_mode)
    tech_fee = round_unit((labor + overhead) * tf_rate, line_mode)
    subtotal = round_unit(labor + expense + overhead + tech_fee, total_mode)
    vat = round_unit(subtotal * Decimal("0.1"), "FLOOR_1")
    return {"directLabor": int(labor), "directExpense": int(expense), "overhead": int(overhead),
            "techFee": int(tech_fee), "subtotal": int(subtotal), "vat": int(vat), "lines": lines}


def fac(area, completion=2005, structure="RC", basement=1, floors=8, in_use=False, cls="BUILDING"):
    return {"facilityClass": cls, "grossAreaM2": area, "structure": structure, "basementFloors": basement,
            "floors": floors, "completionYear": completion, "inUse": in_use}


TRAVEL = {"item": "TRAVEL", "quantity": 12, "unitPrice": 150000}
EQUIP = {"item": "EQUIPMENT_DEPRECIATION", "quantity": 1, "unitPrice": 2400000}
TEST12 = {"item": "MATERIAL_TEST", "quantity": 12, "unitPrice": 380000}
PRINT = {"item": "PRINTING", "quantity": 1, "unitPrice": 450000}
ODD = {"item": "OTHER", "quantity": 2.5, "unitPrice": 33333}

# (caseId, 설명, ruleFixture, asOfDate, facility, command)
CASES = [
    ("G-01", "정밀안전진단 소형 RC, 보정 없음, 기본 요율", "fee-rule-2026.json", "2026-03-02",
     fac(8500), {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL]}),
    ("G-02", "정밀안전진단 중형, 30년 경과(AGE)", "fee-rule-2026.json", "2026-05-10",
     fac(18420, completion=1994), {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL, PRINT]}),
    ("G-03", "정밀안전진단 대형, 지하 4층·사용 중(UNDERGROUND·OCCUPIED)", "fee-rule-2026.json", "2026-10-15",
     fac(42350, completion=1998, basement=4, floors=18, in_use=True),
     {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL, EQUIP, TEST12, PRINT]}),
    ("G-04", "철골조(STEEL) + AGE + OCCUPIED, 제경비 1.15·기술료 0.25", "fee-rule-2026.json", "2026-07-01",
     fac(25000, completion=1990, structure="STEEL", in_use=True),
     {"serviceType": "PRECISE_DIAG", "overheadRate": 1.15, "techFeeRate": 0.25, "expenses": [TRAVEL]}),
    ("G-05", "현장조사 공종 보정계수 override 1.25", "fee-rule-2026.json", "2026-10-15",
     fac(42350, completion=1998, basement=4, in_use=True),
     {"serviceType": "PRECISE_DIAG", "expenses": [EQUIP],
      "manDayOverrides": [{"workItem": "FIELD", "adjFactor": 1.25, "reason": "상시 사용 중 야간작업(합성)"}]}),
    ("G-06", "정밀점검 소형, 경비 없음", "fee-rule-2026.json", "2026-02-01",
     fac(3200), {"serviceType": "PRECISE_INSP"}),
    ("G-07", "정밀점검 중형, 모든 보정", "fee-rule-2026.json", "2026-04-20",
     fac(15000, completion=1980, structure="STEEL", basement=3, in_use=True),
     {"serviceType": "PRECISE_INSP", "expenses": [TRAVEL, PRINT]}),
    ("G-08", "내진성능평가, 기술료 상한 0.40", "fee-rule-2026.json", "2026-09-09",
     fac(12000, completion=1999), {"serviceType": "SEISMIC_EVAL", "techFeeRate": 0.40, "expenses": [PRINT]}),
    ("G-09", "2025 규칙(노임단가 2025) — 기준일 2025-12-31 (TC-SAL-010)", "fee-rule-2025.json", "2025-12-31",
     fac(18420, completion=1994), {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL]}),
    ("G-10", "2026 규칙 — 기준일 2026-01-01 (TC-SAL-010 대조)", "fee-rule-2026.json", "2026-01-01",
     fac(18420, completion=1994), {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL]}),
    ("G-11", "AGE 경계: 정확히 30년(적용)", "fee-rule-2026.json", "2026-06-30",
     fac(9999, completion=1996), {"serviceType": "PRECISE_DIAG"}),
    ("G-12", "AGE 경계: 29년(미적용)", "fee-rule-2026.json", "2026-06-30",
     fac(9999, completion=1997), {"serviceType": "PRECISE_DIAG"}),
    ("G-13", "면적 구간 경계 10,000㎡ → 두 번째 구간", "fee-rule-2026.json", "2026-03-15",
     fac(10000), {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL]}),
    ("G-14", "소수 수량 경비(2.5 × 33,333) 절사", "fee-rule-2026.json", "2026-03-15",
     fac(5000), {"serviceType": "PRECISE_INSP", "expenses": [ODD, TRAVEL]}),
    ("G-15", "라인 HALF_UP_1 · 합계 FLOOR_10000 규칙", "fee-rule-2026-rounding.json", "2026-08-01",
     fac(18420, completion=1994, in_use=True), {"serviceType": "PRECISE_DIAG", "expenses": [ODD, TRAVEL]}),
    ("G-16", "라인 FLOOR_10 · 합계 FLOOR_1 규칙", "fee-rule-2026-floor10.json", "2026-08-01",
     fac(42350, completion=1998, basement=4, in_use=True),
     {"serviceType": "PRECISE_DIAG", "expenses": [ODD, EQUIP]}),
    ("G-17", "여러 공종 override(DATA 1.0 · REPORT 1.3) + 전 보정", "fee-rule-2026.json", "2026-11-11",
     fac(28000, completion=1975, structure="STEEL", basement=5, in_use=True),
     {"serviceType": "PRECISE_DIAG", "expenses": [TRAVEL, EQUIP, TEST12, PRINT],
      "manDayOverrides": [{"workItem": "DATA", "adjFactor": 1.0, "reason": "자료 완비(합성)"},
                          {"workItem": "REPORT", "adjFactor": 1.3, "reason": "영문 보고서(합성)"}]}),
    ("G-18", "요율 상·하한 동시(제경비 1.20 · 기술료 0.20)", "fee-rule-2026.json", "2026-12-31",
     fac(31000, completion=2010, basement=2), {"serviceType": "PRECISE_DIAG", "overheadRate": 1.20,
                                               "techFeeRate": 0.20, "expenses": [TEST12]}),
    ("G-19", "내진성능평가 사용 중 + 지하 3층, 2025 규칙", "fee-rule-2025.json", "2025-06-01",
     fac(19999.5, completion=2001, basement=3, in_use=True), {"serviceType": "SEISMIC_EVAL",
                                                              "expenses": [TRAVEL, PRINT]}),
    ("G-20", "정밀점검 2025 규칙, 제경비 1.18", "fee-rule-2025.json", "2025-02-28",
     fac(21000.75, completion=1988, structure="STEEL"), {"serviceType": "PRECISE_INSP", "overheadRate": 1.18,
                                                         "expenses": [TRAVEL]}),
    ("G-21", "소수 연면적 0.5㎡ 초소형", "fee-rule-2026.json", "2026-01-15",
     fac(0.5, completion=2020, floors=1, basement=0), {"serviceType": "PRECISE_INSP"}),
    ("G-22", "대형 구간 상단 49,999㎡ + 기술료 0.33", "fee-rule-2026.json", "2026-10-01",
     fac(49999, completion=1993, basement=6, floors=30, in_use=True),
     {"serviceType": "PRECISE_DIAG", "techFeeRate": 0.33, "expenses": [TRAVEL, EQUIP, TEST12, PRINT, ODD]}),
]


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob("G-*.json"):
        old.unlink()
    for case_id, desc, rule_file, as_of, facility, command in CASES:
        rule = json.loads((RULES / rule_file).read_text(encoding="utf-8"))
        expected = calculate(rule, facility, command, as_of)
        doc = {"caseId": case_id, "source": "합성 사례(synthetic) — " + desc, "asOfDate": as_of,
               "ruleFixture": rule_file, "facility": facility, "command": command, "expected": expected}
        (OUT / f"{case_id}.json").write_text(json.dumps(doc, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(case_id, expected["subtotal"], desc)


if __name__ == "__main__":
    main()
