package com.atomeng.erp.core.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.core.adapter.out.persistence.ProjectMemberJdbc;
import com.atomeng.erp.core.api.EmployeeDirectoryApi;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** core::api {@link EmployeeDirectoryApi} 구현 — 직원 대장 행 보장(호출자 트랜잭션 참여) + 새로 만들면 감사 로그 */
@Service
public class EmployeeDirectoryService implements EmployeeDirectoryApi {

    private final ProjectMemberJdbc jdbc;
    private final AuditLog audit;
    private final CurrentActor actor;

    public EmployeeDirectoryService(ProjectMemberJdbc jdbc, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
    }

    @Override
    @Transactional
    public boolean ensureEmployee(EmployeeRecord e) {
        if (e.id() == null || e.empNo() == null || e.empNo().isBlank() || e.name() == null || e.name().isBlank()
                || e.hireDate() == null) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "직원 id · 사번 · 이름 · 입사일 필수");
        }
        boolean created = jdbc.ensureEmployee(e.id(), e.empNo(), e.name(), e.hireDate(), actor.actorIdOrSystem());
        if (created) {
            audit.append("CORE", AuditLog.Action.CREATE, "Employee", e.id(), null,
                    Map.of("empNo", e.empNo(), "source", "HRQ_MANUAL"));
        }
        return created;
    }
}
