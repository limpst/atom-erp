package com.atomeng.erp.sal.domain;

/**
 * 계약 상태 (설계 B3 sal.contract + v0.3 ready-to-sign).
 * DRAFT →(EXEC 사내 결재: sal.contract.ready-to-sign) PENDING →(고객 서명 기록: 프로젝트 생성 · sal.contract.signed) APPROVED
 */
public enum ContractStatus {
    DRAFT, PENDING, APPROVED, ACTIVE, COMPLETED, TERMINATED
}
