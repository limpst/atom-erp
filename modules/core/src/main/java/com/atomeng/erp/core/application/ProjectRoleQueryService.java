package com.atomeng.erp.core.application;

import com.atomeng.erp.core.api.ProjectRoleQueryApi;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** core::api {@link ProjectRoleQueryApi} 구현 — 투입 기간(from_date~to_date) 안의 역할만 */
@Service
public class ProjectRoleQueryService implements ProjectRoleQueryApi {

    private static final Set<String> ROLES = Set.of("RESP", "PART", "QA", "SUPPORT");

    private final JdbcTemplate jdbc;

    public ProjectRoleQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

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
