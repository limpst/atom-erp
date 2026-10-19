package com.atomeng.erp.core.adapter.out.persistence;

import com.atomeng.erp.core.domain.model.ProjectMember;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** core.project_member · core.employee 조회/쓰기 (JDBC) */
@Repository
public class ProjectMemberJdbc {

    private final JdbcTemplate jdbc;

    public ProjectMemberJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean activeEmployee(UUID employeeId) {
        Boolean b = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM core.employee WHERE id = ? AND deleted_at IS NULL "
                + "AND status <> 'RETIRED')", Boolean.class, employeeId);
        return Boolean.TRUE.equals(b);
    }

    /** 같은 (프로젝트, 직원, 역할)이면 기간만 갱신 → 행 id */
    public UUID upsert(ProjectMember m) {
        return jdbc.queryForObject("""
                INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (project_id, employee_id, role_in_project)
                DO UPDATE SET from_date = EXCLUDED.from_date, to_date = EXCLUDED.to_date
                RETURNING id""", UUID.class, m.projectId(), m.employeeId(), m.roleInProject(),
                Date.valueOf(m.fromDate()), Date.valueOf(m.toDate()));
    }

    public List<Map<String, Object>> members(UUID projectId) {
        return jdbc.queryForList("""
                SELECT m.id, m.employee_id AS "employeeId", e.emp_no AS "empNo", m.role_in_project AS "roleInProject",
                       m.from_date AS "fromDate", m.to_date AS "toDate"
                  FROM core.project_member m JOIN core.employee e ON e.id = m.employee_id
                 WHERE m.project_id = ? ORDER BY m.role_in_project, m.from_date, m.id""", projectId);
    }

    /** 직원 대장 행 보장(HRQ 수동 등록 = 더존 연동 전 직원 대장). 이미 있으면 그대로 둔다 → 새로 만들었는가 */
    public boolean ensureEmployee(UUID id, String empNo, String name, java.time.LocalDate hireDate, UUID by) {
        return jdbc.update("""
                INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING""", id, empNo, name, Date.valueOf(hireDate), by,
                by) == 1;
    }
}
