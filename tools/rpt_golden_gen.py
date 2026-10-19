"""RPT 상태평가 · 안전등급 골든 픽스처 생성기 (06-IMPL Step 2·6, FR-RPT-006/007, TC-RPT-001/002).

Java GradeEngine 과 **독립적으로** 같은 규칙(설계 B7.1 + 아래 확정 사항)을 Python Decimal 로 다시 구현해 기대값을 만든다.
과거 실제 보고서 10건(골든 데이터셋)이 들어오기 전까지 쓰는 **합성 사례**다(실데이터 아님). 규칙 값은 ⚠️ 예시.

  지표(부재 그룹 = 동 · 층 · 부재유형)
    crack_width_mm        = 균열(CRACK) 최대 폭(mm), 없으면 0
    spall_m2_per_member   = Σ 박리·박락(SPALL) 면적(m²) / 부재 수            (소수 4자리 HALF_UP)
    rebar_exposure_ratio  = 철근노출(REBAR_EXPOSURE) 부재 수(중복 제거) / 부재 수 (소수 4자리 HALF_UP)
  등급 구간    : bands 를 순서대로, value < lt 인 첫 구간(마지막 else)
  분포 보정    : 결함 부재 비율(소수 4자리) < extent.lt 이고 등급이 floor 보다 나쁘면 max(등급+delta, floor)
  부재 등급    : 규칙에 있는 지표 중 최악(지표 없음 = a)
  층 점수      : Σ(점수×가중치) / Σ가중치 (존재 부재만 재정규화, 소수 6자리 HALF_UP)
  동 점수      : AVG = 층 평균(6자리) / AVG_WORST_BLEND = (1−w)×평균(6자리) + w×최악층 (6자리)
  시설물 점수  : 동 연면적 가중평균 (소수 3자리 HALF_UP)
  등급         : gradeBand.bands (score < lt 첫 구간) → 하한 규칙(주요부재 등급 ≥ r 이면 최소 minGrade)

실행:  python tools/rpt_golden_gen.py
  → modules/rpt/src/test/resources/golden/rules/BLD_RC-*.json   (규칙 픽스처 = 시드 원문)
  → modules/rpt/src/test/resources/golden/cases/GOLD-xx.json    (합성 사례 12건)
  → modules/rpt/src/main/resources/db/migration/rpt/V0021__rpt_rule_seed.sql (규칙 시드 — 픽스처와 같은 본문)
"""
from __future__ import annotations

import json
import random
import uuid
from decimal import ROUND_HALF_UP, Decimal, getcontext
from pathlib import Path

getcontext().prec = 60

ROOT = Path(__file__).resolve().parents[1]
GOLDEN = ROOT / "modules/rpt/src/test/resources/golden"
SEED = ROOT / "modules/rpt/src/main/resources/db/migration/rpt/V0021__rpt_rule_seed.sql"

RATINGS = ["a", "b", "c", "d", "e"]
GRADES = ["A", "B", "C", "D", "E"]
NOTE = "예시값 — 「시설물의 안전 및 유지관리 실시 세부지침」 상태평가 기준표로 교체 ⚠️ 법령 최신본 확인 필요"


def bands(*pairs, last):
    out = [{"lt": lt, "rating": r} for lt, r in pairs]
    out.append({"else": True, "rating": last})
    return out


def rules_2026() -> dict:
    crack_cb = bands(("0.1", "a"), ("0.2", "b"), ("0.3", "c"), ("1.0", "d"), last="e")
    crack_slab = bands(("0.1", "a"), ("0.3", "b"), ("0.5", "c"), ("1.0", "d"), last="e")
    crack_wall = bands(("0.2", "a"), ("0.3", "b"), ("0.5", "c"), ("1.0", "d"), last="e")
    crack_fdn = bands(("0.1", "a"), ("0.2", "b"), ("0.3", "c"), ("0.5", "d"), last="e")
    spall = bands(("0.01", "a"), ("0.05", "b"), ("0.15", "c"), ("0.40", "d"), last="e")
    rebar = bands(("0.001", "a"), ("0.05", "b"), ("0.15", "c"), ("0.30", "d"), last="e")
    items = {}
    for m, cb in (("FOUNDATION", crack_fdn), ("COLUMN", crack_cb), ("BEAM", crack_cb), ("SLAB", crack_slab),
                  ("WALL", crack_wall)):
        items[f"{m}.crack_width_mm"] = {"unit": "mm", "bands": cb}
    for m in ("COLUMN", "BEAM", "SLAB", "WALL"):
        items[f"{m}.spall_m2_per_member"] = {"unit": "m2/member", "bands": spall}
    for m in ("COLUMN", "BEAM", "SLAB"):
        items[f"{m}.rebar_exposure_ratio"] = {"unit": "ratio", "bands": rebar}
    return {
        "facilityClass": "BLD_RC",
        "version": "2026.1",
        "effectiveFrom": "2026-01-01",
        "effectiveTo": None,
        "legalBasis": NOTE,
        "condRating": {"items": items,
                       "extent": {"metric": "affected_member_ratio", "lt": "0.10", "delta": -1, "floor": "b"}},
        "gradeWeight": {"weights": {"FOUNDATION": "0.20", "COLUMN": "0.25", "BEAM": "0.20", "SLAB": "0.20",
                                    "WALL": "0.15"},
                        "floorAgg": {"mode": "AVG_WORST_BLEND", "worstWeight": "0.30"}},
        "gradeBand": {"scores": {"a": "1", "b": "2", "c": "3", "d": "4", "e": "5"},
                      "bands": [{"lt": "1.5", "grade": "A"}, {"lt": "2.5", "grade": "B"},
                                {"lt": "3.5", "grade": "C"}, {"lt": "4.5", "grade": "D"},
                                {"else": True, "grade": "E"}],
                      "floorRules": [{"id": "FR-MAJOR-E-MIN-D", "memberTypes": ["FOUNDATION", "COLUMN", "BEAM"],
                                      "rating": "e", "minGrade": "D"},
                                     {"id": "FR-MAJOR-D-MIN-C", "memberTypes": ["FOUNDATION", "COLUMN"],
                                      "rating": "d", "minGrade": "C"}]},
    }


def rules_2025() -> dict:
    r = json.loads(json.dumps(rules_2026()))
    r["version"] = "2025.1"
    r["effectiveFrom"] = "2025-01-01"
    r["effectiveTo"] = "2026-01-01"
    cb = bands(("0.1", "a"), ("0.2", "b"), ("0.4", "c"), ("1.0", "d"), last="e")
    r["condRating"]["items"]["COLUMN.crack_width_mm"]["bands"] = cb
    r["condRating"]["items"]["BEAM.crack_width_mm"]["bands"] = cb
    r["condRating"]["extent"]["lt"] = "0.05"
    r["gradeWeight"]["floorAgg"] = {"mode": "AVG"}
    r["gradeBand"]["floorRules"] = r["gradeBand"]["floorRules"][:1]
    return r


# ---------------------------------------------------------------------------------------------- 독립 구현
def d(x) -> Decimal:
    return Decimal(str(x))


def q(v: Decimal, places: int) -> Decimal:
    return v.quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP)


def plain(v: Decimal) -> str:
    return format(v, "f")


def band_rate(bs, value: Decimal):
    for b in bs:
        if b.get("else") or value < d(b["lt"]):
            return b["rating"], (b.get("lt"))
    raise ValueError("no band")


def grade_band(bs, value: Decimal):
    for b in bs:
        if b.get("else") or value < d(b["lt"]):
            return b["grade"]
    raise ValueError("no band")


def compute(snapshot: dict, rules: dict) -> dict:
    groups = sorted(snapshot["memberGroups"], key=lambda g: (g["buildingCode"], g["floorCode"], g["memberType"]))
    defects = snapshot["defects"]
    items = rules["condRating"]["items"]
    extent = rules["condRating"].get("extent")
    weights = rules["gradeWeight"]["weights"]
    scores = rules["gradeBand"]["scores"]
    ratings = []
    for g in groups:
        key = (g["buildingCode"], g["floorCode"], g["memberType"])
        ds = [x for x in defects if (x["buildingCode"], x["floorCode"], x["memberType"]) == key]
        n = Decimal(g["memberCount"])
        affected = q(Decimal(len({x["memberId"] for x in ds})) / n, 4)
        worst = 0
        evidence = []
        for ind in ("crack_width_mm", "spall_m2_per_member", "rebar_exposure_ratio"):
            item = items.get(f'{g["memberType"]}.{ind}')
            if item is None:
                continue
            if ind == "crack_width_mm":
                src = [x for x in ds if x["defectType"] == "CRACK"]
                value = max((d(x["widthMm"]) for x in src), default=Decimal(0))
            elif ind == "spall_m2_per_member":
                src = [x for x in ds if x["defectType"] == "SPALL"]
                value = q(sum((d(x["areaM2"]) for x in src), Decimal(0)) / n, 4)
            else:
                src = [x for x in ds if x["defectType"] == "REBAR_EXPOSURE"]
                value = q(Decimal(len({x["memberId"] for x in src})) / n, 4)
            r, lt = band_rate(item["bands"], value)
            idx = RATINGS.index(r)
            if extent is not None and affected < d(extent["lt"]):
                fidx = RATINGS.index(extent["floor"])
                if idx > fidx:
                    idx = max(idx + int(extent["delta"]), fidx)
            worst = max(worst, idx)
            evidence.append({"indicator": ind, "value": plain(value), "unit": item["unit"],
                             "rating": RATINGS[idx], "threshold": lt,
                             "defectIds": sorted(x["id"] for x in src)})
        rating = RATINGS[worst]
        ratings.append({"buildingCode": g["buildingCode"], "floorCode": g["floorCode"], "memberType": g["memberType"],
                        "memberCount": g["memberCount"], "rating": rating, "score": scores[rating],
                        "weight": weights[g["memberType"]], "affectedRatio": plain(affected), "evidence": evidence})
    # 층
    floors = {}
    for r in ratings:
        floors.setdefault((r["buildingCode"], r["floorCode"]), []).append(r)
    floor_scores = {}
    for k in sorted(floors):
        rs = floors[k]
        wsum = sum((d(r["weight"]) for r in rs), Decimal(0))
        floor_scores[k] = q(sum((d(r["score"]) * d(r["weight"]) for r in rs), Decimal(0)) / wsum, 6)
    # 동
    agg = rules["gradeWeight"]["floorAgg"]
    bldg_scores = {}
    for b in sorted({k[0] for k in floor_scores}):
        fs = [v for k, v in floor_scores.items() if k[0] == b]
        avg = q(sum(fs, Decimal(0)) / Decimal(len(fs)), 6)
        if agg["mode"] == "AVG":
            bldg_scores[b] = avg
        else:
            w = d(agg["worstWeight"])
            bldg_scores[b] = q((Decimal(1) - w) * avg + w * max(fs), 6)
    # 시설물
    areas = {b["code"]: d(b["grossAreaM2"]) for b in snapshot["buildings"]}
    total = sum((areas[b] for b in bldg_scores), Decimal(0))
    facility = q(sum((bldg_scores[b] * areas[b] for b in bldg_scores), Decimal(0)) / total, 3)
    grade = grade_band(rules["gradeBand"]["bands"], facility)
    applied = []
    for fr in rules["gradeBand"]["floorRules"]:
        hit = any(r["memberType"] in fr["memberTypes"] and RATINGS.index(r["rating"]) >= RATINGS.index(fr["rating"])
                  for r in ratings)
        if hit and GRADES.index(fr["minGrade"]) > GRADES.index(grade):
            grade = fr["minGrade"]
            applied.append(fr["id"])
    return {
        "memberRatings": ratings,
        "floorScores": [{"buildingCode": k[0], "floorCode": k[1], "score": plain(v)} for k, v in floor_scores.items()],
        "buildingScores": [{"buildingCode": b, "score": plain(v)} for b, v in bldg_scores.items()],
        "facilityScore": plain(facility),
        "computedGrade": grade,
        "appliedFloorRules": applied,
    }


# ---------------------------------------------------------------------------------------------- 합성 사례
FLOORS = ["B1", "1F", "2F", "3F", "4F", "5F"]
TYPES = ["FOUNDATION", "COLUMN", "BEAM", "SLAB", "WALL"]

# 사례별 손상 프로필: (평균 균열폭 중심, 박리 확률, 철근노출 확률, 결함 밀도)
PROFILES = [
    ("A 수준(경미)", 0.05, 0.00, 0.00, 0.15),
    ("B 수준", 0.14, 0.05, 0.00, 0.30),
    ("B~C 경계", 0.22, 0.10, 0.02, 0.35),
    ("C 수준", 0.27, 0.15, 0.05, 0.45),
    ("C 수준 · 2개 동 면적 가중", 0.25, 0.20, 0.05, 0.40),
    ("D 수준", 0.55, 0.30, 0.15, 0.55),
    ("D 수준 · 철근노출 다수", 0.45, 0.35, 0.30, 0.60),
    ("E 수준(심각)", 1.80, 0.60, 0.50, 1.40),
    ("국부 결함(분포 보정 완화)", 0.35, 0.05, 0.00, 0.04),
    ("주요부재 e → 하한 D", 0.08, 0.02, 0.00, 0.20),
    ("2025.1 규칙 적용(유효기간)", 0.33, 0.10, 0.02, 0.40),
    ("2025/2026 경계일(2026-01-01)", 0.33, 0.10, 0.02, 0.40),
]
AS_OF = ["2026-03-10", "2026-04-02", "2026-05-20", "2026-06-11", "2026-07-07", "2026-08-01", "2026-08-19",
         "2026-09-03", "2026-09-15", "2026-10-01", "2025-11-20", "2026-01-01"]


def make_case(i: int, rng: random.Random) -> dict:
    name, crack, p_spall, p_rebar, density = PROFILES[i]
    nb = 2 if i == 4 else 1
    buildings = []
    groups = []
    defects = []
    seq = 0
    for bi in range(nb):
        code = f"B{bi + 1:02d}"
        buildings.append({"code": code, "name": f"합성 {bi + 1}동",
                          "buildingId": str(uuid.UUID(int=rng.getrandbits(128), version=4)),
                          "grossAreaM2": plain(q(d(rng.uniform(800, 12000)), 2))})
        floors = FLOORS[: rng.randint(3, 6)]
        for fi, fl in enumerate(floors):
            for mt in TYPES:
                if mt == "FOUNDATION" and fi != 0:
                    continue
                if mt == "WALL" and rng.random() < 0.3:
                    continue
                cnt = rng.randint(6, 40)
                groups.append({"buildingCode": code, "floorCode": fl, "memberType": mt, "memberCount": cnt})
                n_def = max(0, int(round(cnt * density * rng.uniform(0.5, 1.5))))
                if i == 8:   # 국부 결함: 한 부재에만 집중
                    n_def = 1 if rng.random() < 0.7 else 0
                for _ in range(n_def):
                    seq += 1
                    member = f"{fl}-{mt[:3]}-{rng.randint(1, cnt):02d}"
                    x0, y0 = rng.uniform(1000, 40000), rng.uniform(1000, 25000)
                    roll = rng.random()
                    base = {"id": f"D-{seq:04d}", "buildingCode": code, "floorCode": fl, "memberType": mt,
                            "memberId": member}
                    if roll < p_rebar:
                        defects.append(base | {"defectType": "REBAR_EXPOSURE",
                                               "lengthM": plain(q(d(rng.uniform(0.1, 1.2)), 2)),
                                               "polyline": [[plain(q(d(x0), 1)), plain(q(d(y0), 1))]]})
                    elif roll < p_rebar + p_spall:
                        defects.append(base | {"defectType": "SPALL",
                                               "areaM2": plain(q(d(rng.uniform(0.02, 1.4)), 3)),
                                               "polyline": [[plain(q(d(x0), 1)), plain(q(d(y0), 1))],
                                                            [plain(q(d(x0 + 400), 1)), plain(q(d(y0 + 300), 1))]]})
                    elif roll < p_rebar + p_spall + 0.08:
                        defects.append(base | {"defectType": "EFFLORESCENCE",
                                               "areaM2": plain(q(d(rng.uniform(0.05, 2.0)), 3)),
                                               "polyline": [[plain(q(d(x0), 1)), plain(q(d(y0), 1))]]})
                    else:
                        w = max(0.02, rng.gauss(crack, crack * 0.35))
                        pts = [[x0, y0]]
                        for _ in range(rng.randint(1, 4)):
                            x0 += rng.uniform(80, 600)
                            y0 += rng.uniform(-300, 300)
                            pts.append([x0, y0])
                        defects.append(base | {"defectType": "CRACK", "widthMm": plain(q(d(w), 2)),
                                               "lengthM": plain(q(d(rng.uniform(0.2, 3.5)), 2)),
                                               "polyline": [[plain(q(d(p[0]), 1)), plain(q(d(p[1]), 1))]
                                                            for p in pts]})
    if i == 9:   # 주요부재(기둥) 하나에 심각 균열 → e
        g = next(x for x in groups if x["memberType"] == "COLUMN")
        for k in range(max(1, g["memberCount"] // 5)):
            seq += 1
            defects.append({"id": f"D-{seq:04d}", "buildingCode": g["buildingCode"], "floorCode": g["floorCode"],
                            "memberType": "COLUMN", "memberId": f"{g['floorCode']}-COL-{k + 1:02d}",
                            "defectType": "CRACK", "widthMm": "1.25", "lengthM": "2.10",
                            "polyline": [["5000.0", "5000.0"], ["5400.0", "5600.0"]]})
    return {"id": f"GOLD-{i + 1:02d}", "description": name, "asOf": AS_OF[i], "facilityClass": "BLD_RC",
            "snapshot": {"schemaVersion": "1", "buildings": buildings, "memberGroups": groups, "defects": defects}}


def rule_for(as_of: str, rule_sets: list[dict]) -> dict:
    for r in rule_sets:
        if r["effectiveFrom"] <= as_of and (r["effectiveTo"] is None or as_of < r["effectiveTo"]):
            return r
    raise ValueError(as_of)


def sql_str(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def seed_sql(rule_sets: list[dict]) -> str:
    out = ["-- =====================================================================",
           "-- V0021 — RPT 상태평가 · 안전등급 규칙 시드 (tools/rpt_golden_gen.py 생성 — 손으로 고치지 말 것)",
           "--   본문 = 골든 픽스처 golden/rules/BLD_RC-*.json (통합 시험이 DB 조립 결과와 픽스처를 대조)",
           "--   ⚠️ 예시 값(approved_by NULL) — 세부지침 상태평가 기준표로 교체. 법령 최신본 확인 필요",
           "-- ====================================================================="]
    kinds = {"COND_RATING": "condRating", "GRADE_WEIGHT": "gradeWeight", "GRADE_BAND": "gradeBand"}
    for r in rule_sets:
        for kind, field in kinds.items():
            rid = uuid.uuid5(uuid.NAMESPACE_URL, f"rpt-rule:{kind}:{r['facilityClass']}:{r['version']}")
            eto = "NULL" if r["effectiveTo"] is None else f"DATE '{r['effectiveTo']}'"
            out.append(f"INSERT INTO rpt.rule_set(id, rule_kind, facility_class, version, effective_from, effective_to, "
                       f"legal_basis, status, created_by) VALUES ('{rid}', '{kind}', '{r['facilityClass']}', "
                       f"'{r['version']}', DATE '{r['effectiveFrom']}', {eto}, {sql_str(r['legalBasis'])}, 'DEPLOYED', "
                       f"'00000000-0000-0000-0000-000000000000');")
            body = r[field]
            if kind == "COND_RATING":
                entries = sorted(body["items"].items()) + [("_extent", body["extent"])]
            else:
                entries = sorted(body.items())
            for key, expr in entries:
                iid = uuid.uuid5(uuid.NAMESPACE_URL, f"rpt-rule-item:{rid}:{key}")
                out.append(f"INSERT INTO rpt.rule_item(id, rule_set_id, item_key, expr) VALUES ('{iid}', '{rid}', "
                           f"'{key}', {sql_str(json.dumps(expr, ensure_ascii=False, sort_keys=True))}::jsonb);")
    return "\n".join(out) + "\n"


def main() -> None:
    rule_sets = [rules_2025(), rules_2026()]
    (GOLDEN / "rules").mkdir(parents=True, exist_ok=True)
    (GOLDEN / "cases").mkdir(parents=True, exist_ok=True)
    for r in rule_sets:
        (GOLDEN / "rules" / f"BLD_RC-{r['version']}.json").write_text(
            json.dumps(r, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    rng = random.Random(20261019)
    dist = {}
    for i in range(len(PROFILES)):
        case = make_case(i, rng)
        rule = rule_for(case["asOf"], rule_sets)
        exp = compute(case["snapshot"], rule)
        exp["ruleVersion"] = rule["version"]
        case["expected"] = exp
        dist[case["id"]] = (exp["computedGrade"], exp["facilityScore"], rule["version"], exp["appliedFloorRules"],
                            len(case["snapshot"]["defects"]))
        (GOLDEN / "cases" / f"{case['id']}.json").write_text(
            json.dumps(case, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    SEED.write_text(seed_sql(rule_sets), encoding="utf-8")
    for k, v in dist.items():
        print(k, v)


if __name__ == "__main__":
    main()
