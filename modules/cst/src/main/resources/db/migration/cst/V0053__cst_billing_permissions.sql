-- V0053 — CST 청구 · 수금 세부 권한 (docs/CST_BILLING_COLLECTION_DESIGN.md §5). 8.2 매트릭스 리소스와 겹치지 않는 세분화 리소스
--   직무 분리: 재무(FIN)가 청구 · 입금을 등록하고 대사를 "제안", 경영지원(ADMIN) · 임원(EXEC)이 "확정".
--   DB 도 제안자 = 확정자를 거부한다(ck_alloc_4eyes). 감사(AUDITOR)는 조회만.
INSERT INTO core.role_permission(role, permission, scope) VALUES
 ('EXEC', 'cst:invoice:read', 'ALL'), ('ADMIN', 'cst:invoice:read', 'ALL'), ('FIN', 'cst:invoice:read', 'ALL'),
 ('AUDITOR', 'cst:invoice:read', 'ALL'), ('PM', 'cst:invoice:read', 'PROJECT'),
 ('FIN', 'cst:invoice:write', 'ALL'),
 ('EXEC', 'cst:receipt:read', 'ALL'), ('ADMIN', 'cst:receipt:read', 'ALL'), ('FIN', 'cst:receipt:read', 'ALL'),
 ('AUDITOR', 'cst:receipt:read', 'ALL'),
 ('FIN', 'cst:receipt:write', 'ALL'),
 ('FIN', 'cst:allocation:propose', 'ALL'),
 ('ADMIN', 'cst:allocation:confirm', 'ALL'), ('EXEC', 'cst:allocation:confirm', 'ALL'),
 ('EXEC', 'cst:receivable:read', 'ALL'), ('ADMIN', 'cst:receivable:read', 'ALL'), ('FIN', 'cst:receivable:read', 'ALL'),
 ('AUDITOR', 'cst:receivable:read', 'ALL'), ('PM', 'cst:receivable:read', 'PROJECT');
