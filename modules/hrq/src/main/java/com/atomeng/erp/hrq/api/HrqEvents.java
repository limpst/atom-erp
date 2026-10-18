package com.atomeng.erp.hrq.api;

import com.atomeng.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** HRQ 발행 이벤트 (설계 03 B6 정본 이름). payload = 레코드 컴포넌트. */
public final class HrqEvents {

    private HrqEvents() {}

    public record TimesheetLine(UUID entryId, UUID timesheetKey, int revision, UUID projectId, String wbsCode,
                                String workType, BigDecimal hours) {}

    /**
     * hrq.timesheet.confirmed — PM 승인된 일 단위 타임시트. CST 인건비 원가 전기의 <b>유일한 원천</b>(BR-HRQ-013).
     */
    public record TimesheetConfirmed(UUID employeeId, LocalDate date, String postingPeriod, List<TimesheetLine> lines)
            implements DomainEvent {
        public static final String TYPE = "hrq.timesheet.confirmed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Employee"; }
        @Override public UUID aggregateId() { return employeeId; }
    }

    /**
     * hrq.timesheet.corrected — 확정 타임시트 정정(새 리비전). CST 는 idempotencyKey = timesheetKey:newRevision 으로
     * 역분개(previousHours) + 재전기(newHours) (BR-HRQ-014).
     */
    public record TimesheetCorrected(UUID timesheetKey, UUID previousEntryId, UUID newEntryId, int previousRevision,
                                     int newRevision, UUID employeeId, LocalDate workDate, String postingPeriod,
                                     UUID projectId, String wbsCode, String workType, BigDecimal previousHours,
                                     BigDecimal newHours, BigDecimal deltaHours, String reason, UUID correctedBy,
                                     boolean adjustment, String idempotencyKey) implements DomainEvent {
        public static final String TYPE = "hrq.timesheet.corrected";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Timesheet"; }
        @Override public UUID aggregateId() { return timesheetKey; }
    }

    public record MandayRow(UUID projectId, UUID employeeId, String grade, String workType, BigDecimal mandays) {}

    /** hrq.manday.closed — 월 마감. CST 는 잠금·대사 전용, 원가 재전기 금지(BR-HRQ-013) */
    public record MandayClosed(String yearMonth, BigDecimal totalMandays, List<MandayRow> rows) implements DomainEvent {
        public static final String TYPE = "hrq.manday.closed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "MonthClose"; }
        @Override public UUID aggregateId() {
            return UUID.nameUUIDFromBytes(("hrq-month:" + yearMonth).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    /** hrq.credential.expired — 소비: QAR(서명 권한 회수), DEM, 알림 */
    public record CredentialExpired(UUID employeeId, UUID credentialId, String credentialType, LocalDate expiredOn)
            implements DomainEvent {
        public static final String TYPE = "hrq.credential.expired";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Employee"; }
        @Override public UUID aggregateId() { return employeeId; }
    }

    /** hrq.employee.terminated (v0.4) — 퇴사 확정 시 1회(employeeId+resignedOn). 소비: QAR, DEM, FCD, FLD */
    public record EmployeeTerminated(UUID employeeId, String empNo, LocalDate resignedOn, List<UUID> activeAllocationIds,
                                     String source) implements DomainEvent {
        public static final String TYPE = "hrq.employee.terminated";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Employee"; }
        @Override public UUID aggregateId() { return employeeId; }
    }

    /** hrq.grade.changed (v0.2) — 소비: CST(등급별 단가 분할) */
    public record GradeChanged(UUID employeeId, String newGrade, String previousGrade, LocalDate effectiveDate)
            implements DomainEvent {
        public static final String TYPE = "hrq.grade.changed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Employee"; }
        @Override public UUID aggregateId() { return employeeId; }
    }
}
