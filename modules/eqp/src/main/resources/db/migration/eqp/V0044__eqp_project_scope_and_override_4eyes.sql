-- =====================================================================
-- V0044 — 보안 검토 2026-10-19 정책 결정(권장안 적용)
--   EQP-01: 예약 · 반출은 PROJECT 범위 — 신청자 · 반출자는 그 프로젝트 투입 인원(core.project_member),
--           예약 승인자는 그 프로젝트에 투입된 PM(또는 ALL 범위 역할 ADMIN), 승인자 ≠ 신청자(서비스 검사).
--           eqp:checkout:create(ENG) 는 설계 8.2 표 셀이 바뀜 → permission-policy/EQP-01.csv
--   EQP-04: QA 검교정 예외(Override)는 요청자(requested_by)와 승인자(approved_by)가 다른 사람(4-eyes),
--           projectId 필수, 소급 시작 금지, 장비 · 프로젝트별 활성 1건(서비스 검사, 장비 행 잠금으로 직렬화).
-- =====================================================================
UPDATE core.role_permission SET scope = 'PROJECT'
 WHERE (role, permission) IN (('PM', 'eqp:reservation:create'), ('ENG', 'eqp:reservation:create'),
                              ('PM', 'eqp:reservation:approve'), ('ENG', 'eqp:checkout:create'));

-- 기존 행(이 마이그레이션 전)은 요청자 기록이 없다 → NULL 허용, 새 행은 서비스가 반드시 채운다.
ALTER TABLE eqp.calibration_override ADD COLUMN requested_by uuid;
ALTER TABLE eqp.calibration_override ADD CONSTRAINT ck_override_4eyes
  CHECK (requested_by IS NULL OR requested_by <> approved_by);
COMMENT ON COLUMN eqp.calibration_override.requested_by IS 'EQP-04 예외 요청자(현장 PM · ENG) — 승인자(QA)와 달라야 함';
