"""설계 00 v0.3 §8.2 역할-모듈 권한 매트릭스(CSV, 표 원문 셀) → core.role_permission 시드 SQL 생성.

사용: python tools/gen_role_permission_seed.py
입력: apps/erp-server/src/test/resources/permission-matrix-8.2.csv
출력: modules/core/src/main/resources/db/migration/core/V0002__core_role_permission_matrix.sql

해석 규칙(Java PermissionMatrixTest 와 독립 구현 — 두 구현의 결과가 같아야 테스트 통과):
  - 토큰: [CRUDA]+ (괄호 한정어)? ◐?   C=create R=read U=update D=delete A=approve
  - 리소스: 모듈 기본 리소스, 한정어가 있으면 한정어 리소스(QUALIFIER_RESOURCE)
  - 범위: 셀에 ◐ 가 있으면 CUSTOMER→CLIENT, 본인·시간·경비·라벨 과제→SELF, 그 외 PROJECT
          ◐ 가 없으면 '본인'→SELF, 그 외 ALL
  - SYSADMIN 특수 셀: 설정→{mod}:setting:update, 템플릿→rpt:template:update,
          체크리스트→qar:checklist:update, 모델 배포→aip:model:deploy (모두 ALL)
"""
import csv
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "apps/erp-server/src/test/resources/permission-matrix-8.2.csv"
OUT = ROOT / "modules/core/src/main/resources/db/migration/core/V0002__core_role_permission_matrix.sql"

DEFAULT_RESOURCE = {
    "SAL": "opportunity", "FAC": "facility", "HRQ": "engineer", "EQP": "equipment", "FLD": "defect",
    "RPT": "report", "QAR": "review", "CST": "budget", "PTL": "share", "DEM": "supervision-log",
    "AIP": "inference-job",
}
QUALIFIER_RESOURCE = {
    "가용인력": "availability", "본인": None, "대여": "checkout", "계약": "contract", "의견": "comment",
    "시간": "timesheet", "경비": "expense", "견적": "estimate", "공유분": None, "라벨 과제": "label-task",
    "청구": "invoice", "감사로그": "audit-log",
}
SELF_QUALIFIERS = {"본인", "시간", "경비", "라벨 과제"}
ACTIONS = {"C": "create", "R": "read", "U": "update", "D": "delete", "A": "approve"}
SYSADMIN_SPECIAL = {"템플릿": ("template", "update"), "체크리스트": ("checklist", "update"),
                    "모델 배포": ("model", "deploy")}
TOKEN = re.compile(r"([CRUDA]+)(?:\(([^)]*)\))?(◐)?")


def grants(role: str, module: str, cell: str):
    cell = cell.strip()
    mod = module.lower()
    if cell == "-":
        return set()
    if cell == "설정":
        return {(f"{mod}:setting:update", "ALL")}
    if cell in SYSADMIN_SPECIAL:
        res, act = SYSADMIN_SPECIAL[cell]
        return {(f"{mod}:{res}:{act}", "ALL")}
    scoped = "◐" in cell
    out = set()
    for letters, qual, _ in TOKEN.findall(cell):
        qual = qual or None
        res = QUALIFIER_RESOURCE.get(qual) if qual else None
        res = res or DEFAULT_RESOURCE[module]
        if scoped:
            scope = "CLIENT" if role == "CUSTOMER" else ("SELF" if qual in SELF_QUALIFIERS else "PROJECT")
        else:
            scope = "SELF" if qual == "본인" else "ALL"
        for ch in letters:
            out.add((f"{mod}:{res}:{ACTIONS[ch]}", scope))
    if not out:
        raise ValueError(f"해석 불가 셀: {role}/{module}: {cell!r}")
    return out


def main():
    rows = []
    with SRC.open(encoding="utf-8") as f:
        for r in csv.DictReader(f):
            role = r.pop("role")
            for module, cell in r.items():
                for perm, scope in sorted(grants(role, module, cell)):
                    rows.append((role, perm, scope))
    lines = [
        "-- =====================================================================",
        "-- V0002 — 설계 00 v0.3 §8.2 역할-모듈 권한 매트릭스 시드 (12역할 × 11모듈 = 132셀)",
        "-- 생성기: tools/gen_role_permission_seed.py (직접 수정 금지, CSV 수정 후 재생성)",
        "-- 원본:   apps/erp-server/src/test/resources/permission-matrix-8.2.csv",
        "-- =====================================================================",
        "INSERT INTO core.role_permission(role, permission, scope) VALUES",
    ]
    lines += [f" ('{r}', '{p}', '{s}')" + ("," if i < len(rows) - 1 else ";") for i, (r, p, s) in enumerate(rows)]
    OUT.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"{len(rows)} grants -> {OUT}")


if __name__ == "__main__":
    main()
