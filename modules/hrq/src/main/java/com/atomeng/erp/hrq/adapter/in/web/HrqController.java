package com.atomeng.erp.hrq.adapter.in.web;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.Role;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.application.ComplianceService;
import com.atomeng.erp.hrq.application.CredentialService;
import com.atomeng.erp.hrq.application.EmployeeService;
import com.atomeng.erp.hrq.application.TimesheetService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/hrq (설계 03 B4) — 기술자·등급·자격·등록요건·타임시트·월 마감 */
@RestController
@RequestMapping("/api/v1/hrq")
class HrqController {

    record RegisterEmployee(@NotNull UUID id, @NotBlank @Size(max = 20) String empNo, @NotBlank @Size(max = 50) String name,
                            @NotNull @Pattern(regexp = "ARCH|CIVIL|SAFETY|STRUCT|OTHER") String field,
                            @NotNull @Pattern(regexp = "FULLTIME|PARTTIME|CONCURRENT|CONTRACT") String employmentType,
                            boolean registeredField, @NotNull LocalDate hiredOn, String initialGrade,
                            LocalDate gradeFrom) {}

    record GradeChange(@NotNull @Pattern(regexp = "JUNIOR|INTER|SENIOR|MASTER") String grade,
                       @NotNull LocalDate validFrom, UUID evidenceAttachmentId) {}

    record Termination(@NotNull LocalDate resignedOn, @Pattern(regexp = "DOUZONE_SYNC|MANUAL") String source) {}

    record ResignPlan(@NotNull LocalDate resignPlannedOn) {}

    record CredentialRequest(@NotBlank @Size(max = 30) String typeCode, @Size(max = 50) String certNo,
                             @NotNull LocalDate acquiredOn, LocalDate expiresOn) {}

    record RejectRequest(@NotBlank String reason) {}

    record EntryRequest(@NotNull UUID projectId, @NotNull LocalDate workDate, @NotBlank String workType,
                        @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("24") BigDecimal hours) {}

    record WeekRequest(@NotNull List<@Valid EntryRequest> entries, boolean submit) {}

    record ApprovalRequest(@NotEmpty List<UUID> entryIds) {}

    record CorrectRequest(@NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("24") BigDecimal hours,
                          String workType, @NotBlank @Size(max = 500) String reason, boolean adjustment) {}

    record ReopenRequest(@NotBlank @Size(max = 500) String reason) {}

    private final EmployeeService employees;
    private final CredentialService credentials;
    private final ComplianceService compliance;
    private final TimesheetService timesheets;
    private final HrqJdbc jdbc;
    private final CurrentActor actor;

    HrqController(EmployeeService employees, CredentialService credentials, ComplianceService compliance,
                  TimesheetService timesheets, HrqJdbc jdbc, CurrentActor actor) {
        this.employees = employees;
        this.credentials = credentials;
        this.compliance = compliance;
        this.timesheets = timesheets;
        this.jdbc = jdbc;
        this.actor = actor;
    }

    /** ENG·LABELER 등 SELF 범위 역할은 본인 데이터만 (A2 "본인") */
    private void requireSelfOr(UUID empId, Role... broader) {
        var p = AtomAuthentication.currentPrincipal().orElseThrow();
        for (Role r : broader) {
            if (p.has(r)) {
                return;
            }
        }
        if (!empId.equals(actor.actorIdOrSystem())) {
            throw new DomainException(CoreErrors.OUT_OF_PROJECT_SCOPE, "본인 데이터만 접근할 수 있습니다");
        }
    }

    private static boolean isAdmin() {
        return AtomAuthentication.currentPrincipal().map(p -> p.has(Role.ADMIN)).orElse(false);
    }

    // ------------------------------------------------------------ 기술자·등급
    @PostMapping("/employees")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("hrq:employee:write")
    public HrqJdbc.EmployeeRow register(@Valid @RequestBody RegisterEmployee r) {
        return employees.register(new EmployeeService.Register(r.id(), r.empNo(), r.name(), r.field(),
                r.employmentType(), r.registeredField(), r.hiredOn(), r.initialGrade(), r.gradeFrom()));
    }

    @GetMapping("/employees")
    @RequirePermission("hrq:employee:read")
    public List<HrqJdbc.EmployeeRow> list(@RequestParam(required = false) String field) {
        return jdbc.employees(field);
    }

    @GetMapping("/employees/{id}")
    @RequirePermission("hrq:employee:read")
    public Map<String, Object> detail(@PathVariable UUID id,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                      LocalDate gradeAt) {
        requireSelfOr(id, Role.EXEC, Role.PM, Role.QA, Role.ADMIN, Role.SYSADMIN, Role.AUDITOR);
        var e = employees.get(id);
        return Map.of("employee", e, "grades", jdbc.grades(id), "credentials", jdbc.credentials(id),
                "gradeAt", jdbc.gradeAt(id, gradeAt != null ? gradeAt : LocalDate.now()).orElse("NONE"));
    }

    @PostMapping("/employees/{id}/grades")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("hrq:grade:write")
    public List<HrqJdbc.GradeRow> changeGrade(@PathVariable UUID id, @Valid @RequestBody GradeChange r) {
        employees.changeGrade(id, r.grade(), r.validFrom(), r.evidenceAttachmentId());
        return jdbc.grades(id);
    }

    @PostMapping("/employees/{id}:plan-resign")
    @RequirePermission("hrq:employee:write")
    public HrqJdbc.EmployeeRow planResign(@PathVariable UUID id, @Valid @RequestBody ResignPlan r) {
        return employees.planResign(id, r.resignPlannedOn());
    }

    @PostMapping("/employees/{id}:terminate")
    @RequirePermission("hrq:employee:write")
    public Map<String, Object> terminate(@PathVariable UUID id, @Valid @RequestBody Termination r) {
        boolean published = employees.terminate(id, r.resignedOn(), r.source() == null ? "MANUAL" : r.source());
        return Map.of("employee", employees.get(id), "eventPublished", published);
    }

    // ------------------------------------------------------------ 자격
    @PostMapping("/employees/{id}/credentials")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("hrq:credential:create")
    public Map<String, UUID> addCredential(@PathVariable UUID id, @Valid @RequestBody CredentialRequest r) {
        requireSelfOr(id, Role.ADMIN);
        return Map.of("id", credentials.register(id, r.typeCode(), r.certNo(), r.acquiredOn(), r.expiresOn()));
    }

    @PostMapping("/credentials/{id}:approve")
    @RequirePermission("hrq:credential:approve")
    public HrqJdbc.CredentialRow approve(@PathVariable UUID id) {
        return credentials.approve(id);
    }

    @PostMapping("/credentials/{id}:reject")
    @RequirePermission("hrq:credential:approve")
    public Map<String, String> reject(@PathVariable UUID id, @Valid @RequestBody RejectRequest r) {
        credentials.reject(id, r.reason());
        return Map.of("status", "REJECTED");
    }

    // ------------------------------------------------------------ 등록요건
    @GetMapping("/compliance")
    @RequirePermission("hrq:compliance:read")
    public ComplianceService.Evaluation compliance(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return compliance.evaluate(date);
    }

    @GetMapping("/compliance:forecast")
    @RequirePermission("hrq:compliance:read")
    public ComplianceService.Forecast forecast(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return compliance.forecast(from, to);
    }

    // ------------------------------------------------------------ 타임시트
    @PutMapping("/timesheets/{empId}/weeks/{isoWeek}")
    @RequirePermission("hrq:timesheet:write")
    public List<HrqJdbc.TimesheetRow> saveWeek(@PathVariable UUID empId, @PathVariable String isoWeek,
                                               @Valid @RequestBody WeekRequest r) {
        requireSelfOr(empId);
        return timesheets.saveWeek(empId, isoWeek, r.entries().stream().map(e -> new TimesheetService.EntryInput(
                e.projectId(), e.workDate(), e.workType(), e.hours())).toList(), r.submit());
    }

    @PostMapping("/timesheets/approvals")
    @RequirePermission("hrq:timesheet:approve")
    public Map<String, Integer> approve(@Valid @RequestBody ApprovalRequest r) {
        return Map.of("approved", timesheets.approve(r.entryIds()));
    }

    /** (v0.3) 확정 타임시트 정정 — Idempotency-Key 필수(설계 B4). 같은 키 재요청은 공통 필터가 재반환(TC-HRQ-013) */
    @PostMapping("/timesheets/{id}:correct")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("hrq:timesheet:correct")
    public HrqJdbc.TimesheetRow correct(@PathVariable UUID id,
                                        @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                        @Valid @RequestBody CorrectRequest r) {
        if (key == null || key.isBlank()) {
            throw new DomainException(CoreErrors.IDEMPOTENCY_KEY_REQUIRED, "정정 요청에는 Idempotency-Key 가 필수입니다");
        }
        var cur = jdbc.lockTimesheet(id)
                .orElseThrow(() -> new DomainException("HRQ-404-001", "타임시트 없음: " + id));
        requireSelfOr(cur.employeeId(), Role.PM, Role.ADMIN);
        return timesheets.correct(id, new TimesheetService.Correction(r.hours(), r.workType(), r.reason(),
                r.adjustment()), isAdmin());
    }

    @GetMapping("/timesheets/{empId}/weeks/{isoWeek}")
    @RequirePermission("hrq:timesheet:read")
    public List<HrqJdbc.TimesheetRow> week(@PathVariable UUID empId, @PathVariable String isoWeek) {
        requireSelfOr(empId, Role.EXEC, Role.PM, Role.ADMIN);
        LocalDate mon = weekMonday(isoWeek);
        return jdbc.entries(empId, mon, mon.plusDays(6));
    }

    @GetMapping("/timesheets/keys/{timesheetKey}/revisions")
    @RequirePermission("hrq:timesheet:read")
    public List<HrqJdbc.TimesheetRow> revisions(@PathVariable UUID timesheetKey) {
        return jdbc.revisions(timesheetKey);
    }

    // ------------------------------------------------------------ 월 마감
    @PostMapping("/month-close/{yyyyMM}")
    @RequirePermission("hrq:month-close:write")
    public TimesheetService.CloseResult close(@PathVariable String yyyyMM) {
        return timesheets.closeMonth(yyyyMM);
    }

    @PostMapping("/month-close/{yyyyMM}:reopen")
    @RequirePermission("hrq:month-close:write")
    public Map<String, Object> reopen(@PathVariable String yyyyMM, @Valid @RequestBody ReopenRequest r) {
        timesheets.reopenMonth(yyyyMM, r.reason());
        return jdbc.monthClose(yyyyMM);
    }

    private static LocalDate weekMonday(String isoWeek) {
        if (isoWeek == null || !isoWeek.matches("^\\d{4}-W\\d{2}$")) {
            throw new DomainException("HRQ-422-001", "ISO 주차 형식 YYYY-Www");
        }
        return LocalDate.of(Integer.parseInt(isoWeek.substring(0, 4)), 1, 4)
                .with(java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(isoWeek.substring(6)))
                .with(java.time.DayOfWeek.MONDAY);
    }
}
