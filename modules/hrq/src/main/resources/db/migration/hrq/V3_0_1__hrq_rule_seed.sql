-- =====================================================================
-- V3_0_1 — HRQ 규칙·코드 시드 ⚠️ 예시 값 — 법령 최신본 확인 필요 (QA·법무 서명 전 운영 사용 금지)
--   approved_by NULL = 미승인 예시. 03-IMPL Step 6: QA 시트 → 생성 스크립트로 대체 예정.
-- =====================================================================
INSERT INTO hrq.credential_type(code, name, category, has_expiry) VALUES
 ('ARCH_STRUCT_PE',  '건축구조기술사(예시)',        'NATIONAL', false),
 ('ARCH_ENG',        '건축기사(예시)',              'NATIONAL', false),
 ('SAFETY_DIAG_CERT','안전진단 관련 민간자격(예시)', 'PRIVATE',  true);

INSERT INTO hrq.training_course(code, name, provider, required_hours) VALUES
 ('SAFETY_INSP_NEW',     '안전점검 신규교육(예시)', '합성 교육기관', 70),
 ('SAFETY_INSP_REGULAR', '안전점검 정기교육(예시)', '합성 교육기관', 35);

INSERT INTO hrq.training_renewal_rule(id, course_code, valid_months, effective_from, legal_reference) VALUES
 (core.uuid_generate_v7(), 'SAFETY_INSP_REGULAR', 36, DATE '2018-01-18',
  '⚠️ 예시: 시특법 시행규칙 교육 주기 — 법령 최신본 확인 필요');

-- 등록요건 (분야 ARCH, 등급별 최소 인원) — ⚠️ 예시 (설계 와이어프레임 "필요 2" 기준 합성값)
INSERT INTO hrq.registration_rule(id, rule_set_version, field, min_grade, min_count, count_leave, count_upward,
                                  effective_from, legal_reference)
SELECT core.uuid_generate_v7(), 'v2026.07-example', 'ARCH', g, 2, false, true, DATE '2026-07-01',
       '⚠️ 예시: 시특법 시행령 별표(안전진단전문기관 등록요건) — 법령 최신본 확인 필요'
FROM (VALUES ('MASTER'), ('SENIOR'), ('INTER'), ('JUNIOR')) AS t(g);

INSERT INTO hrq.role_requirement_rule(id, inspection_type, role, min_grade, required_credentials, required_courses,
                                      effective_from, legal_reference) VALUES
 (core.uuid_generate_v7(), 'PRECISION_DIAG', 'RESPONSIBLE', 'MASTER', '{ARCH_STRUCT_PE}', '{SAFETY_INSP_REGULAR}',
  DATE '2018-01-18', '⚠️ 예시: 책임기술자 자격요건 — 법령 최신본 확인 필요'),
 (core.uuid_generate_v7(), 'DETAILED', 'RESPONSIBLE', 'SENIOR', '{ARCH_STRUCT_PE,ARCH_ENG}', '{SAFETY_INSP_REGULAR}',
  DATE '2018-01-18', '⚠️ 예시: 책임기술자 자격요건 — 법령 최신본 확인 필요');

INSERT INTO hrq.concurrency_rule(id, code, role, max_concurrent, severity, effective_from, legal_reference) VALUES
 (core.uuid_generate_v7(), 'R-CONC-03', 'RESPONSIBLE', 3, 'HARD', DATE '2018-01-18',
  '⚠️ 예시: 동시 수행 한도 — 법령 최신본 확인 필요');

-- HRQ 세부 권한 (설계 03 A2). 리소스명은 8.2 매트릭스(hrq:engineer 등)와 별개 세분화
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC', 'hrq:employee:read', 'ALL'), ('PM', 'hrq:employee:read', 'ALL'), ('QA', 'hrq:employee:read', 'ALL'),
 ('ADMIN', 'hrq:employee:read', 'ALL'), ('SYSADMIN', 'hrq:employee:read', 'ALL'), ('AUDITOR', 'hrq:employee:read', 'ALL'),
 ('ENG', 'hrq:employee:read', 'SELF'), ('LABELER', 'hrq:employee:read', 'SELF'),
 ('ADMIN', 'hrq:employee:write', 'ALL'), ('SYSADMIN', 'hrq:employee:write', 'ALL'),
 ('ADMIN', 'hrq:grade:write', 'ALL'),
 ('ADMIN', 'hrq:credential:create', 'ALL'), ('ENG', 'hrq:credential:create', 'SELF'),
 ('LABELER', 'hrq:credential:create', 'SELF'),
 ('ADMIN', 'hrq:credential:approve', 'ALL'),
 ('EXEC', 'hrq:compliance:read', 'ALL'), ('SALES', 'hrq:compliance:read', 'ALL'), ('PM', 'hrq:compliance:read', 'ALL'),
 ('QA', 'hrq:compliance:read', 'ALL'), ('ADMIN', 'hrq:compliance:read', 'ALL'), ('SYSADMIN', 'hrq:compliance:read', 'ALL'),
 ('ENG', 'hrq:timesheet:write', 'SELF'), ('LABELER', 'hrq:timesheet:write', 'SELF'),
 ('PM', 'hrq:timesheet:approve', 'ALL'),
 ('PM', 'hrq:timesheet:correct', 'ALL'), ('ADMIN', 'hrq:timesheet:correct', 'ALL'),
 ('ENG', 'hrq:timesheet:correct', 'SELF'),
 ('EXEC', 'hrq:timesheet:read', 'ALL'), ('PM', 'hrq:timesheet:read', 'ALL'), ('ADMIN', 'hrq:timesheet:read', 'ALL'),
 ('ENG', 'hrq:timesheet:read', 'SELF'), ('LABELER', 'hrq:timesheet:read', 'SELF'),
 ('ADMIN', 'hrq:month-close:write', 'ALL');
