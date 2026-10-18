package com.atomeng.common.identity.authz;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * 행 수준 권한(◐) 판정 포트 (설계 8.3-3). 구현은 프로젝트 데이터를 소유한 core 모듈이 제공한다.
 * 운영에서는 Redis 5분 캐시 + core.project.member-changed 이벤트 무효화를 붙인다(Step 4 후속).
 */
public interface ProjectScopeResolver {

    /** 직원이 기준일에 해당 프로젝트에 투입(core.project_member) 되어 있는가 */
    boolean isMember(UUID employeeId, UUID projectId, LocalDate asOf);

    /** 프로젝트의 발주 고객사 (포털 CLIENT 범위 검사용) */
    Optional<UUID> clientOf(UUID projectId);
}
