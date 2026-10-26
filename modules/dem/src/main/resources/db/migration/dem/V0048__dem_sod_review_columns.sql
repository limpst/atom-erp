-- =====================================================================
-- V0048 — 보안 검토 2026-10-19 DEM-03(정책 결정 · 권장안) 직무 분리 근거 컬럼 · 표
--   (c) 리비전 검토 요청자 · 파일 교체자 기록 → 승인자 ≠ 작성자 · 검토 요청자 · 파일 교체자
--   (b) 감리일지 정정 요청(PENDING) 결정 — log_amendment 는 append-only(트리거)라 결정은 별도 append-only 표에 남긴다.
--       유효 상태: APPLIED 행 · 또는 PENDING 행 + 결정 APPROVED. 결정자 ≠ 요청자(amended_by) ≠ 일지 작성자.
-- =====================================================================
ALTER TABLE dem.document_revision
  ADD COLUMN review_requested_by uuid,
  ADD COLUMN review_requested_at timestamptz,
  ADD COLUMN file_replaced_by    uuid;

CREATE TABLE dem.log_amendment_decision (
  amendment_id uuid PRIMARY KEY REFERENCES dem.log_amendment(id),
  decision     varchar(10) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
  note         text,
  decided_by   uuid NOT NULL,
  decided_at   timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER trg_amendment_decision_append_only BEFORE UPDATE OR DELETE ON dem.log_amendment_decision
  FOR EACH ROW EXECUTE FUNCTION dem.forbid_mutation();
