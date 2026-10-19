package com.atomeng.erp.fac.api;

import com.atomeng.common.events.DomainEvent;
import java.time.LocalDate;
import java.util.UUID;

/** FAC 발행 이벤트 (설계 02 B6 정본 이름). payload = 레코드 컴포넌트. */
public final class FacEvents {

    private FacEvents() {}

    /** fac.building.registered — 소비: SAL, PTL */
    public record BuildingRegistered(UUID buildingId, UUID siteId, String pnu, String name, UUID ownerClientId)
            implements DomainEvent {
        public static final String TYPE = "fac.building.registered";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return buildingId; }
    }

    /** fac.building.merged — 중복 병합(source → survivor target), 소비: 전 모듈(참조 갱신, FLD 결함 재매핑) */
    public record BuildingMerged(UUID sourceId, UUID targetId, int movedNodes, int copiedGrades) implements DomainEvent {
        public static final String TYPE = "fac.building.merged";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return sourceId; }
    }

    /** fac.grade.recorded — 소비: PTL, CST, SAL */
    public record GradeRecorded(UUID buildingId, String grade, String previousGrade, String inspectionType,
                                LocalDate performedOn, UUID reportId) implements DomainEvent {
        public static final String TYPE = "fac.grade.recorded";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return buildingId; }
    }

    /** fac.grade.critical-detected — D·E 판정 (BR-FAC-011) */
    public record GradeCriticalDetected(UUID buildingId, String grade) implements DomainEvent {
        public static final String TYPE = "fac.grade.critical-detected";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return buildingId; }
    }

    /** fac.inspection.due-approaching — D-180/90/30, 소비: SAL(리드). idempotencyKey = BR-FAC-008 */
    public record InspectionDueApproaching(UUID buildingId, String inspectionType, LocalDate dueDate, int threshold,
                                           String idempotencyKey) implements DomainEvent {
        public static final String TYPE = "fac.inspection.due-approaching";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return buildingId; }
    }

    /** fac.inspection.overdue — 도래일 경과, 소비: SAL, EXEC */
    public record InspectionOverdue(UUID buildingId, String inspectionType, LocalDate dueDate)
            implements DomainEvent {
        public static final String TYPE = "fac.inspection.overdue";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return buildingId; }
    }

    /**
     * fac.building.demolished — DEM 해체 완료(dem.demolition.completed) 반영으로 시설물 멸실 처리.
     * 진행 중 점검 도래(inspection_due)는 CANCELLED, 이후 주기 계산 대상 아님. 소비: SAL, CST, PTL
     */
    public record BuildingDemolished(UUID buildingId, String previousStatus, LocalDate demolishedOn, UUID projectId,
                                     int cancelledDues) implements DomainEvent {
        public static final String TYPE = "fac.building.demolished";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Building"; }
        @Override public UUID aggregateId() { return buildingId; }
    }
}
