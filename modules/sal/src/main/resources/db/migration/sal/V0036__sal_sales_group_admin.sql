-- =====================================================================
-- V0036 — 영업 그룹(SALES_LEAD) 소속 관리 API (웹 프론트엔드가 찾은 "관리 API 없음" 보완)
--   지금까지 sal.sales_group_member 는 직접 SQL 로만 넣을 수 있었다(BR-SAL-006 영업팀장 할인 한도 10%).
--   ADMIN(경영지원) 전용 관리 API 로 등록 · 수정 · 비활성화하고 모든 변경을 core.audit_log 에 남긴다.
--   비활성화는 행을 지우지 않고 active = false (이력 보존) — 할인 한도 해석은 active 행만 본다.
-- =====================================================================
ALTER TABLE sal.sales_group_member
  ADD COLUMN active     boolean NOT NULL DEFAULT true,
  ADD COLUMN note       varchar(200),
  ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN created_by uuid,
  ADD COLUMN updated_at timestamptz,
  ADD COLUMN updated_by uuid,
  ADD COLUMN version    bigint NOT NULL DEFAULT 0;

-- 8.2 매트릭스 밖 SAL 세부 권한 — ADMIN 전용
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ADMIN', 'sal:sales-group:read',  'ALL'),
 ('ADMIN', 'sal:sales-group:write', 'ALL');
