-- =====================================================================
-- V0040 — 실행예산 상신 · 결재 분리(maker-checker) · SYSADMIN 회계기간 조회 (보안 검토 2026-10-19 §2 CST-01, 웹 3차 점검)
--   CST-01: 결재는 SUBMITTED 판만, 결재자 ≠ 작성(created_by) · 마지막 수정(updated_by) · 상신(submitted_by) 자.
--           상신은 POST …/budget-revisions/{id}:submit (cst:budget:update — PM 은 투입 프로젝트, ADMIN · FIN 전체).
--   SYSADMIN 은 cst:period:reopen 만 있고 cst:period:read 가 없어 /cst/periods 목록이 비었다 → 조회(ALL) 부여.
-- =====================================================================
ALTER TABLE cst.budget_revision
  ADD COLUMN submitted_by uuid,
  ADD COLUMN submitted_at timestamptz;

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('SYSADMIN', 'cst:period:read', 'ALL');
