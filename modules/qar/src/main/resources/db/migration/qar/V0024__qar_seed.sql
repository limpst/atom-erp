-- =====================================================================
-- V0024 — QAR 시드: 워크플로우 2종 · AUTO-CHECK 규칙 세트 2개(2025.1 · 2026.1, 규칙 15종) · 공통 체크리스트 1종 · 세부 권한
--   ⚠️ 예시 값 — 체크리스트 문항 · 금지 표현 사전 · 재료시험 최소 수량 · 조사기간 상한은 QA 팀장 확정 전(법령 최신본 확인 필요)
--   규칙 세트는 approved_by NULL 인 채 DEPLOYED(검증자 서명 활성화 API 는 후속)
-- =====================================================================
INSERT INTO qar.workflow_def (id, code, version, applies_to, effective_from, status, created_by) VALUES
 ('0192f070-0000-7000-8000-000000000001', 'WF_REG_BASIC', 1, '{"reportTypes":["RT_REG","RT_EMG","RT_BLD"]}',
  DATE '2025-01-01', 'DEPLOYED', '00000000-0000-0000-0000-000000000000'),
 ('0192f070-0000-7000-8000-000000000002', 'WF_PSD_STANDARD', 1,
  '{"reportTypes":["RT_PRC","RT_PSD","RT_SEI","RT_ADJ_PRE","RT_ADJ_POST","RT_DEM"]}',
  DATE '2025-01-01', 'DEPLOYED', '00000000-0000-0000-0000-000000000000');

INSERT INTO qar.workflow_step_def (id, workflow_def_id, seq, step_code, role_required, sla_hours, optional) VALUES
 ('0192f070-0000-7000-8000-000000000101', '0192f070-0000-7000-8000-000000000001', 1, 'SELF_CHECK',     'ENG',    NULL, false),
 ('0192f070-0000-7000-8000-000000000102', '0192f070-0000-7000-8000-000000000001', 2, 'AI_REVIEW',      'SYSTEM', 1,    true),
 ('0192f070-0000-7000-8000-000000000103', '0192f070-0000-7000-8000-000000000001', 3, 'QA_REVIEW',      'QA',     16,   false),
 ('0192f070-0000-7000-8000-000000000104', '0192f070-0000-7000-8000-000000000001', 4, 'FINAL_APPROVAL', 'PM',     8,    false),
 ('0192f070-0000-7000-8000-000000000201', '0192f070-0000-7000-8000-000000000002', 1, 'SELF_CHECK',     'ENG',    NULL, false),
 ('0192f070-0000-7000-8000-000000000202', '0192f070-0000-7000-8000-000000000002', 2, 'AI_REVIEW',      'SYSTEM', 1,    true),
 ('0192f070-0000-7000-8000-000000000203', '0192f070-0000-7000-8000-000000000002', 3, 'QA_REVIEW',      'QA',     24,   false),
 ('0192f070-0000-7000-8000-000000000204', '0192f070-0000-7000-8000-000000000002', 4, 'FINAL_APPROVAL', 'PM',     8,    false);

-- AUTO-CHECK 규칙 세트 (BR-QAR-007: 라운드는 제출 시점 세트로 고정)
INSERT INTO qar.autocheck_rule_set (id, version, effective_from, effective_to, status, legal_basis, created_by) VALUES
 ('0192f070-0000-7000-8000-000000002025', '2025.1', DATE '2025-01-01', DATE '2026-01-01', 'DEPLOYED',
  '⚠️ 예시 — 국토안전관리원 보고서 평가 항목·부실 판정 기준 최신본 확인 필요', '00000000-0000-0000-0000-000000000000'),
 ('0192f070-0000-7000-8000-000000002026', '2026.1', DATE '2026-01-01', NULL, 'DEPLOYED',
  '⚠️ 예시 — 국토안전관리원 보고서 평가 항목·부실 판정 기준 최신본 확인 필요', '00000000-0000-0000-0000-000000000000');

-- 규칙 15종(A4.1 중 AC-HRQ-002 제외 — FLD 현장 출입 로그 모듈 없음). 두 세트는 금지 표현 사전 · 조사기간 상한만 다르다
INSERT INTO qar.autocheck_rule_def (rule_set_id, rule_id, description, default_severity, params, applies_to)
SELECT s.id, r.rule_id, r.description, r.sev, r.params::jsonb, ARRAY['*']
  FROM qar.autocheck_rule_set s
 CROSS JOIN (VALUES
  ('AC-NUM-001', '본문 서술 수치(개소 · 건 · 길이 · 폭)가 결함물량표 · 스냅샷 값과 일치', 'BLOCKING',
   '{"countTolerance":0,"lengthToleranceM":0.01,"widthToleranceMm":0.0}'),
  ('AC-NUM-002', '결함물량표 = 스냅샷 결함 재집계(개소 · 길이 · 면적), 단위 일관성', 'BLOCKING', '{"scale":2}'),
  ('AC-GRD-001', '모든 부재 그룹에 상태평가 · 결함 부재는 판정 근거(evidence) 존재', 'BLOCKING', '{}'),
  ('AC-GRD-002', '최종 등급 확정, 산정과 다르면 override 사유 100자 · 스냅샷 근거 결함', 'BLOCKING', '{"minReasonChars":100}'),
  ('AC-GRD-003', '직전 회차 대비 등급 개선인데 보수보강 이력 없음', 'WARNING', '{}'),
  ('AC-PHO-001', '사진 필수 결함마다 사진 1장 이상(사진대지 번호 = 결함 번호)', 'BLOCKING',
   '{"requiredDefectTypes":["CRACK","SPALL","REBAR_EXPOSURE"]}'),
  ('AC-PHO-002', '촬영일이 현장조사 기간 밖 · 같은 해시 사진의 다른 결함 중복 사용', 'WARNING', '{"maxSurveyDays":90}'),
  ('AC-EQP-001', '측정값 장비의 검교정 유효기간이 측정일을 포함(eqp::api 재판정)', 'BLOCKING', '{}'),
  ('AC-HRQ-001', '참여 · 책임기술자 자격이 조사일 · 제출일 기준 유효', 'BLOCKING', '{}'),
  ('AC-SEC-001', '보고서 필수 목차 · 필수 표 존재(목차 매니페스트)', 'BLOCKING',
   '{"requiredSections":["ch1.overview","ch4.quantities","ch6.condition","ch7.opinion"],"requiredTables":["ch4.quantities","ch6.condition"]}'),
  ('AC-SEC-002', '재료시험 종류별 최소 수량 충족 ⚠️ 예시 수량', 'BLOCKING', '{"minTests":{"REBOUND_HAMMER":3,"CARBONATION":3}}'),
  ('AC-TXT-001', 'AI 초안 미수락 문장(source=AI 블록) 존재', 'BLOCKING', '{}'),
  ('AC-TXT-002', '금지 · 위험 표현(단정적 안전 보장) 사용', 'WARNING', NULL),
  ('AC-DRW-001', '결함 있는 층마다 망도 존재, 망도 캡션 결함 수 = 결함 수', 'WARNING', '{}'),
  ('AC-DATE-001', '측정일 ≤ 점검일 ≤ 제출일, 조사기간 상한', 'WARNING', NULL)
 ) AS r(rule_id, description, sev, params)
 WHERE r.params IS NOT NULL;

INSERT INTO qar.autocheck_rule_def (rule_set_id, rule_id, description, default_severity, params, applies_to) VALUES
 ('0192f070-0000-7000-8000-000000002025', 'AC-TXT-002', '금지 · 위험 표현(단정적 안전 보장) 사용', 'WARNING',
  '{"phrases":[{"pattern":"완전히 안전","suggest":"조사 범위 내에서 구조 안전성에 중대한 결함은 확인되지 않음"},
               {"pattern":"영구적으로 문제\\s*없","suggest":"현 시점 기준으로 사용상 지장은 없는 것으로 판단됨"}]}', ARRAY['*']),
 ('0192f070-0000-7000-8000-000000002026', 'AC-TXT-002', '금지 · 위험 표현(단정적 안전 보장) 사용', 'WARNING',
  '{"phrases":[{"pattern":"완전히 안전","suggest":"조사 범위 내에서 구조 안전성에 중대한 결함은 확인되지 않음"},
               {"pattern":"영구적으로 문제\\s*없","suggest":"현 시점 기준으로 사용상 지장은 없는 것으로 판단됨"},
               {"pattern":"붕괴\\s*위험(이|은)?\\s*전혀","suggest":"조사 범위에서 붕괴 징후는 관찰되지 않음"},
               {"pattern":"보수(가|는)?\\s*필요\\s*없","suggest":"현 시점 보수 우선순위는 낮음(정기 관찰 권고)"}]}', ARRAY['*']),
 ('0192f070-0000-7000-8000-000000002025', 'AC-DATE-001', '측정일 ≤ 점검일 ≤ 제출일, 조사기간 상한', 'WARNING',
  '{"maxSurveyDays":120}', ARRAY['*']),
 ('0192f070-0000-7000-8000-000000002026', 'AC-DATE-001', '측정일 ≤ 점검일 ≤ 제출일, 조사기간 상한', 'WARNING',
  '{"maxSurveyDays":90}', ARRAY['*']);

-- 공통 체크리스트(⚠️ 예시 문항 — QA 팀장 엑셀 업로드(Step 3)로 교체)
INSERT INTO qar.checklist_template (id, report_type, version, effective_from, status) VALUES
 ('0192f070-0000-7000-8000-000000003001', 'RT_COMMON', '2025.1', DATE '2025-01-01', 'DEPLOYED');
INSERT INTO qar.checklist_item (id, template_id, item_no, category, question, legal_basis, criticality, autocheck_rule_id, stage) VALUES
 ('0192f070-0000-7000-8000-000000003101', '0192f070-0000-7000-8000-000000003001', '1.1', '보고서 작성',
  '본문 수치가 결함물량표 · 스냅샷과 일치하는가', '⚠️ 예시: 보고서 평가 항목(수치 정합)', 'CRITICAL', 'AC-NUM-001', 'BOTH'),
 ('0192f070-0000-7000-8000-000000003102', '0192f070-0000-7000-8000-000000003001', '1.2', '보고서 작성',
  '결함물량표 합계 · 단위가 맞는가', NULL, 'MAJOR', 'AC-NUM-002', 'BOTH'),
 ('0192f070-0000-7000-8000-000000003103', '0192f070-0000-7000-8000-000000003001', '2.1', '상태평가',
  '모든 부재 등급에 판정 근거가 있는가', '⚠️ 예시: 세부지침 상태평가 기준', 'CRITICAL', 'AC-GRD-001', 'BOTH'),
 ('0192f070-0000-7000-8000-000000003104', '0192f070-0000-7000-8000-000000003001', '2.2', '상태평가',
  '등급 override 에 사유 · 근거 결함이 있는가', NULL, 'CRITICAL', 'AC-GRD-002', 'BOTH'),
 ('0192f070-0000-7000-8000-000000003105', '0192f070-0000-7000-8000-000000003001', '3.1', '인력·장비',
  '측정 장비 검교정이 측정일에 유효했는가', '⚠️ 예시: 측정 장비 검교정 의무', 'MAJOR', 'AC-EQP-001', 'SELF'),
 ('0192f070-0000-7000-8000-000000003106', '0192f070-0000-7000-8000-000000003001', '3.2', '보고서 작성',
  '필수 목차 · 표가 모두 있는가', NULL, 'MAJOR', 'AC-SEC-001', 'SELF'),
 ('0192f070-0000-7000-8000-000000003107', '0192f070-0000-7000-8000-000000003001', '4.1', '보고서 작성',
  '단정적 안전 보장 표현을 쓰지 않았는가', NULL, 'MINOR', 'AC-TXT-002', 'SELF'),
 ('0192f070-0000-7000-8000-000000003108', '0192f070-0000-7000-8000-000000003001', '4.2', '조사범위',
  '보고서 서술이 현장조사 결과를 빠짐없이 반영하는가(작성자 확인)', NULL, 'MAJOR', NULL, 'SELF'),
 ('0192f070-0000-7000-8000-000000003109', '0192f070-0000-7000-8000-000000003001', '5.1', '상태평가',
  '상태평가 결과가 결함 분포와 공학적으로 타당한가(QA 판단)', NULL, 'MAJOR', NULL, 'QA'),
 ('0192f070-0000-7000-8000-000000003110', '0192f070-0000-7000-8000-000000003001', '5.2', '보고서 작성',
  '종합 의견의 보수 · 보강 권고가 구체적인가(QA 판단)', NULL, 'MINOR', NULL, 'QA');

-- QAR 세부 권한 (설계 07 A2). 8.2 매트릭스 리소스(qar:review · qar:checklist)와 겹치지 않는 세분화 리소스만 추가
--   author     = 검토 생성 · 자동검사 실행 · 제출 · 재제출 · 정정본 재검토 (ENG · PM, 프로젝트 범위)
--   answer     = 체크리스트 응답(단계별 담당은 서비스가 확인)
--   thread     = 코멘트 답변 · 해결 요청 · 해결 확인
--   remark     = 앵커 코멘트 생성 (QA · PM)
--   decision   = 단계 결정 PASS/REJECT (QA 배정자 · 승인 단계 PM)
--   approval   = 최종 승인 (PM = 책임기술자)
--   waiver     = WARNING 예외 승인 사유 (PM · QA 2인)
--   assignment = 검토자 수동 배정 · 재배정 (QA)
--   suggestion = AI 제안 조회 · 채택/기각 (QA 만, BR-QAR-005)
--   ai-job     = AI 검토 작업 수동 실행 · 타임아웃 스캔 (QA · SYSADMIN)
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ENG', 'qar:author:create', 'PROJECT'), ('ENG', 'qar:author:update', 'PROJECT'),
 ('ENG', 'qar:answer:update', 'PROJECT'), ('ENG', 'qar:thread:create', 'PROJECT'),
 ('PM', 'qar:author:create', 'PROJECT'), ('PM', 'qar:author:update', 'PROJECT'),
 ('PM', 'qar:answer:update', 'PROJECT'), ('PM', 'qar:thread:create', 'PROJECT'),
 ('PM', 'qar:remark:create', 'PROJECT'), ('PM', 'qar:decision:update', 'PROJECT'),
 ('PM', 'qar:approval:approve', 'PROJECT'), ('PM', 'qar:waiver:create', 'PROJECT'),
 ('QA', 'qar:answer:update', 'ALL'), ('QA', 'qar:thread:create', 'ALL'), ('QA', 'qar:remark:create', 'ALL'),
 ('QA', 'qar:decision:update', 'ALL'), ('QA', 'qar:waiver:create', 'ALL'), ('QA', 'qar:assignment:update', 'ALL'),
 ('QA', 'qar:suggestion:read', 'ALL'), ('QA', 'qar:suggestion:decide', 'ALL'), ('QA', 'qar:ai-job:run', 'ALL'),
 ('SYSADMIN', 'qar:ai-job:run', 'ALL');
