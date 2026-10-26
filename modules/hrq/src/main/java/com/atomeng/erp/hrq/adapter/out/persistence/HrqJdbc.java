package com.atomeng.erp.hrq.adapter.out.persistence;

import com.atomeng.common.core.UuidV7;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.Grade;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.Rule;
import com.atomeng.erp.hrq.domain.compliance.ComplianceEngine.Staff;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** hrq 스키마 JDBC 저장소 (이력·리비전 테이블은 JPA 대신 JDBC — 불변 행을 dirty checking 으로 건드리지 않게) */
@Repository
public class HrqJdbc {

    public record EmployeeRow(UUID id, String empNo, String name, String field, String employmentType, String status,
                              boolean registeredField, LocalDate hiredOn, LocalDate resignPlannedOn,
                              LocalDate resignedOn, long version) {}

    public record GradeRow(UUID id, String grade, LocalDate validFrom, LocalDate validTo) {}

    public record CredentialRow(UUID id, UUID employeeId, String typeCode, String certNo, LocalDate acquiredOn,
                                LocalDate expiresOn, String status) {}

    /**
     * 타임시트 행. approvedBy · approvalMode(RESP · DELEGATED) · delegationReason 은 승인 이력 표시용(HRQ-01, V0039) —
     * 정정으로 바로 확정된 리비전은 approvalMode 가 없다(정정자 = approvedBy).
     */
    public record TimesheetRow(UUID id, UUID employeeId, UUID projectId, LocalDate workDate, String workType,
                               BigDecimal hours, String status, UUID timesheetKey, int revisionNo, UUID supersedesId,
                               String correctionReason, String postingPeriod, UUID approvedBy, String approvalMode,
                               String delegationReason) {
        public TimesheetRow(UUID id, UUID employeeId, UUID projectId, LocalDate workDate, String workType,
                            BigDecimal hours, String status, UUID timesheetKey, int revisionNo, UUID supersedesId,
                            String correctionReason, String postingPeriod) {
            this(id, employeeId, projectId, workDate, workType, hours, status, timesheetKey, revisionNo, supersedesId,
                    correctionReason, postingPeriod, null, null, null);
        }
    }

    private final JdbcTemplate jdbc;

    public HrqJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Date d(LocalDate x) {
        return x == null ? null : Date.valueOf(x);
    }

    private static LocalDate ld(ResultSet rs, String c) throws SQLException {
        Date x = rs.getDate(c);
        return x == null ? null : x.toLocalDate();
    }

    // ================================================================ employee
    public void insertEmployee(EmployeeRow e, UUID by) {
        jdbc.update("""
                INSERT INTO hrq.employee(id, emp_no, name, field, employment_type, status, registered_field, hired_on,
                                         created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)""",
                e.id(), e.empNo(), e.name(), e.field(), e.employmentType(), e.registeredField(), d(e.hiredOn()), by, by);
    }

    public Optional<EmployeeRow> employee(UUID id) {
        return jdbc.query("SELECT * FROM hrq.employee WHERE id = ? AND deleted_at IS NULL", this::emp, id)
                .stream().findFirst();
    }

    /** 배정·퇴사 등 직원 단위 직렬화 (03-IMPL Step 10: employee 행 락) */
    public Optional<EmployeeRow> lockEmployee(UUID id) {
        return jdbc.query("SELECT * FROM hrq.employee WHERE id = ? AND deleted_at IS NULL FOR UPDATE", this::emp, id)
                .stream().findFirst();
    }

    public List<EmployeeRow> employees(String field) {
        return jdbc.query("SELECT * FROM hrq.employee WHERE deleted_at IS NULL AND (?::text IS NULL OR field = ?) "
                + "ORDER BY emp_no", this::emp, field, field);
    }

    public void updateEmployeeStatus(UUID id, String status, LocalDate resignPlannedOn, LocalDate resignedOn, UUID by) {
        jdbc.update("""
                UPDATE hrq.employee SET status = ?, resign_planned_on = coalesce(?, resign_planned_on),
                       resigned_on = coalesce(?, resigned_on), updated_at = now(), updated_by = ?, version = version + 1
                 WHERE id = ?""", status, d(resignPlannedOn), d(resignedOn), by, id);
    }

    public boolean insertTermination(UUID empId, LocalDate resignedOn, String source, UUID eventId) {
        return jdbc.update("""
                INSERT INTO hrq.employee_termination(employee_id, resigned_on, source, event_id) VALUES (?, ?, ?, ?)
                ON CONFLICT DO NOTHING""", empId, d(resignedOn), source, eventId) == 1;
    }

    public void setTerminationEvent(UUID empId, LocalDate resignedOn, UUID eventId) {
        jdbc.update("UPDATE hrq.employee_termination SET event_id = ? WHERE employee_id = ? AND resigned_on = ?",
                eventId, empId, d(resignedOn));
    }

    public List<UUID> activeAllocations(UUID empId) {
        return jdbc.queryForList("SELECT id FROM hrq.allocation WHERE employee_id = ? AND status IN "
                + "('CONFIRMED','ACTIVE','PENDING_OVERRIDE') ORDER BY start_on", UUID.class, empId);
    }

    private EmployeeRow emp(ResultSet rs, int i) throws SQLException {
        return new EmployeeRow(rs.getObject("id", UUID.class), rs.getString("emp_no"), rs.getString("name"),
                rs.getString("field"), rs.getString("employment_type"), rs.getString("status"),
                rs.getBoolean("registered_field"), ld(rs, "hired_on"), ld(rs, "resign_planned_on"),
                ld(rs, "resigned_on"), rs.getLong("version"));
    }

    // ================================================================ grade
    public List<GradeRow> grades(UUID empId) {
        return jdbc.query("SELECT * FROM hrq.grade_history WHERE employee_id = ? ORDER BY valid_from",
                (rs, i) -> new GradeRow(rs.getObject("id", UUID.class), rs.getString("grade"), ld(rs, "valid_from"),
                        ld(rs, "valid_to")), empId);
    }

    public Optional<String> gradeAt(UUID empId, LocalDate at) {
        return jdbc.queryForList("""
                SELECT grade FROM hrq.grade_history WHERE employee_id = ? AND valid_from <= ?
                   AND (valid_to IS NULL OR valid_to > ?)""", String.class, empId, d(at), d(at)).stream().findFirst();
    }

    public Optional<GradeRow> openGrade(UUID empId) {
        return jdbc.query("SELECT * FROM hrq.grade_history WHERE employee_id = ? AND valid_to IS NULL",
                (rs, i) -> new GradeRow(rs.getObject("id", UUID.class), rs.getString("grade"), ld(rs, "valid_from"),
                        null), empId).stream().findFirst();
    }

    public void closeGrade(UUID gradeId, LocalDate validTo) {
        jdbc.update("UPDATE hrq.grade_history SET valid_to = ? WHERE id = ?", d(validTo), gradeId);
    }

    public UUID insertGrade(UUID empId, String grade, LocalDate from, UUID evidence, UUID by) {
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO hrq.grade_history(id, employee_id, grade, valid_from, evidence_attachment_id, created_by)
                VALUES (?, ?, ?, ?, ?, ?)""", id, empId, grade, d(from), evidence, by);
        return id;
    }

    // ================================================================ credential
    public UUID insertCredential(UUID empId, String type, String certNo, LocalDate acquired, LocalDate expires, UUID by) {
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO hrq.credential(id, employee_id, type_code, cert_no, acquired_on, expires_on, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, empId, type, certNo, d(acquired), d(expires), by, by);
        return id;
    }

    public Optional<CredentialRow> lockCredential(UUID id) {
        return jdbc.query("SELECT * FROM hrq.credential WHERE id = ? FOR UPDATE", this::cred, id).stream().findFirst();
    }

    public List<CredentialRow> credentials(UUID empId) {
        return jdbc.query("SELECT * FROM hrq.credential WHERE employee_id = ? ORDER BY acquired_on DESC", this::cred, empId);
    }

    /** 웹 목록(자격 승인 큐 · 만료 칩): 직원 · 자격 종류 이름 포함. employeeId 가 있으면 그 직원 것만 */
    public List<Map<String, Object>> credentialList(String status, UUID employeeId) {
        return jdbc.queryForList("""
                SELECT c.id, c.employee_id, e.emp_no, e.name AS employee_name, c.type_code, t.name AS type_name,
                       t.has_expiry, c.cert_no, c.acquired_on, c.expires_on, c.status, c.reject_reason, c.approved_by,
                       c.approved_at, c.created_at
                  FROM hrq.credential c JOIN hrq.employee e ON e.id = c.employee_id
                  JOIN hrq.credential_type t ON t.code = c.type_code
                 WHERE (?::text IS NULL OR c.status = ?) AND (?::uuid IS NULL OR c.employee_id = ?)
                 ORDER BY (c.status = 'PENDING') DESC, c.expires_on NULLS LAST, e.emp_no""",
                status, status, employeeId, employeeId);
    }

    public void setCredentialStatus(UUID id, String status, UUID by, String rejectReason, boolean approved) {
        jdbc.update("""
                UPDATE hrq.credential SET status = ?, reject_reason = coalesce(?, reject_reason),
                       approved_by = CASE WHEN ? THEN ? ELSE approved_by END,
                       approved_at = CASE WHEN ? THEN now() ELSE approved_at END,
                       updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""",
                status, rejectReason, approved, by, approved, by, id);
    }

    public int supersedeOthers(UUID empId, String type, UUID keepId, UUID by) {
        return jdbc.update("""
                UPDATE hrq.credential SET status = 'SUPERSEDED', updated_at = now(), updated_by = ?, version = version + 1
                 WHERE employee_id = ? AND type_code = ? AND id <> ? AND status IN ('VALID','EXPIRING','EXPIRED')""",
                by, empId, type, keepId);
    }

    /** VALID → EXPIRING (D-90 이내) */
    public int markExpiring(LocalDate today, int days) {
        return jdbc.update("""
                UPDATE hrq.credential SET status = 'EXPIRING', updated_at = now(), version = version + 1
                 WHERE status = 'VALID' AND expires_on IS NOT NULL AND expires_on - ?::date <= ?""", d(today), days);
    }

    /** VALID|EXPIRING → EXPIRED (만료일 경과) — 전이된 행만 반환(이벤트 1회) */
    public List<CredentialRow> markExpired(LocalDate today) {
        return jdbc.query("""
                UPDATE hrq.credential SET status = 'EXPIRED', updated_at = now(), version = version + 1
                 WHERE status IN ('VALID','EXPIRING') AND expires_on < ? RETURNING *""", this::cred, d(today));
    }

    public List<CredentialRow> expiringWithin(LocalDate today, int maxDays) {
        return jdbc.query("""
                SELECT * FROM hrq.credential WHERE status IN ('VALID','EXPIRING') AND expires_on IS NOT NULL
                   AND expires_on >= ? AND expires_on - ?::date <= ?""", this::cred, d(today), d(today), maxDays);
    }

    public boolean insertNotificationIfAbsent(String key, UUID recordId, int threshold) {
        return jdbc.update("""
                INSERT INTO hrq.notification_log(idempotency_key, record_id, threshold_days) VALUES (?, ?, ?)
                ON CONFLICT DO NOTHING""", key, recordId, threshold) == 1;
    }

    private CredentialRow cred(ResultSet rs, int i) throws SQLException {
        return new CredentialRow(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getString("type_code"), rs.getString("cert_no"), ld(rs, "acquired_on"), ld(rs, "expires_on"),
                rs.getString("status"));
    }

    // ================================================================ compliance
    public List<Rule> registrationRules(LocalDate at) {
        return jdbc.query("""
                SELECT * FROM hrq.registration_rule WHERE effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY field, min_grade""", (rs, i) -> new Rule(rs.getObject("id", UUID.class), rs.getString("field"),
                Grade.valueOf(rs.getString("min_grade")), rs.getInt("min_count"), rs.getString("required_credential"),
                rs.getBoolean("count_leave"), rs.getBoolean("count_upward"), rs.getString("legal_reference")),
                d(at), d(at));
    }

    /**
     * 평가일 D 시점 인원 (BR-HRQ-006): 등록분야·상근, 입사 ≤ D, 퇴사(예정)일 당일까지 재직(그 다음날부터 제외).
     * 자격은 승인(VALID·EXPIRING)·D 시점 미만료만 인정(BR-HRQ-005: PENDING 미보유).
     */
    public List<Staff> staffAt(LocalDate at) {
        Map<UUID, Set<String>> creds = new HashMap<>();
        jdbc.query("""
                SELECT employee_id, type_code FROM hrq.credential
                 WHERE status IN ('VALID','EXPIRING') AND acquired_on <= ? AND (expires_on IS NULL OR expires_on >= ?)""",
                rs -> {
                    creds.computeIfAbsent(rs.getObject(1, UUID.class), k -> new HashSet<>()).add(rs.getString(2));
                }, d(at), d(at));
        return jdbc.query("""
                SELECT e.id, e.emp_no, e.field, e.status,
                       (SELECT g.grade FROM hrq.grade_history g WHERE g.employee_id = e.id AND g.valid_from <= ?
                           AND (g.valid_to IS NULL OR g.valid_to > ?)) AS grade
                  FROM hrq.employee e
                 WHERE e.deleted_at IS NULL AND e.registered_field AND e.employment_type = 'FULLTIME'
                   AND e.hired_on <= ?
                   AND (e.resigned_on IS NULL OR e.resigned_on >= ?)
                   AND (e.resign_planned_on IS NULL OR e.resign_planned_on >= ?)""", (rs, i) -> {
            UUID id = rs.getObject("id", UUID.class);
            String g = rs.getString("grade");
            return new Staff(id, rs.getString("emp_no"), rs.getString("field"), g == null ? null : Grade.valueOf(g),
                    "LEAVE".equals(rs.getString("status")), creds.getOrDefault(id, Set.of()));
        }, d(at), d(at), d(at), d(at), d(at));
    }

    /** 변화점: 퇴사(예정)일 다음날, 자격 만료 다음날, 규칙 시행일, 등급 변경일 */
    public List<LocalDate> changePoints(LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT DISTINCT d FROM (
                  SELECT resign_planned_on + 1 AS d FROM hrq.employee WHERE resign_planned_on IS NOT NULL
                  UNION SELECT resigned_on + 1 FROM hrq.employee WHERE resigned_on IS NOT NULL
                  UNION SELECT expires_on + 1 FROM hrq.credential WHERE status IN ('VALID','EXPIRING') AND expires_on IS NOT NULL
                  UNION SELECT effective_from FROM hrq.registration_rule
                  UNION SELECT effective_to FROM hrq.registration_rule WHERE effective_to IS NOT NULL
                  UNION SELECT valid_from FROM hrq.grade_history
                  UNION SELECT hired_on FROM hrq.employee) t
                 WHERE d > ? AND d <= ? ORDER BY d""", LocalDate.class, d(from), d(to));
    }

    // ================================================================ timesheet
    public void insertTimesheet(TimesheetRow t, UUID by, UUID approvedBy) {
        jdbc.update("""
                INSERT INTO hrq.timesheet_entry(id, employee_id, project_id, work_date, work_type, hours, status,
                    timesheet_key, revision_no, supersedes_id, correction_reason, posting_period, approved_by, approved_at,
                    created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ?::uuid IS NULL THEN NULL ELSE now() END, ?, ?)""",
                t.id(), t.employeeId(), t.projectId(), d(t.workDate()), t.workType(), t.hours(), t.status(),
                t.timesheetKey(), t.revisionNo(), t.supersedesId(), t.correctionReason(), t.postingPeriod(), approvedBy,
                approvedBy, by, by);
    }

    public int deleteOpenEntries(UUID empId, LocalDate from, LocalDate to) {
        // 정정 리비전(revision_no > 1, 재승인 대기 SUBMITTED)은 주간 저장으로 지우지 않는다(HRQ-02)
        return jdbc.update("DELETE FROM hrq.timesheet_entry WHERE employee_id = ? AND work_date BETWEEN ? AND ? "
                + "AND status IN ('DRAFT','SUBMITTED') AND revision_no = 1", empId, d(from), d(to));
    }

    /**
     * 하루 합계(시간) — 주간 저장으로 교체되지 않는 행만: SUPERSEDED 제외, 최초 리비전의 DRAFT · SUBMITTED 제외
     * (확정 행 · 재승인 대기 정정 리비전은 포함). HRQ-03 하루 24h 상한.
     */
    public BigDecimal retainedHoursOn(UUID empId, LocalDate day) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(hours), 0) FROM hrq.timesheet_entry
                 WHERE employee_id = ? AND work_date = ? AND status <> 'SUPERSEDED'
                   AND NOT (status IN ('DRAFT','SUBMITTED') AND revision_no = 1)""", BigDecimal.class, empId, d(day));
    }

    /** 하루 합계(시간) — SUPERSEDED 만 제외(정정 시 상한 검사용, 열린 입력 포함) */
    public BigDecimal activeHoursOn(UUID empId, LocalDate day) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(hours), 0) FROM hrq.timesheet_entry
                 WHERE employee_id = ? AND work_date = ? AND status <> 'SUPERSEDED'""", BigDecimal.class, empId, d(day));
    }

    /** 근무일 기준 프로젝트 투입(core.project_member, 역할 무관) — HRQ-03 */
    public boolean assignedOn(UUID empId, UUID projectId, LocalDate on) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM core.project_member
                                WHERE employee_id = ? AND project_id = ? AND ? BETWEEN from_date AND to_date)""",
                Boolean.class, empId, projectId, d(on)));
    }

    /** 기준일에 그 프로젝트의 책임기술자(RESP)로 투입 중인가 — HRQ-01 · HRQ-02 */
    public boolean responsibleOn(UUID empId, UUID projectId, LocalDate on) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM core.project_member
                                WHERE employee_id = ? AND project_id = ? AND role_in_project = 'RESP'
                                  AND ? BETWEEN from_date AND to_date)""",
                Boolean.class, empId, projectId, d(on)));
    }

    public UUID timesheetCreatedBy(UUID id) {
        return jdbc.queryForObject("SELECT created_by FROM hrq.timesheet_entry WHERE id = ?", UUID.class, id);
    }

    /** SUBMITTED → APPROVED + 승인 방식(RESP · DELEGATED) · 대리 승인 사유 기록 */
    public void approveTimesheet(UUID id, UUID by, String mode, String delegationReason) {
        jdbc.update("""
                UPDATE hrq.timesheet_entry SET status = 'APPROVED', approved_by = ?, approved_at = now(),
                       approval_mode = ?, delegation_reason = ?,
                       updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""",
                by, mode, delegationReason, by, id);
    }

    public List<TimesheetRow> entries(UUID empId, LocalDate from, LocalDate to) {
        return jdbc.query("SELECT * FROM hrq.timesheet_entry WHERE employee_id = ? AND work_date BETWEEN ? AND ? "
                + "ORDER BY work_date, created_at, revision_no", this::ts, empId, d(from), d(to));
    }

    public Optional<TimesheetRow> lockTimesheet(UUID id) {
        return jdbc.query("SELECT * FROM hrq.timesheet_entry WHERE id = ? FOR UPDATE", this::ts, id).stream().findFirst();
    }

    public List<TimesheetRow> revisions(UUID key) {
        return jdbc.query("SELECT * FROM hrq.timesheet_entry WHERE timesheet_key = ? ORDER BY revision_no", this::ts, key);
    }

    public void setTimesheetStatus(UUID id, String status, UUID by, boolean approve) {
        jdbc.update("""
                UPDATE hrq.timesheet_entry SET status = ?,
                       approved_by = CASE WHEN ? THEN ? ELSE approved_by END,
                       approved_at = CASE WHEN ? THEN now() ELSE approved_at END,
                       updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""",
                status, approve, by, approve, by, id);
    }

    public int countOpenInMonth(LocalDate monthStart, LocalDate nextMonthStart) {
        return jdbc.queryForObject("SELECT count(*) FROM hrq.timesheet_entry WHERE work_date >= ? AND work_date < ? "
                + "AND status IN ('DRAFT','SUBMITTED')", Integer.class, d(monthStart), d(nextMonthStart));
    }

    /** 원가 귀속 월(posting_period) 기준 인·일 집계 (8h = 1 인·일), 근무일 시점 등급으로 분할 */
    public List<Map<String, Object>> aggregateMandays(String ym) {
        return jdbc.queryForList("""
                SELECT t.project_id, t.employee_id, coalesce(g.grade, 'UNGRADED') AS grade, t.work_type,
                       round(sum(t.hours) / 8.0, 2) AS mandays
                  FROM hrq.timesheet_entry t
                  LEFT JOIN hrq.grade_history g
                    ON g.employee_id = t.employee_id AND t.work_date >= g.valid_from
                   AND (g.valid_to IS NULL OR t.work_date < g.valid_to)
                 WHERE t.posting_period = ? AND t.status IN ('APPROVED','LOCKED')
                 GROUP BY t.project_id, t.employee_id, coalesce(g.grade, 'UNGRADED'), t.work_type
                 ORDER BY t.project_id, t.employee_id, t.work_type""", ym);
    }

    public int lockApproved(String ym, UUID by) {
        return jdbc.update("""
                UPDATE hrq.timesheet_entry SET status = 'LOCKED', updated_at = now(), updated_by = ?, version = version + 1
                 WHERE posting_period = ? AND status = 'APPROVED'""", by, ym);
    }

    private TimesheetRow ts(ResultSet rs, int i) throws SQLException {
        return new TimesheetRow(rs.getObject("id", UUID.class), rs.getObject("employee_id", UUID.class),
                rs.getObject("project_id", UUID.class), ld(rs, "work_date"), rs.getString("work_type"),
                rs.getBigDecimal("hours"), rs.getString("status"), rs.getObject("timesheet_key", UUID.class),
                rs.getInt("revision_no"), rs.getObject("supersedes_id", UUID.class), rs.getString("correction_reason"),
                rs.getString("posting_period"), rs.getObject("approved_by", UUID.class), rs.getString("approval_mode"),
                rs.getString("delegation_reason"));
    }

    // ================================================================ month close
    public Optional<String> monthStatus(String ym) {
        return jdbc.queryForList("SELECT status FROM hrq.month_close WHERE year_month = ?", String.class, ym)
                .stream().findFirst();
    }

    public Optional<String> lockMonth(String ym) {
        jdbc.update("INSERT INTO hrq.month_close(year_month, status) VALUES (?, 'OPEN') ON CONFLICT DO NOTHING", ym);
        return jdbc.queryForList("SELECT status FROM hrq.month_close WHERE year_month = ? FOR UPDATE", String.class, ym)
                .stream().findFirst();
    }

    public void closeMonth(String ym, BigDecimal total, UUID eventId, UUID by) {
        jdbc.update("""
                UPDATE hrq.month_close SET status = 'CLOSED', total_mandays = ?, event_id = ?, closed_at = now(),
                       closed_by = ? WHERE year_month = ?""", total, eventId, by, ym);
    }

    public void reopenMonth(String ym, String reason, UUID by) {
        jdbc.update("""
                UPDATE hrq.month_close SET status = 'REOPENED', reopened_at = now(), reopened_by = ?, reopen_reason = ?
                 WHERE year_month = ?""", by, reason, ym);
    }

    /** 웹 목록: 월 마감 이력(최근 월부터) */
    public List<Map<String, Object>> monthCloses() {
        return jdbc.queryForList("""
                SELECT year_month, status, total_mandays, closed_at, closed_by, reopened_at, reopened_by, reopen_reason
                  FROM hrq.month_close ORDER BY year_month DESC LIMIT 36""");
    }

    public Map<String, Object> monthClose(String ym) {
        return jdbc.queryForMap("SELECT * FROM hrq.month_close WHERE year_month = ?", ym);
    }

    public static List<UUID> ids(List<TimesheetRow> rows) {
        List<UUID> out = new ArrayList<>();
        rows.forEach(r -> out.add(r.id()));
        return out;
    }

    public static Timestamp ts(Instant i) {
        return Timestamp.from(i);
    }
}
