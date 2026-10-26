package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.erp.core.api.ProjectRoleQuery;
import java.time.LocalDate;
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
}
