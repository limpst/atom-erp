-- =====================================================================
-- V0039 — 타임시트 승인 · 월 마감 재오픈 정책 (보안 검토 2026-10-19 §2 HRQ-01 · HRQ-07 권장안)
--   HRQ-01: 승인은 그 프로젝트의 책임기술자(core.project_member RESP, 오늘 투입 중)만, 본인 것은 누구도 승인 불가.
--           EXEC · ADMIN 은 사유(5자 이상)를 남기는 대리 승인(DELEGATED)만 — approval_mode · delegation_reason 에 기록.
--           PM 의 hrq:timesheet:approve 는 ALL 그대로 두고 RESP 여부는 서비스가 확인한다(엔드포인트에 projectId 없음).
--   HRQ-07: 월 마감 재오픈을 hrq:month-close:reopen(SYSADMIN)으로 분리 — CST(cst:period:reopen)와 같은 구조.
--           월 마감 이력 조회는 hrq:month-close:read(EXEC · PM · ADMIN · SYSADMIN) — 재오픈하는 SYSADMIN 도 목록을 본다.
-- =====================================================================
ALTER TABLE hrq.timesheet_entry
  ADD COLUMN approval_mode     varchar(10) CHECK (approval_mode IN ('RESP', 'DELEGATED')),
  ADD COLUMN delegation_reason text;

ALTER TABLE hrq.timesheet_entry ADD CONSTRAINT ck_ts_delegation_reason
  CHECK (approval_mode IS DISTINCT FROM 'DELEGATED' OR length(btrim(delegation_reason)) >= 5);

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC',     'hrq:timesheet:approve',  'ALL'),
 ('ADMIN',    'hrq:timesheet:approve',  'ALL'),
 ('SYSADMIN', 'hrq:month-close:reopen', 'ALL'),
 ('EXEC',     'hrq:month-close:read',   'ALL'),
 ('PM',       'hrq:month-close:read',   'ALL'),
 ('ADMIN',    'hrq:month-close:read',   'ALL'),
 ('SYSADMIN', 'hrq:month-close:read',   'ALL');
