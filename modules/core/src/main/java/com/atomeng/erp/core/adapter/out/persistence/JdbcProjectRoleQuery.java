package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.erp.core.api.ProjectRoleQuery;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** core::api {@link ProjectRoleQuery} 구현 — 투입 기간(from_date~to_date) 안의 역할 */
@Component
class JdbcProjectRoleQuery implements ProjectRoleQuery {

    private final JdbcTemplate jdbc;

    JdbcProjectRoleQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean hasRole(UUID employeeId, UUID projectId, String roleInProject, LocalDate asOf) {
        if (employeeId == null || projectId == null || roleInProject == null) {
            return false;
        }
        Boolean exists = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM core.project_member
                                WHERE employee_id = ? AND project_id = ? AND role_in_project = ?
                                  AND ? BETWEEN from_date AND to_date)""",
                Boolean.class, employeeId, projectId, roleInProject, asOf);
        return Boolean.TRUE.equals(exists);
    }

    private static final Set<String> ROLES = Set.of("RESP", "PART", "QA", "SUPPORT");

    @Override
    public Set<UUID> projectsWithRole(UUID employeeId, String roleInProject, LocalDate asOf) {
        if (employeeId == null || asOf == null || roleInProject == null || !ROLES.contains(roleInProject)) {
            return Set.of();
        }
        return new LinkedHashSet<>(jdbc.queryForList("""
                SELECT DISTINCT m.project_id FROM core.project_member m JOIN core.project p ON p.id = m.project_id
                 WHERE m.employee_id = ? AND m.role_in_project = ? AND ? BETWEEN m.from_date AND m.to_date
                   AND p.deleted_at IS NULL""", UUID.class, employeeId, roleInProject, asOf));
    }
}
