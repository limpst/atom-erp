-- =====================================================================
-- V0035 — 프로젝트 투입 지정 권한 (전체 업무 흐름 시험에서 드러난 연결 누락)
--   계약 서명으로 생긴 프로젝트는 core.project_member 가 0명 → PROJECT 범위 역할(PM · ENG)이 아무 작업도 못 한다.
--   임원 · 경영지원은 전체(ALL)로 책임기술자를 지정하고, 지정된 책임기술자(PM)는 자기 프로젝트(PROJECT)에 참여자를 지정한다.
--   8.2 매트릭스(11개 업무 모듈) 밖의 core 세부 권한.
-- =====================================================================
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC',  'core:project-member:assign', 'ALL'),
 ('ADMIN', 'core:project-member:assign', 'ALL'),
 ('PM',    'core:project-member:assign', 'PROJECT');
