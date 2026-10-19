-- =====================================================================
-- V0038 — 기준 요율(표준 노임 단가 등) 승인 절차 (설계 08 A2 "노임단가 · 제경비율 · 환율 기준 관리":
--   ADMIN = 관리, EXEC = 승인 / 08-IMPL 위험표 "rate_table DRAFT 장기 체류")
--   ADMIN 이 DRAFT 로 등록 · 수정 → EXEC 가 승인(등록자 ≠ 승인자, 4-eyes)해야 APPROVED 로 원장 · 예산에 쓰인다.
--   기간 겹침 금지(EXCLUDE)는 APPROVED 행에만 적용 — 초안은 승인 때 직전 무기한 행을 마감하고 겹침을 다시 검사한다.
--   기존 행(시험 · 이전 등록분)은 APPROVED 로 간주(기본값). 요율 값은 마이그레이션에 넣지 않는다(ADMIN 입력만).
-- =====================================================================
ALTER TABLE cst.rate_table
  ADD COLUMN status        varchar(10) NOT NULL DEFAULT 'APPROVED'
                           CHECK (status IN ('DRAFT', 'APPROVED', 'REJECTED')),
  ADD COLUMN approved_by   uuid,
  ADD COLUMN approved_at   timestamptz,
  ADD COLUMN reject_reason text,
  ADD COLUMN retired_at    timestamptz,
  ADD COLUMN retired_by    uuid;

ALTER TABLE cst.rate_table DROP CONSTRAINT rate_table_no_overlap;
ALTER TABLE cst.rate_table ADD CONSTRAINT rate_table_no_overlap EXCLUDE USING gist
  (rate_kind WITH =, key_code WITH =, daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[)') WITH &&)
  WHERE (status = 'APPROVED');

-- 같은 (종류, 키) 초안은 하나만
CREATE UNIQUE INDEX ux_rate_one_draft ON cst.rate_table(rate_kind, key_code) WHERE status = 'DRAFT';

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC', 'cst:rate:approve', 'ALL');
