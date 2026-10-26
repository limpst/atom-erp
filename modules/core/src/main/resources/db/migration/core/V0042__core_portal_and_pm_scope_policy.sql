-- =====================================================================
-- V0042 — 보안 검토 2026-10-19 정책 결정(권장안 적용)
--   PTL-01: 고객(CUSTOMER, 포털 realm)은 ptl:* 만. 내부 API 권한(설계 8.2 표의 CLIENT 범위 셀)을 지운다.
--           보안 체인도 포털 주체를 /api/v1/ptl/** 로만 제한(PortalRealmRestrictionFilter, CORE-403-010).
--   SAL-05: PM 의 PROJECT 범위 sal:opportunity · eqp:equipment 는 자원(영업기회 · 장비 대장)에 프로젝트가 없어
--           판정할 수 없다(CMN-05 이후 403). 읽기는 ALL 로 명시하고, 기회 수정 · 장비 등록/수정은 PM 에게서 뺀다
--           (영업기회 수정 = SALES, 장비 대장 등록 · 수정 = ADMIN).
--   설계 표와 달라진 셀: apps/erp-server/src/test/resources/permission-policy/PTL-01.csv · SAL-05.csv
-- =====================================================================
DELETE FROM core.role_permission
 WHERE role = 'CUSTOMER' AND permission NOT LIKE 'ptl:%';

DELETE FROM core.role_permission
 WHERE role = 'PM' AND permission IN ('sal:opportunity:update', 'eqp:equipment:create', 'eqp:equipment:update');

UPDATE core.role_permission SET scope = 'ALL'
 WHERE role = 'PM' AND permission IN ('sal:opportunity:read', 'eqp:equipment:read');
