package com.atomeng.erp.hrq.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.core.api.EmployeeDirectoryApi;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc.EmployeeRow;
import com.atomeng.erp.hrq.api.HrqEvents;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 기술자 프로필·등급 이력·퇴사 (03-IMPL Step 2·3, FR-HRQ-002·003, 설계 B6 v0.4) */
@Service
public class EmployeeService {

    private static final Set<String> GRADES = Set.of("JUNIOR", "INTER", "SENIOR", "MASTER");

    public record Register(UUID id, String empNo, String name, String field, String employmentType,
                           boolean registeredField, LocalDate hiredOn, String initialGrade, LocalDate gradeFrom) {}

    private final HrqJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final EmployeeDirectoryApi directory;

    public EmployeeService(HrqJdbc jdbc, OutboxPublisher outbox, AuditLog audit, CurrentActor actor,
                           EmployeeDirectoryApi directory) {
        this.directory = directory;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
    }

    /**
     * 기술자 등록(core.employee 와 1:1). 더존 동기화(Step 2) 전까지 수동 등록이 직원 대장 입력을 겸한다 —
     * core 직원 행이 없으면 core::api {@link EmployeeDirectoryApi} 로 같은 트랜잭션에서 만든다(없으면 FK 위반으로 등록 불가였음).
     */
    @Transactional
    public EmployeeRow register(Register r) {
        UUID by = actor.actorIdOrSystem();
        directory.ensureEmployee(new EmployeeDirectoryApi.EmployeeRecord(r.id(), r.empNo(), r.name(), r.hiredOn()));
        jdbc.insertEmployee(new EmployeeRow(r.id(), r.empNo(), r.name(), r.field(), r.employmentType(), "ACTIVE",
                r.registeredField(), r.hiredOn(), null, null, 0), by);
        if (r.initialGrade() != null) {
            requireGrade(r.initialGrade());
            jdbc.insertGrade(r.id(), r.initialGrade(), r.gradeFrom() != null ? r.gradeFrom() : r.hiredOn(), null, by);
        }
        audit.append("HRQ", AuditLog.Action.CREATE, "Employee", r.id(), null,
                Map.of("empNo", r.empNo(), "field", r.field(), "employmentType", r.employmentType()));
        return get(r.id());
    }

    public EmployeeRow get(UUID id) {
        return jdbc.employee(id).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "기술자 없음: " + id));
    }

    /** 등급 변경: 기존 열린 레코드 valid_to = from 으로 닫고 신규 (FR-HRQ-003) → hrq.grade.changed */
    @Transactional
    public void changeGrade(UUID empId, String grade, LocalDate from, UUID evidenceId) {
        requireGrade(grade);
        jdbc.lockEmployee(empId).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "기술자 없음: " + empId));
        UUID by = actor.actorIdOrSystem();
        var open = jdbc.openGrade(empId);
        String prev = open.map(HrqJdbc.GradeRow::grade).orElse(null);
        if (open.isPresent()) {
            if (!from.isAfter(open.get().validFrom())) {
                throw new DomainException(HrqErrors.INVALID, "새 등급 시작일은 현재 등급 시작일(%s) 이후여야 합니다"
                        .formatted(open.get().validFrom()));
            }
            jdbc.closeGrade(open.get().id(), from);
        }
        jdbc.insertGrade(empId, grade, from, evidenceId, by);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("grade", grade);
        after.put("from", from.toString());
        audit.append("HRQ", AuditLog.Action.UPDATE, "EmployeeGrade", empId, prev == null ? null : Map.of("grade", prev),
                after);
        outbox.publish(new HrqEvents.GradeChanged(empId, grade, prev, from));
    }

    /** 퇴사 예정 (BR-HRQ-007 what-if 트리거는 Step 8) */
    @Transactional
    public EmployeeRow planResign(UUID empId, LocalDate on) {
        get(empId);
        jdbc.updateEmployeeStatus(empId, "RESIGN_PLANNED", on, null, actor.actorIdOrSystem());
        return get(empId);
    }

    /**
     * 퇴사 확정 → hrq.employee.terminated 1회 (멱등키 employeeId+resignedOn). 활성 배정은 자동 해제하지 않고
     * activeAllocationIds 로 알린다(설계 A8-6).
     */
    @Transactional
    public boolean terminate(UUID empId, LocalDate resignedOn, String source) {
        EmployeeRow e = jdbc.lockEmployee(empId)
                .orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "기술자 없음: " + empId));
        UUID by = actor.actorIdOrSystem();
        jdbc.updateEmployeeStatus(empId, "RESIGNED", null, resignedOn, by);
        var allocations = jdbc.activeAllocations(empId);
        UUID eventId = outboxPublishOnce(e, resignedOn, source, allocations);
        if (eventId == null) {
            return false;
        }
        audit.append("HRQ", AuditLog.Action.UPDATE, "EmployeeTermination", empId, Map.of("status", e.status()),
                Map.of("status", "RESIGNED", "resignedOn", resignedOn.toString(), "source", source));
        return true;
    }

    private UUID outboxPublishOnce(EmployeeRow e, LocalDate resignedOn, String source, java.util.List<UUID> allocations) {
        // 멱등키 행을 먼저 선점(같은 트랜잭션) → 이미 있으면 이벤트 생략
        UUID placeholder = com.atomeng.common.core.UuidV7.next();
        if (!jdbc.insertTermination(e.id(), resignedOn, source, placeholder)) {
            return null;
        }
        UUID eventId = outbox.publish(new HrqEvents.EmployeeTerminated(e.id(), e.empNo(), resignedOn, allocations,
                source));
        jdbc.setTerminationEvent(e.id(), resignedOn, eventId);
        return eventId;
    }

    private static void requireGrade(String g) {
        if (!GRADES.contains(g)) {
            throw new DomainException(HrqErrors.INVALID, "등급: JUNIOR|INTER|SENIOR|MASTER");
        }
    }
}
