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

    public ProjectService(ProjectRepository projects, ClientRepository clients, DocNumberGenerator numbers,
                          OutboxPublisher outbox, AuditLog audit, Clock clock, ProjectMemberJdbc members) {
        this.members = members;
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

    @Transactional(readOnly = true)
    public java.util.List<Map<String, Object>> members(UUID projectId) {
        get(projectId);
        return members.members(projectId);
    }
}
