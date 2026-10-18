package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.common.identity.authz.ProjectScopeResolver;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 행 수준 권한 판정 (설계 8.3-3). 투입 기간(from_date~to_date) 안의 core.project_member 소속 여부.
 * TODO(Step 4 후속): Redis 5분 캐시 + core.project.member-changed 이벤트 무효화.
 */
@Component
class JdbcProjectScopeResolver implements ProjectScopeResolver {

    private final JdbcTemplate jdbc;

    JdbcProjectScopeResolver(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isMember(UUID employeeId, UUID projectId, LocalDate asOf) {
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM core.project_member
                                WHERE employee_id = ? AND project_id = ? AND ? BETWEEN from_date AND to_date)""",
                Boolean.class, employeeId, projectId, asOf);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public Optional<UUID> clientOf(UUID projectId) {
        return jdbc.query("SELECT client_id FROM core.project WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> rs.getObject(1, UUID.class), projectId).stream().findFirst();
    }
}
