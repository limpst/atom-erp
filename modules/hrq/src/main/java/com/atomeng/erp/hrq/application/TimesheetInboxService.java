package com.atomeng.erp.hrq.application;

import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.erp.core.api.ProjectRoleQueryApi;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 웹 "내 할 일" — 내가 승인해야 할 타임시트(읽기 전용). 실패하는 쪽으로 닫힘(fail-closed):
 * <ul>
 *   <li>호출자가 오늘(서울) <b>책임자(RESP)</b>로 투입된 프로젝트의 제출(SUBMITTED) 항목만 — 보안 검토 HRQ-01 권장안과 같은 기준</li>
 *   <li>본인 타임시트는 제외(본인 승인 금지)</li>
 *   <li>직원 ID 가 없는 호출자(시스템 · 고객)는 빈 목록</li>
 * </ul>
 * EXEC · ADMIN 의 사유 대리 승인(HRQ-01)은 "내 할 일" 에 넣지 않는다(책임자가 처리하는 것이 기본 경로).
 * 직원 · 주(ISO) · 프로젝트별로 묶어 건수 · 시간 합계를 준다.
 */
@Service
public class TimesheetInboxService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final ProjectRoleQueryApi projectRoles;
    private final Clock clock;

    public TimesheetInboxService(JdbcTemplate jdbc, ProjectRoleQueryApi projectRoles, Clock clock) {
        this.jdbc = jdbc;
        this.projectRoles = projectRoles;
        this.clock = clock;
    }

    public List<Map<String, Object>> pendingApprovals() {
        UUID me = AtomAuthentication.currentPrincipal().map(AtomPrincipal::employeeId).orElse(null);
        if (me == null) {
            return List.of();
        }
        Set<UUID> resp = projectRoles.projectsWithRole(me, "RESP", LocalDate.now(clock.withZone(SEOUL)));
        if (resp.isEmpty()) {
            return List.of();
        }
        return jdbc.queryForList("""
                SELECT t.employee_id AS "employeeId", e.emp_no AS "empNo", e.name AS "employeeName",
                       t.project_id AS "projectId",
                       to_char(date_trunc('week', t.work_date), 'IYYY-"W"IW') AS "isoWeek",
                       count(*) AS "entries", sum(t.hours) AS "hours", min(t.work_date) AS "firstDate",
                       max(t.updated_at) AS "submittedAt"
                  FROM hrq.timesheet_entry t JOIN hrq.employee e ON e.id = t.employee_id
                 WHERE t.status = 'SUBMITTED' AND t.employee_id <> ? AND t.project_id = ANY(?::uuid[])
                 GROUP BY t.employee_id, e.emp_no, e.name, t.project_id, date_trunc('week', t.work_date)
                 ORDER BY min(t.work_date), e.emp_no""",
                me, resp.stream().map(UUID::toString).collect(java.util.stream.Collectors.joining(",", "{", "}")));
    }
}
