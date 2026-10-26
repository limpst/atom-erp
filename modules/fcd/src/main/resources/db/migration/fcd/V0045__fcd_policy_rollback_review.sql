-- 보안 검토 2026-10-19 §2 정책 결정(권장안) — FCD-12 · FCD-13
--
-- FCD-12 배치 롤백 4-eyes: FIN(·ADMIN)이 요청(사유 필수) → AUDITOR 가 승인하면 그때 실행, 요청자 ≠ 승인자.
--   배치 상태(import_batch.status)는 실행 전까지 그대로 두고, 요청은 별도 표에 남긴다(배치당 대기 요청 1건).
CREATE TABLE fcd.import_rollback_request (
  id             uuid PRIMARY KEY,
  batch_id       uuid NOT NULL REFERENCES fcd.import_batch(id),
  reason         text NOT NULL CHECK (btrim(reason) <> ''),
  status         varchar(10) NOT NULL CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  requested_by   uuid NOT NULL,
  requested_at   timestamptz NOT NULL DEFAULT now(),
  decided_by     uuid,
  decided_at     timestamptz,
  decision_note  text,
  CONSTRAINT ck_rollback_four_eyes CHECK (decided_by IS NULL OR decided_by <> requested_by),
  CONSTRAINT ck_rollback_decided CHECK ((status = 'PENDING') = (decided_by IS NULL))
);
CREATE UNIQUE INDEX ux_rollback_pending ON fcd.import_rollback_request (batch_id) WHERE status = 'PENDING';

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('AUDITOR', 'fcd:import:rollback-approve', 'ALL');

-- FCD-13 검토 실행: policy 는 enum, 기본값 재정의(AUDITOR)는 사유 · 재정의자를 남긴다
ALTER TABLE fcd.review_run
  ADD COLUMN override_reason text,
  ADD COLUMN overridden_by   uuid,
  ADD CONSTRAINT ck_review_run_policy CHECK (policy IN ('MONTHLY_BUDGET','THRESHOLD')),
  ADD CONSTRAINT ck_review_run_override CHECK ((override_reason IS NULL) = (overridden_by IS NULL));
