-- =====================================================================
-- V4_0_1 — 장비 유형 10종(설계 A1.3) + 검교정 규칙 + EQP 세부 권한
--   ⚠️ 검교정 주기·앤빌 기준은 예시 값(approved_by NULL). 법령 최신본 확인 필요
--      (「시설물의 안전 및 유지관리에 관한 특별법」 시행령, 「안전점검 및 진단 세부지침」 장비 기준, 「국가표준기본법」)
-- =====================================================================
INSERT INTO eqp.equipment_type (id, code, name, calibration_required, measure_items) VALUES
 ('0192c000-0000-7000-8000-000000000001','NDT-RH','슈미트해머',true,'["rebound_r","compressive_est"]'),
 ('0192c000-0000-7000-8000-000000000002','NDT-UPV','초음파 속도 측정기',true,'["upv_mps","crack_depth_mm"]'),
 ('0192c000-0000-7000-8000-000000000003','NDT-RBR','철근탐사기',true,'["cover_mm","spacing_mm"]'),
 ('0192c000-0000-7000-8000-000000000004','NDT-CORE','코어채취기',false,'["core_specimen"]'),
 ('0192c000-0000-7000-8000-000000000005','MSR-CRK','균열폭 측정기',true,'["crack_width_mm"]'),
 ('0192c000-0000-7000-8000-000000000006','SRV-LVL','레벨·토탈스테이션',true,'["elevation_mm","deflection_mm"]'),
 ('0192c000-0000-7000-8000-000000000007','SRV-TLT','기울기계',true,'["tilt_deg"]'),
 ('0192c000-0000-7000-8000-000000000008','CHM-CBN','탄산화 시험 키트',false,'["carbonation_mm"]'),
 ('0192c000-0000-7000-8000-000000000009','CHM-CL','염화물 측정기',true,'["chloride_kgm3"]'),
 ('0192c000-0000-7000-8000-00000000000a','UAV','드론',false,'["image","thermal"]'),
 -- 키트 부속(드론 배터리·UPV 프로브 등) — 교정 대상 부속은 별도 유형으로 등록(A8-4)
 ('0192c000-0000-7000-8000-00000000000b','KIT-PART','키트 부속(교정 대상)',true,'[]');

INSERT INTO eqp.calibration_rule (id, type_id, interval_months, pre_use_check, legal_basis, effective_from)
SELECT gen_random_uuid(), id,
       CASE code WHEN 'NDT-CORE' THEN 6 ELSE 12 END,
       CASE code WHEN 'NDT-RH' THEN '{"anvil":{"target":80,"tol":2}}'::jsonb END,
       '⚠️ 예시 값 — 법령 최신본 확인 필요(안전점검 및 진단 세부지침 장비 기준 / 국가표준기본법)',
       DATE '2000-01-01'
  FROM eqp.equipment_type;

-- EQP 세부 권한 (설계 04 A2). 8.2 매트릭스 리소스(eqp:equipment·checkout·setting)와 겹치지 않는 세분화 리소스만 추가
--   calibration = 검교정 등록(ADMIN) / override = 만료 예외 승인(QA, BR-EQP-003)
--   reservation = 예약 생성(PM·ENG)·승인(PM·ADMIN) / flight = 드론 비행승인(PM)·비행로그(ENG)
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('ADMIN', 'eqp:calibration:write', 'ALL'), ('SYSADMIN', 'eqp:calibration:write', 'ALL'),
 ('EXEC', 'eqp:calibration:read', 'ALL'), ('PM', 'eqp:calibration:read', 'ALL'), ('QA', 'eqp:calibration:read', 'ALL'),
 ('ADMIN', 'eqp:calibration:read', 'ALL'), ('SYSADMIN', 'eqp:calibration:read', 'ALL'),
 ('ENG', 'eqp:calibration:read', 'ALL'), ('AUDITOR', 'eqp:calibration:read', 'ALL'),
 ('QA', 'eqp:override:approve', 'ALL'),
 ('PM', 'eqp:reservation:create', 'ALL'), ('ENG', 'eqp:reservation:create', 'ALL'),
 ('PM', 'eqp:reservation:approve', 'ALL'), ('ADMIN', 'eqp:reservation:approve', 'ALL'),
 ('PM', 'eqp:reservation:read', 'ALL'), ('ENG', 'eqp:reservation:read', 'ALL'), ('ADMIN', 'eqp:reservation:read', 'ALL'),
 ('EXEC', 'eqp:reservation:read', 'ALL'),
 ('ADMIN', 'eqp:label:issue', 'ALL'), ('SYSADMIN', 'eqp:label:issue', 'ALL'),
 ('ADMIN', 'eqp:scan:run', 'ALL'), ('SYSADMIN', 'eqp:scan:run', 'ALL'),
 ('ADMIN', 'eqp:drone:write', 'ALL'),
 ('PM', 'eqp:flight-approval:write', 'ALL'), ('ADMIN', 'eqp:flight-approval:write', 'ALL'),
 ('ENG', 'eqp:flight:write', 'ALL'), ('ADMIN', 'eqp:flight:write', 'ALL'),
 ('EXEC', 'eqp:flight:read', 'ALL'), ('PM', 'eqp:flight:read', 'ALL'), ('QA', 'eqp:flight:read', 'ALL'),
 ('ADMIN', 'eqp:flight:read', 'ALL');
