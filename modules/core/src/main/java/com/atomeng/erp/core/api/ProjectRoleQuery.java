package com.atomeng.erp.core.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 프로젝트 투입 역할 공개 조회(core.project_member.role_in_project). 보안 검토 2026-10-19 RPT-07: 등급 결정 · 변경은
 * 그 프로젝트의 지정 책임기술자(RESP 투입)만 — 다른 모듈은 core 테이블을 읽지 않고 이 포트로 확인한다.
 */
public interface ProjectRoleQuery {

    /** 책임기술자(= PM) 투입 역할 */
    String RESP = "RESP";

    /** 직원이 기준일에 그 프로젝트에 해당 역할(RESP · PART · QA · SUPPORT)로 투입되어 있는가 */
    boolean hasRole(UUID employeeId, UUID projectId, String roleInProject, LocalDate asOf);
}
