-- =====================================================================
-- V0018 — FLD 세부 권한 (설계 05 A2). 8.2 표 셀(fld:defect · fld:setting)은 V0002 에서 이미 부여:
--   동기화 push = fld:defect:update(PM · ENG ◐ 프로젝트), pull · 조회 = fld:defect:read.
-- 표 셀 검사(PermissionMatrixTest)가 defect 리소스를 셀과 정확히 비교하므로 그 외 기능은 별도 리소스로 둔다.
-- =====================================================================
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('PM', 'fld:round:create', 'PROJECT'), ('ADMIN', 'fld:round:create', 'ALL'),
 ('PM', 'fld:round:read', 'PROJECT'), ('ENG', 'fld:round:read', 'PROJECT'), ('QA', 'fld:round:read', 'ALL'),
 ('EXEC', 'fld:round:read', 'ALL'), ('ADMIN', 'fld:round:read', 'ALL'), ('AUDITOR', 'fld:round:read', 'ALL'),
 ('PM', 'fld:zone:assign', 'PROJECT'),
 ('PM', 'fld:zone:lock', 'PROJECT'),
 ('PM', 'fld:conflict:resolve', 'PROJECT'), ('ENG', 'fld:conflict:resolve', 'PROJECT'),
 ('PM', 'fld:photo:upload', 'PROJECT'), ('ENG', 'fld:photo:upload', 'PROJECT'),
 ('PM', 'fld:photo:verify', 'PROJECT'), ('QA', 'fld:photo:verify', 'ALL'), ('AUDITOR', 'fld:photo:verify', 'ALL'),
 ('PM', 'fld:ai-suggestion:request', 'PROJECT'), ('ENG', 'fld:ai-suggestion:request', 'PROJECT'),
 ('ADMIN', 'fld:ai-suggestion:attach', 'ALL'), ('SYSADMIN', 'fld:ai-suggestion:attach', 'ALL'),
 ('SYSADMIN', 'fld:projection:rebuild', 'ALL');
