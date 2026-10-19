-- =====================================================================
-- V0029 — dem 시드 (10-IMPL Step 3 V10_009 · Step 4 V10_010) + DEM 세부 권한
--   ⚠️ 법령 최신본 확인 필요: rule_param 값 · 점검 항목은 자리표시(예시) — approved_by NULL 인 채 사용,
--      운영 전환 체크리스트에서 현행 법령 · 감리 책임기술자 검수 후 교체 · 서명.
-- =====================================================================
INSERT INTO dem.rule_param(rule_key, value_json, effective_from, legal_basis) VALUES
 ('MAX_CONCURRENT_SUPERVISION',     '{"chief": 3}',                                         '2026-01-01', '⚠️ 예시 — 건축물관리법 시행규칙 해체공사감리 관련 조항 확인 필요'),
 ('SUPERVISOR_QUALIFICATION_CODES', '{"any": ["ARCH_STRUCT_PE", "ARCH_ENG", "SAFETY_DIAG_CERT"]}', '2026-01-01', '⚠️ 예시 — 해체공사감리자 자격 요건 확인 필요(HRQ credential_type 코드)'),
 ('LOG_LOCK_TIME',                  '{"time": "23:59", "tz": "Asia/Seoul"}',                 '2026-01-01', '내부 규정 BR-DEM-006'),
 ('CRITICAL_ALERT_SLA_MIN',         '{"minutes": 30}',                                      '2026-01-01', '내부 규정 BR-DEM-007'),
 ('CO_DUE_HOURS',                   '{"LOW": 72, "MEDIUM": 48, "HIGH": 24, "CRITICAL": 4}', '2026-01-01', '내부 규정(10-IMPL Step 8) ⚠️ 예시'),
 ('ADJ_MATCH_DISTANCE_M',           '{"m": 0.5}',                                           '2026-01-01', '내부 기준 B7.2'),
 ('ADJ_CRACK_WIDTH_GROWTH_MM',      '{"mm": 0.1}',                                          '2026-01-01', '내부 기준 B7.2'),
 ('ADJ_CRACK_LENGTH_GROWTH_PCT',    '{"pct": 20}',                                          '2026-01-01', '내부 기준 B7.2');

-- 공정 5종 점검표 v1 (항목은 예시 — 감리 책임기술자 검수 후 확정) ⚠️ 법령 최신본 확인 필요
INSERT INTO dem.checklist_template(id, phase_code, version_no, effective_from, legal_basis) VALUES
 ('0192d000-0000-7000-8000-000000000101', 'TEMPORARY',        1, '2026-01-01', '⚠️ 예시 — 해체공사 감리업무 점검 서식 확인 필요'),
 ('0192d000-0000-7000-8000-000000000102', 'ASBESTOS_CHECK',   1, '2026-01-01', '⚠️ 예시 — 석면 해체 · 제거 확인 서식 확인 필요'),
 ('0192d000-0000-7000-8000-000000000103', 'UPPER_DEMOLITION', 1, '2026-01-01', '⚠️ 예시 — 해체공사 감리업무 점검 서식 확인 필요'),
 ('0192d000-0000-7000-8000-000000000104', 'LOWER_DEMOLITION', 1, '2026-01-01', '⚠️ 예시 — 해체공사 감리업무 점검 서식 확인 필요'),
 ('0192d000-0000-7000-8000-000000000105', 'DEBRIS_OUT',       1, '2026-01-01', '⚠️ 예시 — 폐기물 반출 관련 서식 확인 필요');

INSERT INTO dem.checklist_template_item(id, template_id, seq, title, photo_required, default_severity) VALUES
 ('0192d000-0000-7000-8000-000000001011', '0192d000-0000-7000-8000-000000000101', 1, '비계 · 작업발판 설치 상태',            true,  'HIGH'),
 ('0192d000-0000-7000-8000-000000001012', '0192d000-0000-7000-8000-000000000101', 2, '가설 울타리 · 방음벽 설치',            false, 'MEDIUM'),
 ('0192d000-0000-7000-8000-000000001013', '0192d000-0000-7000-8000-000000000101', 3, '잭서포트 설치 간격 · 수직도',          true,  'CRITICAL'),
 ('0192d000-0000-7000-8000-000000001021', '0192d000-0000-7000-8000-000000000102', 1, '석면 제거 완료 확인서 대조',           false, 'HIGH'),
 ('0192d000-0000-7000-8000-000000001022', '0192d000-0000-7000-8000-000000000102', 2, '잔재 석면 육안 확인',                  true,  'CRITICAL'),
 ('0192d000-0000-7000-8000-000000001031', '0192d000-0000-7000-8000-000000000103', 1, '안전난간 · 추락방지망 설치 상태',       true,  'HIGH'),
 ('0192d000-0000-7000-8000-000000001032', '0192d000-0000-7000-8000-000000000103', 2, '살수 · 비산먼지 방지 설비 가동',       true,  'MEDIUM'),
 ('0192d000-0000-7000-8000-000000001033', '0192d000-0000-7000-8000-000000000103', 3, '잔재물 적재 높이 및 하부 슬래브 보강', true,  'CRITICAL'),
 ('0192d000-0000-7000-8000-000000001034', '0192d000-0000-7000-8000-000000000103', 4, '잭서포트 설치 간격 · 수직도',          true,  'CRITICAL'),
 ('0192d000-0000-7000-8000-000000001035', '0192d000-0000-7000-8000-000000000103', 5, '해체 순서가 해체계획서와 일치',        false, 'HIGH'),
 ('0192d000-0000-7000-8000-000000001036', '0192d000-0000-7000-8000-000000000103', 6, '장비 작업반경 내 출입 통제',           true,  'HIGH'),
 ('0192d000-0000-7000-8000-000000001037', '0192d000-0000-7000-8000-000000000103', 7, '인접건축물 보호조치',                  true,  'HIGH'),
 ('0192d000-0000-7000-8000-000000001038', '0192d000-0000-7000-8000-000000000103', 8, '소음 · 진동 관리',                     false, 'MEDIUM'),
 ('0192d000-0000-7000-8000-000000001041', '0192d000-0000-7000-8000-000000000104', 1, '지하층 해체 순서 · 흙막이 안정',       true,  'CRITICAL'),
 ('0192d000-0000-7000-8000-000000001042', '0192d000-0000-7000-8000-000000000104', 2, '인접건축물 계측(기울기 · 균열) 확인',  true,  'HIGH'),
 ('0192d000-0000-7000-8000-000000001051', '0192d000-0000-7000-8000-000000000105', 1, '반출 차량 적재 · 덮개 상태',           true,  'MEDIUM'),
 ('0192d000-0000-7000-8000-000000001052', '0192d000-0000-7000-8000-000000000105', 2, '세륜 시설 가동',                       false, 'LOW');

-- DEM 세부 권한 — 8.2 표 셀(dem:supervision-log · dem:setting)은 V0002 에서 이미 부여(감리일지 작성 = supervision-log).
-- 표 셀 검사(PermissionMatrixTest)가 supervision-log 리소스를 셀과 정확히 비교하므로 그 외 기능은 별도 리소스로 둔다.
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('PM', 'dem:project:create', 'ALL'), ('SALES', 'dem:project:create', 'ALL'),
 ('EXEC', 'dem:project:read', 'ALL'), ('SALES', 'dem:project:read', 'ALL'), ('PM', 'dem:project:read', 'ALL'),
 ('ENG', 'dem:project:read', 'ALL'), ('QA', 'dem:project:read', 'ALL'), ('ADMIN', 'dem:project:read', 'ALL'),
 ('PM', 'dem:project:transition', 'ALL'),
 ('PM', 'dem:supervisor:assign', 'ALL'),
 ('EXEC', 'dem:supervisor:read', 'ALL'), ('PM', 'dem:supervisor:read', 'ALL'), ('ENG', 'dem:supervisor:read', 'ALL'),
 ('QA', 'dem:supervisor:read', 'ALL'), ('ADMIN', 'dem:supervisor:read', 'ALL'),
 ('PM', 'dem:checklist:read', 'ALL'), ('ENG', 'dem:checklist:read', 'ALL'), ('QA', 'dem:checklist:read', 'ALL'),
 ('ADMIN', 'dem:checklist:read', 'ALL'),
 ('PM', 'dem:log-amendment:create', 'ALL'),
 ('ADMIN', 'dem:log-lock:run', 'ALL'), ('SYSADMIN', 'dem:log-lock:run', 'ALL'),
 ('ENG', 'dem:evidence:register', 'ALL'), ('PM', 'dem:evidence:register', 'ALL'),
 ('PM', 'dem:evidence:verify', 'ALL'), ('QA', 'dem:evidence:verify', 'ALL'),
 ('PM', 'dem:revision:write', 'ALL'), ('ENG', 'dem:revision:write', 'ALL'),
 ('PM', 'dem:revision:approve', 'ALL'),
 ('EXEC', 'dem:revision:read', 'ALL'), ('PM', 'dem:revision:read', 'ALL'), ('ENG', 'dem:revision:read', 'ALL'),
 ('QA', 'dem:revision:read', 'ALL'),
 ('PM', 'dem:transmittal:issue', 'ALL'),
 ('QA', 'dem:comment:write', 'ALL'), ('PM', 'dem:comment:write', 'ALL'), ('ENG', 'dem:comment:write', 'ALL'),
 ('PM', 'dem:submission:write', 'ALL'), ('ADMIN', 'dem:submission:write', 'ALL'),
 ('PM', 'dem:survey:write', 'ALL'), ('ENG', 'dem:survey:write', 'ALL'),
 ('EXEC', 'dem:survey:read', 'ALL'), ('PM', 'dem:survey:read', 'ALL'), ('ENG', 'dem:survey:read', 'ALL'),
 ('QA', 'dem:survey:read', 'ALL');
