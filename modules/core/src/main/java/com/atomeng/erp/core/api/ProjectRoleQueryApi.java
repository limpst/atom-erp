package com.atomeng.erp.core.api;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * 프로젝트 투입 역할 조회 공개 포트(읽기 전용) — 웹 "내 할 일" 수신함이 "이 사람이 책임자(RESP)인 프로젝트" 를 알아야 할 때 쓴다.
 * core.project_member 는 core 가 소유하므로 다른 모듈은 이 포트로만 읽는다(직접 SQL 금지).
 */
public interface ProjectRoleQueryApi {

    /**
     * 기준일에 직원이 해당 투입 역할(RESP · PART · QA · SUPPORT)로 투입된 프로젝트 id 목록.
     * 알 수 없는 역할 코드는 빈 집합(실패하는 쪽으로 닫힘).
     */
    Set<UUID> projectsWithRole(UUID employeeId, String roleInProject, LocalDate asOf);
}
