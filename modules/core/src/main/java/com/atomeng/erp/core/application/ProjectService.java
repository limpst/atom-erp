package com.atomeng.erp.core.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.core.adapter.out.persistence.ClientRepository;
import com.atomeng.erp.core.adapter.out.persistence.DocNumberGenerator;
import com.atomeng.erp.core.adapter.out.persistence.ProjectRepository;
import com.atomeng.erp.core.api.ProjectCommandApi;
import com.atomeng.erp.core.api.ProjectCreatedEvent;
import com.atomeng.erp.core.domain.model.Project;
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

    public ProjectService(ProjectRepository projects, ClientRepository clients, DocNumberGenerator numbers,
                          OutboxPublisher outbox, AuditLog audit, Clock clock) {
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
}
