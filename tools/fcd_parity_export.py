"""FCD 골든 패리티 픽스처 내보내기 — Python 프로토타입(atom-fcd, 읽기 전용)을 실행해 Java 이식의 기대값을 만든다.

실행(프로토타입 저장소에는 아무것도 쓰지 않음: 바이트코드·캐시·내보내기 파일 모두 OUT 쪽):
    set PYTHONDONTWRITEBYTECODE=1
    C:\\Users\\atomS\\atom-fcd\\.venv\\Scripts\\python.exe tools\\fcd_parity_export.py <scratch_dir>

산출물(atom-erp 안):
  modules/fcd/src/test/resources/fcd/parity/fcd-parity-envs.json   (A) 평가기·L4 패리티: Python 이 만든 env(tx·ctx·l3) + 적중·점수
  modules/fcd/src/test/resources/fcd/parity/fcd-parity-l1.json     (B) L1 특징·적중 패리티: 원천 거래(카드 부분집합) → tx 특징 · L1 적중
  modules/fcd/src/main/resources/fcd/kr-holidays.csv                공휴일(holidays.KR 2024–2028, 대체·임시 포함)
  modules/fcd/src/main/resources/fcd/categories.csv                 내부 업종 ↔ 카드사 업종명 · MCC (합성 시드)
  modules/fcd/src/main/resources/fcd/rulesets/*.yaml                룰셋 YAML 원본 복사(sha256 은 픽스처에 기록)
모든 데이터는 합성 데이터다(가상 BIN 999999 · (예시) 가맹점 · 999-xx 사업자번호).
"""
from __future__ import annotations

import datetime as dt
import hashlib
import json
import math
import random
import sys
from collections import defaultdict
from pathlib import Path
from types import SimpleNamespace

import numpy as np

FCD = Path(r"C:\Users\atomS\atom-fcd")
ERP = Path(__file__).resolve().parents[1]
MOD = ERP / "modules" / "fcd" / "src"

from atomeng_aip.fraud.detect.context import SyntheticErp  # noqa: E402
from atomeng_aip.fraud.detect.engine import score_frame  # noqa: E402
from atomeng_aip.fraud.detect.features import CATEGORY_FROM_HINT, build_features  # noqa: E402
from atomeng_aip.fraud.detect.l3 import compute_l3  # noqa: E402
from atomeng_aip.fraud.domain import _kr  # noqa: E402
from atomeng_aip.fraud.pipeline import build_dataset, ingest_all  # noqa: E402
from atomeng_aip.fraud.ruleset_registry import RULESET_DIR, load_rulesets  # noqa: E402
from atomeng_aip.fraud.synth.world import CATEGORY_INFO  # noqa: E402

SEED = 20261018
BASELINE_END = dt.date(2026, 4, 1)
L1_TX_KEYS = ["amount", "gross_amount", "cancelled", "cancelled_amount", "status", "hour", "local_time", "date",
              "weekday", "precision", "is_night", "is_deep_night", "is_holiday", "category", "category_name", "policy",
              "cat_max", "is_online", "is_overseas", "country", "blacklisted", "related_party", "pg_unresolved",
              "merchant_name", "card_id", "card_type", "card_status", "card_closed_on", "card_inactive", "limit_txn",
              "limit_day", "limit_month", "online_personal", "day_total", "month_cum", "hug", "hug_level",
              "split_hit", "split_cnt", "split_total", "split_intent", "split_group", "split_multi_hit",
              "split_multi_users", "split_multi_total", "recurring", "preapproved", "preapproved_cat"]


def conv(o):
    if isinstance(o, (np.integer,)):
        return int(o)
    if isinstance(o, (np.floating,)):
        return float(o)
    if isinstance(o, np.bool_):
        return bool(o)
    if isinstance(o, np.ndarray):
        return o.tolist()
    if isinstance(o, (dt.date, dt.datetime)):
        return o.isoformat()
    if isinstance(o, (set, frozenset, tuple)):
        return list(o)
    return str(o)


def clean(v):
    """numpy·튜플 등을 JSON 기본형으로(NaN 은 보존 — Python 의미 그대로)."""
    if isinstance(v, dict):
        return {str(k): clean(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [clean(x) for x in v]
    if isinstance(v, (np.integer,)):
        return int(v)
    if isinstance(v, (np.floating,)):
        return float(v)
    if isinstance(v, np.bool_):
        return bool(v)
    if isinstance(v, (dt.date, dt.datetime)):
        return v.isoformat()
    return v


def hit_json(h):
    return {"rule_id": h.rule_id, "layer": h.layer, "kind": h.kind, "strength": h.strength, "weight": h.weight,
            "floor": h.floor_score, "severity": h.severity, "message": h.message,
            "review_required": h.review_required, "evidence": clean(h.evidence)}


def score_json(s):
    return {"score": s.score, "grade": s.grade, "R": s.R, "Rm": s.R_mitigated, "floor": s.floor,
            "ruleset": s.ruleset_version, "hits": [h.rule_id for h in s.hits],
            "mitigations": [h.rule_id for h in s.mitigations], "flags": [h.rule_id for h in s.flags],
            "reasons": s.reasons, "contributions": [{"rule_id": c["rule_id"], "c": c["c"]} for c in s.contributions],
            "hard": s.hard, "review_required": s.review_required, "unverified_used": s.unverified_used}


def main(scratch: Path) -> None:
    scratch.mkdir(parents=True, exist_ok=True)
    cache = FCD / "data" / "cache" / f"dataset_{SEED}_1.5.pkl"   # 있으면 읽기만(쓰기 없음)
    ds = build_dataset(SEED, 1.5, cache if cache.exists() else None)
    ledger, _ = ingest_all(ds, scratch / "synthetic")
    erp = SyntheticErp.from_dataset(ds)
    reg = load_rulesets()
    params_now = reg.resolve(dt.date(2026, 7, 1)).params
    fr = build_features(ledger, erp, params_now)
    compute_l3(fr, BASELINE_END, ds.world.projects)
    res = score_frame(fr, reg, ("L1", "L2", "L3"))
    rows = fr.rows
    rng = random.Random(SEED)

    # ---------------- (A) 평가기 · L4 패리티 ----------------
    by_rule = defaultdict(list)
    nohit = []
    for r in rows:
        rs = reg.resolve(r["date"])
        env = {"tx": r["tx"], "ctx": r["ctx"], "l3": r.get("l3") or {}, "params": rs.params}
        raw = rs.evaluate(env, r["date"], layers={"L1", "L2", "L3"})
        if raw:
            for h in raw:
                by_rule[h.rule_id].append(r["txn_id"])
        else:
            nohit.append(r["txn_id"])
    pick = set()
    for rid, ids in sorted(by_rule.items()):
        pick.update(ids[:12])
        if len(ids) > 12:
            pick.update(rng.sample(ids[12:], min(6, len(ids) - 12)))
    pick.update(rng.sample(nohit, 150))
    early = [r["txn_id"] for r in rows if r["date"] < dt.date(2026, 4, 1)]
    pick.update(rng.sample(early, 120))
    envs = []
    idx = {r["txn_id"]: r for r in rows}
    for tid in sorted(pick):
        r = idx[tid]
        rs = reg.resolve(r["date"])
        env = {"tx": r["tx"], "ctx": r["ctx"], "l3": r.get("l3") or {}, "params": rs.params}
        raw = rs.evaluate(env, r["date"], layers={"L1", "L2", "L3"})
        envs.append({"txn_id": tid, "date": r["date"].isoformat(), "job_type": r.get("job_type"),
                     "tx": clean(r["tx"]), "ctx": clean(r["ctx"]), "l3": clean(r.get("l3") or {}),
                     "ruleset": rs.version, "raw_hits": [hit_json(h) for h in raw],
                     "score": score_json(res[tid])})
    rule_files = sorted(p for p in Path(RULESET_DIR).glob("*.yaml"))
    lf = {p.name: p.read_bytes().replace(b"\r\n", b"\n") for p in rule_files}   # 줄끝 LF 정규화(저장소 eol=lf)
    sha = {n: hashlib.sha256(b).hexdigest() for n, b in lf.items()}

    # ---------------- (B) L1 특징 · 적중 패리티(카드 부분집합 · 4개월) ----------------
    win_from, win_to = dt.date(2026, 6, 1), dt.date(2026, 10, 1)
    l1_rules = {x.rule_id for rs in reg.all() for x in rs.rules if x.layer == "L1"}
    card_rules = defaultdict(set)
    for r in rows:
        if not (win_from <= r["date"] < win_to):
            continue
        for h in res[r["txn_id"]].hits + res[r["txn_id"]].flags:
            if h.rule_id in l1_rules:
                card_rules[r["card_id"]].add(h.rule_id)
    chosen, covered = [], set()
    while len(chosen) < 24:
        best = max((c for c in card_rules if c not in chosen), key=lambda c: (len(card_rules[c] - covered), c), default=None)
        if best is None or not (card_rules[best] - covered):
            break
        chosen.append(best)
        covered |= card_rules[best]
    multi = [r["card_id"] for r in rows if win_from <= r["date"] < win_to and r["tx"].get("split_multi_hit")]
    for c in multi[:6]:
        if c not in chosen:
            chosen.append(c)
    shared = sorted(c.card_id for c in ds.world.cards.values() if c.card_type == "SHARED")[:3]
    for c in shared:
        if c not in chosen:
            chosen.append(c)
    extra = sorted(c for c in card_rules if c not in chosen)
    chosen += rng.sample(extra, min(6, len(extra)))
    cset = set(chosen)
    sub_txns = {k: t for k, t in ledger.txns.items() if t.card_id in cset and win_from <= t.approved_at.date() < win_to}
    sub = SimpleNamespace(cards=ledger.cards, merchants=ledger.merchants, txns=sub_txns)
    fr_b = build_features(sub, erp, params_now)
    res_b = score_frame(fr_b, reg, ("L1",))
    order = sorted(sub_txns.values(), key=lambda t: t.approved_at)   # build_features 와 같은 안정 정렬
    seq = {t.txn_id: i for i, t in enumerate(order)}
    merchants_used, emps_used = {}, {}
    l1_rows = []
    for r in fr_b.rows:
        t = sub_txns[r["txn_id"]]
        m = ledger.merchants.m.get(t.merchant_id)
        if m:
            merchants_used[m.merchant_id] = {"name": m.name, "category": m.category, "is_online": bool(m.is_online),
                                             "country": m.country, "blacklisted": bool(m.blacklisted),
                                             "related_party": bool(m.related_party)}
        if r["emp_id"]:
            e = erp.employee(r["emp_id"])
            emps_used[r["emp_id"]] = {"resigned_on": e.resigned_on.isoformat() if e and e.resigned_on else None}
        rs = reg.resolve(r["date"])
        env = {"tx": r["tx"], "ctx": r["ctx"], "l3": {}, "params": rs.params}
        raw = rs.evaluate(env, r["date"], layers={"L1"})
        l1_rows.append({
            "txn_id": t.txn_id, "seq": seq[t.txn_id], "card_id": t.card_id, "emp_id": r["emp_id"],
            "approved_at": t.approved_at.isoformat(), "precision": t.precision, "amount_krw": t.amount_krw,
            "cancelled_amount_krw": t.cancelled_amount_krw, "status": t.status, "currency": t.currency,
            "country": t.country, "is_online": t.is_online, "merchant_id": t.merchant_id,
            "merchant_name": t.merchant_name, "category_hint": t.category_hint, "pg_unresolved": bool(t.pg_unresolved),
            "port": {"preapproved": r["tx"]["preapproved"], "preapproved_cat": r["tx"]["preapproved_cat"],
                     "trip_overseas": r["ctx"]["trip_overseas"], "giftcard_recipients": r["ctx"]["giftcard_recipients"]},
            "expected_tx": {k: clean(r["tx"].get(k)) for k in L1_TX_KEYS},
            "expected_shared_unassigned": r["ctx"]["shared_unassigned"],
            "raw_hits": [hit_json(h) for h in raw], "score": score_json(res_b[t.txn_id])})
    cards = {cid: {"card_type": c.card_type, "status": c.status,
                   "closed_on": c.closed_on.isoformat() if c.closed_on else None,
                   "limit_per_txn": c.limit_per_txn, "limit_daily": c.limit_daily, "limit_monthly": c.limit_monthly,
                   "card_company": c.card_company}
             for cid, c in ledger.cards.items() if cid in cset}

    out_t = MOD / "test" / "resources" / "fcd" / "parity"
    out_t.mkdir(parents=True, exist_ok=True)
    meta = {"generator": "tools/fcd_parity_export.py", "prototype": "atom-fcd (read-only)", "seed": SEED,
            "generated_at": dt.datetime.now().isoformat(timespec="seconds"), "ruleset_sha256": sha,
            "note": "합성 데이터 전용"}
    (out_t / "fcd-parity-envs.json").write_text(json.dumps({"meta": meta, "rows": envs}, ensure_ascii=False,
                                                         default=conv), encoding="utf-8")
    (out_t / "fcd-parity-l1.json").write_text(json.dumps(
        {"meta": meta | {"window": [win_from.isoformat(), win_to.isoformat()], "cards_chosen": chosen},
         "params_ruleset": reg.resolve(win_from).version,
         "cards": cards, "merchants": merchants_used, "employees": emps_used, "rows": l1_rows},
        ensure_ascii=False, default=conv), encoding="utf-8")

    # ---------------- (C) 수집 패리티: 카드사 양식 3종(2025-10) → Python parse_file 결과 ----------------
    from atomeng_aip.fraud.ingest.canonical import CardRegistry
    from atomeng_aip.fraud.ingest.fileimport import parse_file
    registry = CardRegistry(ds.world.cards.values())
    samples = out_t.parent / "samples"
    samples.mkdir(parents=True, exist_ok=True)
    ingest = []
    for name in ("acard_approval_2025-10_cp949.csv", "bcard_usage_2025-10_utf8bom.csv", "ccard_purchase_2025-10.xlsx"):
        data = (scratch / "synthetic" / name).read_bytes()
        (samples / name).write_bytes(data)
        res = parse_file(data, registry, filename=name)
        ingest.append({"file": name, "summary": res.summary(),
                       "errors": [{"row_no": e.row_no, "code": e.code} for e in res.errors],
                       "rows": [{"channel": r.channel, "kind": r.kind, "card_id": r.card_id, "approval_no": r.approval_no,
                                 "approved_at": r.approved_at.isoformat() if r.approved_at else None,
                                 "precision": r.precision, "amount_krw": r.amount_krw, "merchant_name": r.merchant_name,
                                 "biz_reg_no": r.biz_reg_no, "category_hint": r.category_hint, "currency": r.currency,
                                 "cancel_amount": r.cancel_amount, "partial": r.partial,
                                 "cancel_at": r.cancel_at.isoformat() if r.cancel_at else None, "row_no": r.row_no}
                                for r in res.rows]})
    cards_all = [{"card_id": c.card_id, "card_company": c.card_company, "first6": c.first6, "last4": c.last4,
                  "alias": c.alias} for c in ds.world.cards.values()]
    (out_t / "fcd-parity-ingest.json").write_text(json.dumps({"meta": meta, "cards": cards_all, "files": ingest},
                                                           ensure_ascii=False, default=conv), encoding="utf-8")

    out_m = MOD / "main" / "resources" / "fcd"
    (out_m / "rulesets").mkdir(parents=True, exist_ok=True)
    for p in rule_files:
        (out_m / "rulesets" / p.name).write_bytes(lf[p.name])
    hol = sorted(_kr().items())
    with open(out_m / "kr-holidays.csv", "w", encoding="utf-8", newline="\n") as f:
        f.write("date,name\n")
        for d, n in hol:
            f.write(f"{d.isoformat()},{str(n).replace(',', ' ')}\n")
    with open(out_m / "categories.csv", "w", encoding="utf-8", newline="\n") as f:
        f.write("code,name_ko,mcc\n")
        for cat, (name, mcc) in CATEGORY_INFO.items():
            f.write(f"{cat},{name},{mcc}\n")
    hint = {k: v for k, v in CATEGORY_FROM_HINT.items()}
    (out_m / "category-hints.json").write_text(json.dumps(hint, ensure_ascii=False, indent=0), encoding="utf-8")
    print(json.dumps({"envs": len(envs), "l1_rows": len(l1_rows), "cards": len(chosen),
                      "rules_covered_A": sorted(by_rule), "rules_covered_B": sorted(covered)}, ensure_ascii=False))


if __name__ == "__main__":
    main(Path(sys.argv[1]))
