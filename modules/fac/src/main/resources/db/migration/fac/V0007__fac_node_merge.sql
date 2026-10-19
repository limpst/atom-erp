-- =====================================================================
-- V0007 — 위치 계층 결함 참조 읽기 모델 + 병합·계층 권한 (02-IMPL Step 5·7)
-- =====================================================================
-- BR-FAC-010: 결함이 참조하는 member 는 논리삭제 + 대체 member 지정 필수.
-- 결함은 FLD 소유(FLD → FAC 단방향 의존)라 FAC 가 FLD 를 호출할 수 없다 →
-- fld.defect.recorded / fld.defect.relocated 이벤트로 FAC 가 참조 읽기 모델을 유지한다.
CREATE TABLE fac.node_defect_ref (
  node_id    uuid NOT NULL REFERENCES fac.location_node(id),
  defect_id  uuid NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (node_id, defect_id)
);
CREATE INDEX ix_node_defect_ref_defect ON fac.node_defect_ref (defect_id);

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('PM',       'fac:building:merge', 'ALL'),
 ('SYSADMIN', 'fac:building:merge', 'ALL'),
 ('EXEC',     'fac:node:read',  'ALL'), ('SALES', 'fac:node:read', 'ALL'), ('PM', 'fac:node:read', 'ALL'),
 ('ENG',      'fac:node:read',  'ALL'), ('QA', 'fac:node:read', 'ALL'), ('ADMIN', 'fac:node:read', 'ALL'),
 ('SYSADMIN', 'fac:node:read',  'ALL'), ('AUDITOR', 'fac:node:read', 'ALL'),
 ('PM',       'fac:node:write', 'ALL'), ('ENG', 'fac:node:write', 'ALL'), ('SYSADMIN', 'fac:node:write', 'ALL');
