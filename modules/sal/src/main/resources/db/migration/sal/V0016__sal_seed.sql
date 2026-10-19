-- =====================================================================
-- SAL 시드 (01-IMPL Step 0·4·12) — 모든 값은 ⚠️ 예시(합성). 운영 투입 전 법령 · 고시 · 발주기관 기준 최신본으로 교체하고
-- 검증자 서명(verified_by) 후 ACTIVE 로 전환해야 한다(FR-SAL-010). 여기서는 개발 · 시험용으로 verified_by NULL 인 채 ACTIVE.
-- 본문 JSON 은 골든 픽스처(modules/sal/src/test/resources/golden/rules/*.json)와 같다(SalIntegrationTest 가 대조).
-- =====================================================================

-- 대가 규칙 FEE_STANDARD: 2025.1(2025-01-01 ~ 2025-12-31) · 2026.1(2026-01-01 ~) — TC-SAL-010 기준일 경계
INSERT INTO sal.fee_rule_set(id, rule_version, valid_from, valid_to, status, body, legal_basis, created_by) VALUES
 ('0192d000-0000-7000-8000-000000002025', '2025.1', DATE '2025-01-01', DATE '2025-12-31', 'ACTIVE',
  '{"ruleVersion": "2025.1", "legalBasis": "⚠️ 예시 값(합성) — 엔지니어링사업 대가의 기준 · 시설물 안전점검·진단 대가 기준 · 2025 엔지니어링 노임단가 최신본 확인 필요", "laborUnitPrices": {"SPECIAL": 402000, "ADVANCED": 323000, "INTERMEDIATE": 261000, "BEGINNER": 199000}, "overheadRate": {"min": 1.1, "max": 1.2, "default": 1.1}, "techFeeRate": {"min": 0.2, "max": 0.4, "default": 0.2}, "rounding": {"line": "FLOOR_1", "total": "FLOOR_1000"}, "baseManDays": [{"serviceType": "PRECISE_DIAG", "facilityClass": "BUILDING", "areaBands": [{"fromM2": 0, "toM2": 10000, "workItems": {"DATA": 4.0, "FIELD": 22.0, "TEST": 8.0, "ANALYSIS": 6.0, "REPORT": 9.0}}, {"fromM2": 10000, "toM2": 30000, "workItems": {"DATA": 5.0, "FIELD": 30.0, "TEST": 10.0, "ANALYSIS": 8.0, "REPORT": 11.0}}, {"fromM2": 30000, "toM2": 50000, "workItems": {"DATA": 6.0, "FIELD": 38.0, "TEST": 12.0, "ANALYSIS": 10.0, "REPORT": 14.0}}], "gradeMix": {"SPECIAL": 0.1, "ADVANCED": 0.25, "INTERMEDIATE": 0.35, "BEGINNER": 0.3}}, {"serviceType": "PRECISE_INSP", "facilityClass": "BUILDING", "areaBands": [{"fromM2": 0, "toM2": 10000, "workItems": {"DATA": 2.0, "FIELD": 10.0, "TEST": 3.0, "REPORT": 5.0}}, {"fromM2": 10000, "toM2": 30000, "workItems": {"DATA": 3.0, "FIELD": 14.0, "TEST": 4.0, "REPORT": 6.5}}], "gradeMix": {"ADVANCED": 0.3, "INTERMEDIATE": 0.4, "BEGINNER": 0.3}}, {"serviceType": "SEISMIC_EVAL", "facilityClass": "BUILDING", "areaBands": [{"fromM2": 0, "toM2": 20000, "workItems": {"DATA": 3.0, "FIELD": 8.0, "ANALYSIS": 18.0, "REPORT": 7.0}}], "gradeMix": {"SPECIAL": 0.2, "ADVANCED": 0.4, "INTERMEDIATE": 0.4}}], "adjustmentFactors": [{"key": "AGE", "when": "years >= 30", "factor": 1.1}, {"key": "STRUCTURE", "when": "structure == ''STEEL''", "factor": 1.05}, {"key": "UNDERGROUND", "when": "basementFloors >= 3", "factor": 1.05}, {"key": "OCCUPIED", "when": "inUse == true", "factor": 1.1}]}'::jsonb,
  '⚠️ 예시 — 2025 엔지니어링 노임단가 · 엔지니어링사업 대가의 기준(법령 최신본 확인 필요)', '00000000-0000-7000-8000-000000000000'),
 ('0192d000-0000-7000-8000-000000002026', '2026.1', DATE '2026-01-01', NULL, 'ACTIVE',
  '{"ruleVersion": "2026.1", "legalBasis": "⚠️ 예시 값(합성) — 엔지니어링사업 대가의 기준 · 시설물 안전점검·진단 대가 기준 · 2026 엔지니어링 노임단가 최신본 확인 필요", "laborUnitPrices": {"SPECIAL": 418000, "ADVANCED": 336000, "INTERMEDIATE": 271000, "BEGINNER": 207000}, "overheadRate": {"min": 1.1, "max": 1.2, "default": 1.1}, "techFeeRate": {"min": 0.2, "max": 0.4, "default": 0.2}, "rounding": {"line": "FLOOR_1", "total": "FLOOR_1000"}, "baseManDays": [{"serviceType": "PRECISE_DIAG", "facilityClass": "BUILDING", "areaBands": [{"fromM2": 0, "toM2": 10000, "workItems": {"DATA": 4.0, "FIELD": 22.0, "TEST": 8.0, "ANALYSIS": 6.0, "REPORT": 9.0}}, {"fromM2": 10000, "toM2": 30000, "workItems": {"DATA": 5.0, "FIELD": 30.0, "TEST": 10.0, "ANALYSIS": 8.0, "REPORT": 11.0}}, {"fromM2": 30000, "toM2": 50000, "workItems": {"DATA": 6.0, "FIELD": 38.0, "TEST": 12.0, "ANALYSIS": 10.0, "REPORT": 14.0}}], "gradeMix": {"SPECIAL": 0.1, "ADVANCED": 0.25, "INTERMEDIATE": 0.35, "BEGINNER": 0.3}}, {"serviceType": "PRECISE_INSP", "facilityClass": "BUILDING", "areaBands": [{"fromM2": 0, "toM2": 10000, "workItems": {"DATA": 2.0, "FIELD": 10.0, "TEST": 3.0, "REPORT": 5.0}}, {"fromM2": 10000, "toM2": 30000, "workItems": {"DATA": 3.0, "FIELD": 14.0, "TEST": 4.0, "REPORT": 6.5}}], "gradeMix": {"ADVANCED": 0.3, "INTERMEDIATE": 0.4, "BEGINNER": 0.3}}, {"serviceType": "SEISMIC_EVAL", "facilityClass": "BUILDING", "areaBands": [{"fromM2": 0, "toM2": 20000, "workItems": {"DATA": 3.0, "FIELD": 8.0, "ANALYSIS": 18.0, "REPORT": 7.0}}], "gradeMix": {"SPECIAL": 0.2, "ADVANCED": 0.4, "INTERMEDIATE": 0.4}}], "adjustmentFactors": [{"key": "AGE", "when": "years >= 30", "factor": 1.1}, {"key": "STRUCTURE", "when": "structure == ''STEEL''", "factor": 1.05}, {"key": "UNDERGROUND", "when": "basementFloors >= 3", "factor": 1.05}, {"key": "OCCUPIED", "when": "inUse == true", "factor": 1.1}]}'::jsonb,
  '⚠️ 예시 — 2026 엔지니어링 노임단가 · 엔지니어링사업 대가의 기준(법령 최신본 확인 필요)', '00000000-0000-7000-8000-000000000000');

-- 적격심사 기준(지자체 용역 유형 예시 1종, 01-IMPL Step 12 규칙 본문 예) — ⚠️ 배점 · 산식 계수 예시
INSERT INTO sal.qual_criteria(id, criteria_code, valid_from, valid_to, status, body, created_by) VALUES
 ('0192d000-0000-7000-8000-00000000c025', 'LOCAL_GOV_SERVICE_V2025', DATE '2025-01-01', NULL, 'ACTIVE',
  '{"criteriaCode": "LOCAL_GOV_SERVICE_V2025", "legalBasis": "⚠️ 예시 값(합성) — 지방자치단체 용역 적격심사 세부기준 최신본 확인 필요", "passScore": 95.0, "lowerLimitRate": 0.87745, "similarYears": 5, "capabilityItems": [{"key": "SIMILAR_PERFORMANCE", "source": "performance_record.similar5y", "reference": "estPrice", "max": 15, "table": [{"ratioGte": 1.0, "score": 15}, {"ratioGte": 0.8, "score": 13.2}, {"ratioGte": 0.5, "score": 11}, {"ratioGte": 0, "score": 9}]}, {"key": "ENGINEERS", "source": "engineers.qualifiedCount", "reference": "engineers.required", "max": 15, "table": [{"ratioGte": 1.0, "score": 15}, {"ratioGte": 0.5, "score": 12}, {"ratioGte": 0, "score": 8}]}, {"key": "CREDIT", "source": "credit.grade", "max": 15, "gradeTable": {"AAA": 15, "AA+": 15, "A0": 14, "BBB0": 13, "BB0": 11}}, {"key": "BONUS_DISABLED_STD", "source": "company.isDisabledStandardWorkplace", "max": 1, "boolScore": 1.0}], "priceFormula": {"type": "LOCAL_GOV_A", "params": {"maxScore": 55, "k": 2, "anchorRate": 0.88}}}'::jsonb, '00000000-0000-7000-8000-000000000000');

-- 모듈 세부 권한(설계 01 A2). 8.2 표 셀(sal:opportunity · estimate · contract)과 겹치지 않는 리소스명만 쓴다
-- (PermissionMatrixTest 가 표 리소스는 셀과 정확히 일치하는지 검사). 견적 = quote, 할인 승인 = discount, 계약 등록·결재·서명 = agreement.
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('SALES', 'sal:fee:calculate', 'ALL'),
 ('PM', 'sal:fee:calculate', 'ALL'),
 ('EXEC', 'sal:fee:calculate', 'ALL'),
 ('SALES', 'sal:fee-rule:read', 'ALL'),
 ('PM', 'sal:fee-rule:read', 'ALL'),
 ('EXEC', 'sal:fee-rule:read', 'ALL'),
 ('ADMIN', 'sal:fee-rule:read', 'ALL'),
 ('SYSADMIN', 'sal:fee-rule:read', 'ALL'),
 ('SALES', 'sal:quote:create', 'ALL'),
 ('PM', 'sal:quote:create', 'ALL'),
 ('SALES', 'sal:quote:read', 'ALL'),
 ('PM', 'sal:quote:read', 'ALL'),
 ('EXEC', 'sal:quote:read', 'ALL'),
 ('ADMIN', 'sal:quote:read', 'ALL'),
 ('AUDITOR', 'sal:quote:read', 'ALL'),
 ('SALES', 'sal:quote:issue', 'ALL'),
 ('EXEC', 'sal:quote:issue', 'ALL'),
 ('SALES', 'sal:quote:send', 'ALL'),
 ('EXEC', 'sal:discount:approve', 'ALL'),
 ('SALES', 'sal:simulation:create', 'ALL'),
 ('SALES', 'sal:performance:read', 'ALL'),
 ('EXEC', 'sal:performance:read', 'ALL'),
 ('ADMIN', 'sal:performance:read', 'ALL'),
 ('PM', 'sal:performance:read', 'ALL'),
 ('SALES', 'sal:performance:write', 'ALL'),
 ('ADMIN', 'sal:performance:write', 'ALL'),
 ('SALES', 'sal:bid:read', 'ALL'),
 ('PM', 'sal:bid:read', 'ALL'),
 ('EXEC', 'sal:bid:read', 'ALL'),
 ('ADMIN', 'sal:bid:read', 'ALL'),
 ('SYSADMIN', 'sal:bid:collect', 'ALL'),
 ('ADMIN', 'sal:bid:collect', 'ALL'),
 ('SALES', 'sal:agreement:create', 'ALL'),
 ('ADMIN', 'sal:agreement:create', 'ALL'),
 ('SALES', 'sal:agreement:read', 'ALL'),
 ('PM', 'sal:agreement:read', 'ALL'),
 ('EXEC', 'sal:agreement:read', 'ALL'),
 ('ADMIN', 'sal:agreement:read', 'ALL'),
 ('FIN', 'sal:agreement:read', 'ALL'),
 ('AUDITOR', 'sal:agreement:read', 'ALL'),
 ('EXEC', 'sal:agreement:approve', 'ALL'),
 ('SALES', 'sal:agreement:sign', 'ALL'),
 ('ADMIN', 'sal:agreement:sign', 'ALL');
