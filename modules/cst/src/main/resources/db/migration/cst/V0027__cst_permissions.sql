-- CST 세부 권한 (설계 08 A2). 8.2 매트릭스 리소스(cst:budget·timesheet·expense·setting)와 겹치지 않는 세분화 리소스
--   cost-real = 실단가(개인 급여 역산 가능) 열람 — ADMIN·EXEC 만 (A2 🔒 콜아웃, TC-CST-11)
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC', 'cst:ledger:read', 'ALL'), ('PM', 'cst:ledger:read', 'PROJECT'), ('ADMIN', 'cst:ledger:read', 'ALL'),
 ('FIN', 'cst:ledger:read', 'ALL'), ('AUDITOR', 'cst:ledger:read', 'ALL'),
 ('EXEC', 'cst:evm:read', 'ALL'), ('PM', 'cst:evm:read', 'PROJECT'), ('ADMIN', 'cst:evm:read', 'ALL'),
 ('FIN', 'cst:evm:read', 'ALL'),
 ('EXEC', 'cst:cost-real:read', 'ALL'), ('ADMIN', 'cst:cost-real:read', 'ALL'),
 ('EXEC', 'cst:progress:write', 'ALL'), ('PM', 'cst:progress:write', 'PROJECT'),
 ('EXEC', 'cst:rate:read', 'ALL'), ('ADMIN', 'cst:rate:read', 'ALL'), ('FIN', 'cst:rate:read', 'ALL'),
 ('SYSADMIN', 'cst:rate:read', 'ALL'),
 ('ADMIN', 'cst:rate:write', 'ALL'),
 ('EXEC', 'cst:period:read', 'ALL'), ('ADMIN', 'cst:period:read', 'ALL'), ('FIN', 'cst:period:read', 'ALL'),
 ('AUDITOR', 'cst:period:read', 'ALL'),
 ('ADMIN', 'cst:period:close', 'ALL'),
 ('SYSADMIN', 'cst:period:reopen', 'ALL');
