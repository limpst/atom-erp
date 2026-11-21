"""CST 청구 · 수금 대사 점수 골든 픽스처 생성기 (docs/CST_BILLING_COLLECTION_DESIGN.md, BR-CST-BIL-006~008).

Java MatchScorer · AgingBucket 과 **독립적으로** 같은 규칙을 Python 으로 다시 구현해 기대값을 만든다.
전부 **합성 사례**다(실제 거래처 · 금액 아님).

  금액 : 입금액 = 청구 합계 50 · 입금액 = 공급가액 35 · 그 밖 0
  이름 : 정규화(법인 표기 · 기호 제거, 소문자) 뒤 한쪽이 다른 쪽을 포함하면 30,
         아니면 bigram Dice(2·교집합 / (|A|+|B|)) × 30 을 HALF_UP 정수로
  날짜 : 입금일 − 발행일 0~30일 20 · 31~90일 10 · 91~180일 5 · 그 밖 0
  aging: 0~30 A · 31~60 B · 61~90 C · 91~180 D · 181 이상 E

실행:  python tools/cst_billing_golden_gen.py   → modules/cst/src/test/resources/golden/billing/match_golden.json
"""
from __future__ import annotations

import json
import re
from collections import Counter
from datetime import date
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "modules/cst/src/test/resources/golden/billing/match_golden.json"

LEGAL = re.compile(r"주식회사|유한책임회사|유한회사|사단법인|재단법인|\(주\)|㈜|\(유\)|\(사\)|\(재\)")
NOISE = re.compile(r"[^0-9A-Za-z가-힣]")


def normalize(raw: str | None) -> str:
    if raw is None:
        return ""
    return NOISE.sub("", LEGAL.sub("", raw)).lower()


def bigrams(s: str) -> Counter:
    if len(s) < 2:
        return Counter([s])
    return Counter(s[i:i + 2] for i in range(len(s) - 1))


def name_score(client: str, payer: str) -> int:
    a, b = normalize(client), normalize(payer)
    if not a or not b:
        return 0
    shorter, longer = (a, b) if len(a) <= len(b) else (b, a)
    if len(shorter) >= 2 and shorter in longer:
        return 30
    ga, gb = bigrams(a), bigrams(b)
    inter = sum(min(n, gb.get(k, 0)) for k, n in ga.items())
    sizes = sum(ga.values()) + sum(gb.values())
    return int((Decimal(30 * 2 * inter) / Decimal(sizes)).quantize(Decimal(1), rounding=ROUND_HALF_UP))


def amount_score(total: int, supply: int, receipt: int) -> int:
    return 50 if receipt == total else 35 if receipt == supply else 0


def date_score(issue: date, value: date) -> int:
    lag = (value - issue).days
    if lag < 0:
        return 0
    return 20 if lag <= 30 else 10 if lag <= 90 else 5 if lag <= 180 else 0


def bucket(days: int) -> str:
    return "A_0_30" if days <= 30 else "B_31_60" if days <= 60 else "C_61_90" if days <= 90 else "D_91_180" if days <= 180 else "E_181_PLUS"


# (id, 공급가액, VAT, 거래처명, 발행일, 입금액, 통장 거래내용, 입금일) — 합성
CASES = [
    ("M-01", 10_000_000, 1_000_000, "합성건설㈜", "2026-03-02", 11_000_000, "합성건설", "2026-03-20"),
    ("M-02", 10_000_000, 1_000_000, "(주)합성건설", "2026-03-02", 10_000_000, "주식회사 합성건설", "2026-04-15"),
    ("M-03", 5_500_000, 550_000, "가나다엔지니어링 주식회사", "2026-01-10", 6_050_000, "가나다엔지니어링-정기점검", "2026-01-25"),
    ("M-04", 3_000_000, 300_000, "합성관리단", "2026-02-01", 3_300_000, "전혀다른상사", "2026-02-10"),
    ("M-05", 3_000_000, 300_000, "합성관리단", "2026-02-01", 3_300_000, "합성 관리단 (1차)", "2026-08-15"),
    ("M-06", 8_000_000, 800_000, "새한구조기술사사무소", "2026-05-01", 8_800_000, "새한구조", "2026-04-28"),
    ("M-07", 8_000_000, 800_000, "새한구조기술사사무소", "2026-05-01", 4_400_000, "새한구조기술사사무소", "2026-05-20"),
    ("M-08", 1_200_000, 120_000, "ABC Tower 관리사무소", "2026-06-01", 1_320_000, "abc tower", "2026-06-30"),
    ("M-09", 1_200_000, 120_000, "ABC Tower 관리사무소", "2026-06-01", 1_320_000, "에이비씨타워", "2026-07-15"),
    ("M-10", 20_000_000, 2_000_000, "재단법인 합성문화재단", "2026-01-05", 22_000_000, "합성문화재단", "2026-07-10"),
    ("M-11", 7_700_000, 770_000, "한빛종합건설(주)", "2026-09-01", 8_470_000, "한빛건설", "2026-09-10"),
    ("M-12", 7_700_000, 770_000, "한빛종합건설(주)", "2026-09-01", 8_470_000, "", "2026-09-10"),
    ("M-13", 4_000_000, 400_000, "가", "2026-09-01", 4_400_000, "가", "2026-09-05"),
    ("M-14", 4_000_000, 400_000, "대한안전진단", "2026-03-01", 4_400_000, "대한안전", "2026-05-31"),
    ("M-15", 4_000_000, 400_000, "대한안전진단", "2026-03-01", 4_400_000, "대한진단안전", "2026-08-29"),
    ("M-16", 9_900_000, 990_000, "유한회사 동서남북", "2026-04-01", 9_900_000, "동서남북(유)", "2026-04-01"),
]
AGING_DAYS = [0, 1, 30, 31, 60, 61, 90, 91, 180, 181, 365, 1000]


def main() -> None:
    cases = []
    for cid, supply, vat, client, issue, receipt, payer, value in CASES:
        a = amount_score(supply + vat, supply, receipt)
        n = name_score(client, payer)
        d = date_score(date.fromisoformat(issue), date.fromisoformat(value))
        cases.append({"id": cid, "supplyKrw": supply, "vatKrw": vat, "clientName": client, "issueDate": issue,
                      "receiptKrw": receipt, "payerText": payer, "valueDate": value,
                      "expected": {"amount": a, "name": n, "date": d, "total": a + n + d, "autoCandidate": a + n + d >= 90}})
    out = {"note": "합성 사례 — 실제 거래처 · 금액 아님. tools/cst_billing_golden_gen.py 로 다시 생성",
           "match": cases, "aging": [{"ageDays": x, "bucket": bucket(x)} for x in AGING_DAYS]}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print("wrote", OUT.relative_to(ROOT), len(cases), "match cases,", len(AGING_DAYS), "aging cases")
    for c in cases:
        print(c["id"], c["expected"])


if __name__ == "__main__":
    main()
