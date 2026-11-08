-- V0055 — PM 의 cst:receivable:read 삭제 (전사 미수 조회는 범위 판정 수단이 없는 ALL 전용 엔드포인트 — CMN-05 실패하는 쪽으로 닫힘).
--   PM 은 /projects/{projectId}/invoices (cst:invoice:read, PROJECT) 로 자기 프로젝트 청구만 본다. 권한 19 → 18건.
DELETE FROM core.role_permission WHERE role = 'PM' AND permission = 'cst:receivable:read';
