package com.atomeng.erp.core.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.core.adapter.out.persistence.ClientRepository;
import com.atomeng.erp.core.adapter.out.persistence.DocNumberGenerator;
import com.atomeng.erp.core.adapter.out.persistence.ProjectMemberJdbc;
import com.atomeng.erp.core.adapter.out.persistence.ProjectRepository;
import com.atomeng.erp.core.api.ProjectCommandApi;
import com.atomeng.erp.core.api.ProjectCreatedEvent;
import com.atomeng.erp.core.domain.model.Project;
import com.atomeng.erp.core.domain.model.ProjectMember;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 프로젝트 유스케이스. 생성 시 업무 행 + outbox(core.project.created) + 감사 로그가 한 트랜잭션 (TC-CORE-004).
 */
@Service
public class ProjectService implements ProjectCommandApi {

    public record CreateProject(String name, String serviceType, UUID clientId, LocalDate startDate,
                                LocalDate endDate, BigDecimal contractAmount) {}

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final ProjectRepository projects;
    private final ClientRepository clients;
    private final DocNumberGenerator numbers;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final Clock clock;
    private final ProjectMemberJdbc members;
    private final com.atomeng.common.identity.authz.PermissionRegistry registry;

    public ProjectService(ProjectRepository projects, ClientRepository clients, DocNumberGenerator numbers,
                          OutboxPublisher outbox, AuditLog audit, Clock clock, ProjectMemberJdbc members,
                          com.atomeng.common.identity.authz.PermissionRegistry registry) {
        this.members = members;
        this.registry = registry;
        this.projects = projects;
        this.clients = clients;
        this.numbers = numbers;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Project create(CreateProject cmd) {
        if (!clients.existsById(cmd.clientId())) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "존재하지 않는 고객입니다",
                    java.util.List.of(Map.of("field", "clientId", "reason", "NOT_FOUND")));
        }
        int year = LocalDate.now(clock.withZone(SEOUL)).getYear();
        var project = Project.create(numbers.nextProjectNo(year), cmd.name(), cmd.serviceType(), cmd.clientId(),
                cmd.startDate(), cmd.endDate(), cmd.contractAmount());
        projects.saveAndFlush(project);
        outbox.publish(new ProjectCreatedEvent(project.getId(), project.getProjectNo(), project.getServiceType(),
                project.getClientId()));
        audit.append("CORE", AuditLog.Action.CREATE, "Project", project.getId(), null,
                Map.of("projectNo", project.getProjectNo(), "name", project.getName()));
        return project;
    }

    /**
     * core::api ProjectCommandApi — SAL 계약 서명 트랜잭션 안에서 호출(MANDATORY 아님: 단독 호출도 원자적).
     * 번호는 계약 승인 연도(BR-SAL-008), 계약 ID · 통화 연결, core.project.created 발행.
     */
    @Override
    @Transactional
    public CreatedProject createFromContract(CreateFromContract cmd) {
        if (!clients.existsById(cmd.clientId())) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "존재하지 않는 고객입니다",
                    java.util.List.of(Map.of("field", "clientId", "reason", "NOT_FOUND")));
        }
        var project = Project.create(numbers.nextProjectNo(cmd.numberingYear()), cmd.name(), cmd.serviceType(),
                cmd.clientId(), cmd.startDate(), cmd.endDate(), cmd.amountKrw());
        project.linkContract(cmd.contractId(), cmd.currency());
        projects.saveAndFlush(project);
        outbox.publish(new ProjectCreatedEvent(project.getId(), project.getProjectNo(), project.getServiceType(),
                project.getClientId()));
        audit.append("CORE", AuditLog.Action.CREATE, "Project", project.getId(), null,
                Map.of("projectNo", project.getProjectNo(), "name", project.getName(), "contractId",
                        String.valueOf(cmd.contractId())));
        return new CreatedProject(project.getId(), project.getProjectNo());
    }

    @Transactional(readOnly = true)
    public Project get(UUID id) {
        return projects.findById(id)
                .orElseThrow(() -> new DomainException(CoreErrors.NOT_FOUND, "프로젝트를 찾을 수 없습니다: " + id));
    }

    /**
     * 프로젝트 투입 지정(책임 · 참여 · QA · 지원). 계약 서명으로 생긴 프로젝트는 투입자가 0명이라 PROJECT 범위 역할(PM · ENG)이
     * 아무 작업도 못 하므로, 임원 · 경영지원(ALL) 이 책임기술자를 지정하고 이후 책임기술자(PROJECT)가 참여자를 지정한다.
     * RESP 투입은 core.project.pm_id 에도 기록. 같은 (직원, 역할) 재지정은 기간 갱신. 감사 로그.
     */
    @Transactional
    public UUID assignMember(ProjectMember m) {
        Project p = get(m.projectId());
        requireAssignAllowed(m);
        if (!members.activeEmployee(m.employeeId())) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "재직 중인 직원이 아닙니다: " + m.employeeId(),
                    java.util.List.of(Map.of("field", "employeeId", "reason", "NOT_FOUND")));
        }
        UUID id = members.upsert(m);
        if (m.isResponsible()) {
            p.assignPm(m.employeeId());
            projects.saveAndFlush(p);
        }
        audit.append("CORE", AuditLog.Action.UPDATE, "ProjectMember", m.projectId(), null,
                Map.of("employeeId", m.employeeId().toString(), "roleInProject", m.roleInProject(),
                        "fromDate", m.fromDate().toString(), "toDate", m.toDate().toString()));
        return id;
    }

    /** CORE-02: RESP 지정 · 본인 투입 변경은 core:project-member:assign 이 ALL 인 역할(EXEC · ADMIN)만 */
    public static final String MEMBER_ASSIGN_FORBIDDEN = "CORE-403-010";

    /**
     * 보안 검토 CORE-02 — 범위가 ALL 이 아닌 지정자(PROJECT 범위 PM)는 PART · QA · SUPPORT 만 지정할 수 있고,
     * 책임기술자(RESP, pm_id 변경)와 <b>본인</b> 투입(역할 · 기간 연장 포함)은 지정할 수 없다. 시스템 실행(인증 없음)은 통과.
     */
    private void requireAssignAllowed(ProjectMember m) {
        var principal = com.atomeng.common.identity.AtomAuthentication.currentPrincipal().orElse(null);
        if (principal == null) {
            return;
        }
        var scope = registry.scopeFor(principal.roles(), "core:project-member:assign")
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED,
                        "권한 없음: core:project-member:assign"));
        if (scope == com.atomeng.common.identity.PermissionScope.ALL) {
            return;
        }
        if (m.isResponsible()) {
            throw new DomainException(MEMBER_ASSIGN_FORBIDDEN,
                    "책임기술자(RESP) 지정은 임원 · 경영지원(전체 범위)만 할 수 있습니다")
                    .with("roleInProject", m.roleInProject());
        }
        if (m.employeeId().equals(principal.actorId())) {
            throw new DomainException(MEMBER_ASSIGN_FORBIDDEN,
                    "본인 투입(역할 · 기간)은 직접 바꿀 수 없습니다 — 임원 · 경영지원이 처리합니다")
                    .with("employeeId", m.employeeId().toString());
        }
    }

    /**
     * 프로젝트 목록 — core:project:read 범위대로 거른다(컨트롤러는 scopeCheckedInService).
     * ALL 전체 · PROJECT 오늘 기준 투입된 프로젝트(GET /{id} 의 isMember 규칙과 같음) · CLIENT 자기 고객사 · SELF 없음.
     */
    @Transactional(readOnly = true)
    public java.util.List<Project> listVisible(com.atomeng.erp.core.domain.model.ProjectStatus status) {
        var p = com.atomeng.common.identity.AtomAuthentication.currentPrincipal()
                .orElseThrow(() -> new DomainException(CoreErrors.UNAUTHENTICATED, "인증이 필요합니다"));
        var scope = registry.scopeFor(p.roles(), "core:project:read")
                .orElseThrow(() -> new DomainException(CoreErrors.PERMISSION_DENIED, "권한 없음: core:project:read"));
        java.util.List<Project> rows = switch (scope) {
            case ALL -> projects.findAllByOrderByCreatedAtDesc();
            case PROJECT -> p.employeeId() == null ? java.util.List.of()
                    : projects.findAllByIdInOrderByCreatedAtDesc(
                            members.activeProjectIds(p.employeeId(), LocalDate.now(clock.withZone(SEOUL))));
            case CLIENT -> p.clientOrgId() == null ? java.util.List.of()
                    : projects.findAllByClientIdOrderByCreatedAtDesc(p.clientOrgId());
            case SELF -> java.util.List.of();
        };
        return status == null ? rows : rows.stream().filter(x -> x.getStatus() == status).toList();
    }

    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> members(UUID projectId) {
        get(projectId);
        return members.members(projectId);
    }
}
