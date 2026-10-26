-- =====================================================================
-- V0047 — 보안 검토 2026-10-19 DEM-02(정책 결정 · 권장안): DEM 세부 권한(V0029)의 PM · ENG 범위 ALL → PROJECT.
--   이전에는 아무 PM 이 다른 프로젝트의 전이 · 종료 · 감리자 지정 · 일지 정정 · 리비전 승인을 할 수 있었다.
--   DEM 경로에는 대부분 projectId 가 없어 서비스가 행 → 업무 패키지 → 프로젝트를 찾은 뒤 DemAccess 로 범위를 본다
--   (투입 인원, 감리 관련 권한은 해당 패키지 활성 감리자도 허용). EXEC · QA · ADMIN · SALES 는 ALL 유지.
--   dem:checklist:read(공정 점검표 템플릿 — 프로젝트 없는 기준 정보)는 ALL 유지.
--   8.2 표 셀(dem:supervision-log:* · dem:setting)은 이미 PROJECT 라 바꾸지 않는다(PermissionMatrixTest 셀 변화 없음).
-- DEM-03(b): 감리자(ENG)가 자기 일지 정정 요청을 올릴 수 있도록 dem:log-amendment:create 를 ENG 에 PROJECT 로 부여
--   (본인 일지 정정은 즉시 적용되지 않고 PENDING 요청 → 다른 사람(dem:supervision-log:approve)이 승인).
-- =====================================================================
UPDATE core.role_permission SET scope = 'PROJECT'
 WHERE role = 'PM' AND scope = 'ALL' AND permission IN (
       'dem:project:create', 'dem:project:read', 'dem:project:transition',
       'dem:supervisor:assign', 'dem:supervisor:read',
       'dem:log-amendment:create',
       'dem:evidence:register', 'dem:evidence:verify',
       'dem:revision:write', 'dem:revision:approve', 'dem:revision:read',
       'dem:transmittal:issue', 'dem:comment:write', 'dem:submission:write',
       'dem:survey:write', 'dem:survey:read');

UPDATE core.role_permission SET scope = 'PROJECT'
 WHERE role = 'ENG' AND scope = 'ALL' AND permission IN (
       'dem:project:read', 'dem:supervisor:read',
       'dem:evidence:register',
       'dem:revision:write', 'dem:revision:read', 'dem:comment:write',
       'dem:survey:write', 'dem:survey:read');

INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ENG', 'dem:log-amendment:create', 'PROJECT')
ON CONFLICT (role, permission) DO UPDATE SET scope = EXCLUDED.scope;
