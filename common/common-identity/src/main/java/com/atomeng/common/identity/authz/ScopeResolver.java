package com.atomeng.common.identity.authz;

import java.util.Optional;
import java.util.UUID;

/**
 * 자원 id → 범위 판정 대상 해석기 (보안 검토 CMN-05). {@link RequirePermission#scopeResolver()} 로 지정한다.
 * 구현은 자원을 소유한 모듈의 Spring bean 이다.
 */
public interface ScopeResolver {

    /**
     * @param resourceKey {@link RequirePermission#resourceParam()} 로 지정한 파라미터 값
     * @return 판정 대상. 자원이 없으면 empty → 404 CORE-404-001
     */
    Optional<ScopeTarget> resolve(Object resourceKey);

    /**
     * 범위 판정 대상.
     *
     * @param projectId       자원이 속한 프로젝트(없으면 null) — PROJECT · CLIENT 범위
     * @param ownerEmployeeId 자원 소유 직원(없으면 null) — SELF 범위, PROJECT 범위에서 프로젝트가 없을 때
     * @param clientOrgId     자원 소유 고객사(없으면 null) — CLIENT 범위에서 프로젝트가 없을 때
     */
    record ScopeTarget(UUID projectId, UUID ownerEmployeeId, UUID clientOrgId) {
        public static ScopeTarget project(UUID projectId) {
            return new ScopeTarget(projectId, null, null);
        }

        public static ScopeTarget owner(UUID ownerEmployeeId) {
            return new ScopeTarget(null, ownerEmployeeId, null);
        }
    }

    /** 미지정 표식(기본값). */
    final class None implements ScopeResolver {
        private None() {}

        @Override
        public Optional<ScopeTarget> resolve(Object resourceKey) {
            return Optional.empty();
        }
    }
}
