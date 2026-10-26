-- =====================================================================
-- V0043 — 보안 검토 SAL-06: 할인 한도는 발행자가 아니라 작성자(created_by) 기준
--   견적 작성 시점의 작성자 역할 · 그룹 한도(EXEC 승인 권한은 제외 — 승인자 ≠ 작성자)를 기록한다.
--   :issue 는 이 값으로 PENDING_APPROVAL 여부를 정하고, 할인 승인자 · 계약 결재자는 작성자와 달라야 한다.
--   이 마이그레이션 전에 만든 견적(NULL)은 한도 0 으로 본다(할인이 있으면 승인 필요 — 실패하는 쪽으로 닫힘).
-- =====================================================================
ALTER TABLE sal.estimate ADD COLUMN author_discount_limit numeric(6,4)
  CHECK (author_discount_limit IS NULL OR (author_discount_limit >= 0 AND author_discount_limit < 0.5));
COMMENT ON COLUMN sal.estimate.author_discount_limit IS 'SAL-06 작성자 할인 한도(작성 시점, EXEC 승인 권한 제외)';
