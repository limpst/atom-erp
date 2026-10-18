package com.atomeng.erp.core.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.application.ProjectService;
import com.atomeng.erp.core.domain.model.Project;
import com.atomeng.erp.core.domain.model.ProjectStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
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
                           String currency, long version) {
        static ProjectResponse of(Project p) {
            return new ProjectResponse(p.getId(), p.getProjectNo(), p.getName(), p.getServiceType(), p.getClientId(),
                    p.getStatus(), p.getStartDate(), p.getEndDate(), p.getContractAmount(), p.getCurrency(),
                    p.getVersion());
        }
    }

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
}
