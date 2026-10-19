-- =====================================================================
-- V0037 — QA 검토자 프로필 관리 API (웹 프론트엔드가 찾은 "관리 API 없음" 보완)
--   qar.reviewer_profile 은 자동 배정 후보 · 수동 배정 자격(SEC-QAR-02)의 기준인데 직접 SQL 로만 넣을 수 있었다.
--   설계 07 은 프로필 관리 주체를 정하지 않았고(전환 체크리스트 "QA 인원 · 전문분야 프로필 입력"만) QA 팀장 역할 코드도
--   없으므로 ADMIN(경영지원) 전용으로 둔다. 자격(qualifications)은 표시 · 감사용 메모이며 배정 판정은 기존대로
--   active · step_code · max_open · expertise 를 쓴다. 비활성화는 active = false(이력 보존).
-- =====================================================================
ALTER TABLE qar.reviewer_profile
  ADD COLUMN qualifications text[] NOT NULL DEFAULT '{}',
  ADD COLUMN note           varchar(200),
  ADD COLUMN created_by     uuid,
  ADD COLUMN updated_at     timestamptz,
  ADD COLUMN updated_by     uuid,
  ADD COLUMN version        bigint NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_reviewer_step CHECK (step_code IN ('QA_REVIEW'));

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ADMIN', 'qar:reviewer-profile:read',  'ALL'),
 ('ADMIN', 'qar:reviewer-profile:write', 'ALL');
