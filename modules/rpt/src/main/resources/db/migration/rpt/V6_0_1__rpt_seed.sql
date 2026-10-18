-- =====================================================================
-- V6_0_1 — RPT 공통 HWPX 템플릿 1종 + RPT 세부 권한
--   템플릿 패키지는 classpath(rpt/templates/RT_COMMON/2026.1.0) — MinIO rpt-templates 업로드 API(FR-RPT-013)는 후속.
--   승인자 NULL(⚠️ QA 승인 전 예시 템플릿)
-- =====================================================================
INSERT INTO rpt.template (id, report_type, locale, name, created_by) VALUES
 ('0192e000-0000-7000-8000-000000000001', 'RT_COMMON', 'ko', '안전점검·진단 공통 보고서(합성 서식)',
  '00000000-0000-0000-0000-000000000000');

INSERT INTO rpt.template_version (id, template_id, semver, package_ref, manifest, effective_from, status, created_by) VALUES
 ('0192e000-0000-7000-8000-000000002026', '0192e000-0000-7000-8000-000000000001', '2026.1.0',
  'classpath:rpt/templates/RT_COMMON/2026.1.0',
  '{"placeholders":{"report.title":{"required":true},"report.typeName":{"required":true},
    "facility.name":{"required":true},"inspection.date":{"required":true},"grade.computed":{"required":true},
    "grade.final":{"required":false},"grade.weightedScore":{"required":true},"report.revNo":{"required":true}},
    "repeats":{"ruleRows":{"kind":"TABLE_ROW","fields":["kind","version","effectiveFrom"]}},
    "blocks":"sections"}'::jsonb,
  DATE '2025-01-01', 'DEPLOYED', '00000000-0000-0000-0000-000000000000');

-- RPT 세부 권한 (설계 06 A2). 8.2 매트릭스 리소스(rpt:report · comment · template)와 겹치지 않는 세분화 리소스만 추가
--   grade:decide   = 안전등급 확정/override (PM = 책임기술자, BR-RPT-005)
--   delivery:confirm = 발주처 납품 확인 등록 (PM · ADMIN, v0.2)
--   rule:read      = 상태평가 rule table 조회
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('PM', 'rpt:grade:decide', 'PROJECT'),
 ('PM', 'rpt:delivery:confirm', 'PROJECT'), ('ADMIN', 'rpt:delivery:confirm', 'ALL'),
 ('EXEC', 'rpt:rule:read', 'ALL'), ('PM', 'rpt:rule:read', 'ALL'), ('ENG', 'rpt:rule:read', 'ALL'),
 ('QA', 'rpt:rule:read', 'ALL'), ('SYSADMIN', 'rpt:rule:read', 'ALL'), ('AUDITOR', 'rpt:rule:read', 'ALL');
