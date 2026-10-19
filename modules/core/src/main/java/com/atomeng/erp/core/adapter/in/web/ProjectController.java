package com.atomeng.erp.core.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.application.ProjectService;
import com.atomeng.erp.core.domain.model.Project;
import com.atomeng.erp.core.domain.model.ProjectMember;
import com.atomeng.erp.core.domain.model.ProjectStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/core/projects")
class ProjectController {

    record CreateProjectRequest(
            @NotBlank @Size(max = 300) String name,
            @NotBlank @Size(max = 30) String serviceType,
            @NotNull UUID clientId,
            LocalDate startDate,
            LocalDate endDate,
            @PositiveOrZero BigDecimal contractAmount) {}

    record ProjectResponse(UUID id, String projectNo, String name, String serviceType, UUID clientId,
                           ProjectStatus status, LocalDate startDate, LocalDate endDate, BigDecimal contractAmount,
                           String currency, UUID pmId, long version) {
        static ProjectResponse of(Project p) {
            return new ProjectResponse(p.getId(), p.getProjectNo(), p.getName(), p.getServiceType(), p.getClientId(),
                    p.getStatus(), p.getStartDate(), p.getEndDate(), p.getContractAmount(), p.getCurrency(),
                    p.getPmId(), p.getVersion());
        }
    }

    record AssignMemberRequest(@NotNull UUID employeeId, @NotBlank String roleInProject, @NotNull LocalDate fromDate,
                               @NotNull LocalDate toDate) {}

    private final ProjectService service;

    ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    @RequirePermission("core:project:create")
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest req) {
        var p = service.create(new ProjectService.CreateProject(req.name(), req.serviceType(), req.clientId(),
                req.startDate(), req.endDate(), req.contractAmount()));
        return ResponseEntity.created(URI.create("/api/v1/core/projects/" + p.getId()))
                .eTag(IfMatch.etagOf(p.getVersion())).body(ProjectResponse.of(p));
    }

    /** ENG·PM 은 투입된 프로젝트만(◐), QA·EXEC·AUDITOR 등은 전체(●) — TC-CORE-001 */
    @GetMapping("/{id}")
    @RequirePermission(value = "core:project:read", projectIdParam = "id")
    public ResponseEntity<ProjectResponse> get(@PathVariable UUID id) {
        var p = service.get(id);
        return ResponseEntity.ok().eTag(IfMatch.etagOf(p.getVersion())).body(ProjectResponse.of(p));
    }

    /** 투입 지정 — 임원 · 경영지원은 전체, PM 은 자기가 투입된 프로젝트만(core:project-member:assign) */
    @PostMapping("/{id}/members")
    @RequirePermission(value = "core:project-member:assign", projectIdParam = "id")
    public ResponseEntity<Map<String, Object>> assignMember(@PathVariable UUID id,
                                                            @Valid @RequestBody AssignMemberRequest req) {
        UUID memberId = service.assignMember(new ProjectMember(id, req.employeeId(), req.roleInProject(),
                req.fromDate(), req.toDate()));
        return ResponseEntity.created(URI.create("/api/v1/core/projects/" + id + "/members/" + memberId))
                .body(Map.of("id", memberId, "projectId", id, "employeeId", req.employeeId(),
                        "roleInProject", req.roleInProject(), "fromDate", req.fromDate(), "toDate", req.toDate()));
    }

    @GetMapping("/{id}/members")
    @RequirePermission(value = "core:project:read", projectIdParam = "id")
    public List<Map<String, Object>> members(@PathVariable UUID id) {
        return service.members(id);
    }
}
