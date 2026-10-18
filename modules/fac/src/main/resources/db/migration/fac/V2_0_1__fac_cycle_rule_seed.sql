-- =====================================================================
-- V2_0_1 — 점검주기 규칙 시드 ⚠️ 예시 값 — 법령 최신본 확인 필요 (QA 검토·서명 전 운영 사용 금지)
--   근거 후보: 시설물의 안전 및 유지관리에 관한 특별법 시행령 별표 3 (안전점검·진단 실시 시기)
--   approved_by 가 NULL 인 규칙 = 미승인 예시. 운영 전환 시 QA 확인 후 갱신/대체.
--   substitutes: 상위 점검 수행이 하위 점검을 갈음 (BR-FAC-007)
-- =====================================================================
INSERT INTO fac.inspection_cycle_rule
  (id, rule_code, law_code, facility_class, grade, inspection_type, cycle_months,
   first_due_basis, first_due_months, substitutes, effective_from, legal_reference)
SELECT core.uuid_generate_v7(), 'SITBEOP-' || c.cls || '-' || v.rule_code || '-v2018', 'SITBEOP', c.cls, v.grade, v.type, v.months,
       v.first_basis, v.first_months, v.subs::varchar(20)[], DATE '2018-01-18',
       '⚠️ 예시: 시특법 시행령 별표3 (' || v.note || ') — 법령 최신본 확인 필요'
FROM (VALUES ('CLASS1'), ('CLASS2'), ('CLASS3')) AS c(cls)
CROSS JOIN (VALUES
  -- 정기안전점검: A·B·C 반기 1회, D·E 연 3회(4개월)
  ('REG-ABC', NULL::char(1), 'REGULAR', 6,  NULL, NULL::smallint, '{}', '정기 A~C 반기'),
  ('REG-D',   'D',           'REGULAR', 4,  NULL, NULL,           '{}', '정기 D 연3회'),
  ('REG-E',   'E',           'REGULAR', 4,  NULL, NULL,           '{}', '정기 E 연3회'),
  -- 정밀안전점검(건축물): A 4년, B·C 3년, D·E 2년
  ('DET-A',   'A',           'DETAILED', 48, NULL, NULL, '{REGULAR}', '정밀점검 A 4년'),
  ('DET-B',   'B',           'DETAILED', 36, NULL, NULL, '{REGULAR}', '정밀점검 B 3년'),
  ('DET-C',   'C',           'DETAILED', 36, NULL, NULL, '{REGULAR}', '정밀점검 C 3년'),
  ('DET-D',   'D',           'DETAILED', 24, NULL, NULL, '{REGULAR}', '정밀점검 D 2년'),
  ('DET-E',   'E',           'DETAILED', 24, NULL, NULL, '{REGULAR}', '정밀점검 E 2년'),
  ('DET-NG',  NULL,          'DETAILED', 36, 'APPROVAL_DATE', 36, '{REGULAR}', '정밀점검 등급 미정 최초'),
  -- 정밀안전진단: A 6년, B·C 5년, D·E 4년, 최초는 사용승인 후 10년(1종)
  ('PD-A',    'A',           'PRECISION_DIAG', 72, NULL, NULL, '{DETAILED,REGULAR}', '정밀진단 A 6년'),
  ('PD-B',    'B',           'PRECISION_DIAG', 60, NULL, NULL, '{DETAILED,REGULAR}', '정밀진단 B 5년'),
  ('PD-C',    'C',           'PRECISION_DIAG', 60, NULL, NULL, '{DETAILED,REGULAR}', '정밀진단 C 5년'),
  ('PD-D',    'D',           'PRECISION_DIAG', 48, NULL, NULL, '{DETAILED,REGULAR}', '정밀진단 D 4년'),
  ('PD-E',    'E',           'PRECISION_DIAG', 48, NULL, NULL, '{DETAILED,REGULAR}', '정밀진단 E 4년'),
  ('PD-NG',   NULL,          'PRECISION_DIAG', 60, 'APPROVAL_DATE', 120, '{DETAILED,REGULAR}', '정밀진단 최초 준공 10년')
) AS v(rule_code, grade, type, months, first_basis, first_months, subs, note)
-- 정밀안전진단은 1종만 의무 (⚠️ 예시)
WHERE NOT (v.type = 'PRECISION_DIAG' AND c.cls <> 'CLASS1')
ORDER BY c.cls, v.rule_code;

-- 건축물관리법 정기점검 (⚠️ 예시: 사용승인 후 10년부터 3년마다)
INSERT INTO fac.inspection_cycle_rule
  (id, rule_code, law_code, facility_class, grade, inspection_type, cycle_months,
   first_due_basis, first_due_months, effective_from, legal_reference)
VALUES (core.uuid_generate_v7(), 'BMA-BMA-CHK-v2020', 'BMA', 'BMA', NULL, 'BMA_CHECK', 36,
        'APPROVAL_DATE', 120, DATE '2020-05-01', '⚠️ 예시: 건축물관리법 시행령 제23조 — 법령 최신본 확인 필요');

-- FAC 세부 권한 (설계 02 A2). 리소스명(building·grade·due·classification)은 8.2 매트릭스(fac:facility)와 별개 세분화
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('SALES',    'fac:building:create', 'ALL'), ('PM', 'fac:building:create', 'ALL'), ('SYSADMIN', 'fac:building:create', 'ALL'),
 ('EXEC',     'fac:building:read',   'ALL'), ('SALES', 'fac:building:read', 'ALL'), ('PM', 'fac:building:read', 'ALL'),
 ('ENG',      'fac:building:read',   'ALL'), ('QA', 'fac:building:read', 'ALL'), ('ADMIN', 'fac:building:read', 'ALL'),
 ('SYSADMIN', 'fac:building:read',   'ALL'), ('AUDITOR', 'fac:building:read', 'ALL'),
 ('SALES',    'fac:building:update', 'ALL'), ('PM', 'fac:building:update', 'ALL'), ('ADMIN', 'fac:building:update', 'ALL'),
 ('SYSADMIN', 'fac:building:update', 'ALL'),
 ('SYSADMIN', 'fac:building:delete', 'ALL'),
 ('PM',       'fac:classification:confirm', 'ALL'),
 ('EXEC',     'fac:grade:read', 'ALL'), ('SALES', 'fac:grade:read', 'ALL'), ('PM', 'fac:grade:read', 'ALL'),
 ('ENG',      'fac:grade:read', 'ALL'), ('QA', 'fac:grade:read', 'ALL'), ('ADMIN', 'fac:grade:read', 'ALL'),
 ('AUDITOR',  'fac:grade:read', 'ALL'),
 ('EXEC',     'fac:due:read', 'ALL'), ('SALES', 'fac:due:read', 'ALL'), ('PM', 'fac:due:read', 'ALL');
