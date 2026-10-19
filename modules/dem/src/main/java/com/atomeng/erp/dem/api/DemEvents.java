package com.atomeng.erp.dem.api;

import com.atomeng.common.events.DomainEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DEM 발행 이벤트 (설계 10 v0.3 B6 정본 이름). payload = 레코드 컴포넌트.
 * {@code dem.demolition.completed} 의 필드는 FAC 구독 계약(fac::api DemolitionCompletedContract)과 정확히 같아야 한다 —
 * 계약 테스트(apps DemFacContractTest)가 JSON 왕복(알 수 없는 필드 = 실패)으로 확인한다.
 */
public final class DemEvents {

    private DemEvents() {}

    /** dem.work-package.created — 소비: CST(예산 라인) */
    public record WorkPackageCreated(UUID workPackageId, UUID projectId, String packageType, UUID facilityId)
            implements DomainEvent {
        public static final String TYPE = "dem.work-package.created";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "WorkPackage"; }
        @Override public UUID aggregateId() { return workPackageId; }
    }

    /** dem.supervisor.assigned — 소비: HRQ(투입 현황), CST */
    public record SupervisorAssigned(UUID assignmentId, UUID workPackageId, UUID projectId, UUID employeeId, String role,
                                     LocalDate validFrom, LocalDate validTo) implements DomainEvent {
        public static final String TYPE = "dem.supervisor.assigned";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "WorkPackage"; }
        @Override public UUID aggregateId() { return workPackageId; }
    }

    /** dem.supervisor.released — 해제(수동 · HRQ 퇴사). reason = MANUAL · HRQ_TERMINATED */
    public record SupervisorReleased(UUID assignmentId, UUID workPackageId, UUID employeeId, LocalDate releasedOn,
                                     String reason) implements DomainEvent {
        public static final String TYPE = "dem.supervisor.released";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "WorkPackage"; }
        @Override public UUID aggregateId() { return workPackageId; }
    }

    /** dem.supervisor-gap.detected — 감리자 공백(A8: 퇴사 · 자격 만료) → PM · EXEC 알림, 일지 작성 차단 */
    public record SupervisorGapDetected(UUID workPackageId, UUID projectId, UUID employeeId, String kind,
                                        LocalDate effectiveOn) implements DomainEvent {
        public static final String TYPE = "dem.supervisor-gap.detected";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "WorkPackage"; }
        @Override public UUID aggregateId() { return workPackageId; }
    }

    /** dem.daily-log.submitted — 소비: CST(인·일 실적), PTL */
    public record DailyLogSubmitted(UUID logId, UUID workPackageId, LocalDate date, int failCount, boolean lateSync)
            implements DomainEvent {
        public static final String TYPE = "dem.daily-log.submitted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "DailyLog"; }
        @Override public UUID aggregateId() { return logId; }
    }

    /** dem.daily-log.locked — 자동 잠금(BR-DEM-006), 감사 */
    public record DailyLogLocked(UUID logId, UUID workPackageId, LocalDate date, String contentSha256,
                                 boolean wasSubmitted) implements DomainEvent {
        public static final String TYPE = "dem.daily-log.locked";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "DailyLog"; }
        @Override public UUID aggregateId() { return logId; }
    }

    /** dem.corrective-order.issued — FAIL 항목 시정지시(CRITICAL 이면 stopWorkRequested) */
    public record CorrectiveOrderIssued(UUID orderId, String orderNo, UUID workPackageId, String severity, Instant dueAt,
                                        boolean stopWorkRequested) implements DomainEvent {
        public static final String TYPE = "dem.corrective-order.issued";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "WorkPackage"; }
        @Override public UUID aggregateId() { return workPackageId; }
    }

    /** dem.evidence-photo.registered (v0.3) — 해시 검증 통과 사진. 소비: AIP(위험요소 후보 탐지, FR-DEM-020) */
    public record EvidencePhotoRegistered(UUID photoId, UUID attachmentId, String sha256, UUID workPackageId,
                                          UUID checklistResultId) implements DomainEvent {
        public static final String TYPE = "dem.evidence-photo.registered";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "EvidencePhoto"; }
        @Override public UUID aggregateId() { return photoId; }
    }

    public record IssuedRevision(UUID revisionId, String docNo, String revCode, String issuedRev) {}

    /** dem.revision.issued — Transmittal 발행. 소비: PTL, 영향분석 핸들러 */
    public record RevisionIssued(UUID transmittalId, String transmittalNo, UUID workPackageId, String purpose,
                                 List<IssuedRevision> revisions) implements DomainEvent {
        public static final String TYPE = "dem.revision.issued";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Transmittal"; }
        @Override public UUID aggregateId() { return transmittalId; }
    }

    /** dem.comment.closed — 의견 종결. 소비: QAR KPI */
    public record CommentClosed(UUID commentId, UUID revisionId, String previousStatus) implements DomainEvent {
        public static final String TYPE = "dem.comment.closed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "ReviewComment"; }
        @Override public UUID aggregateId() { return commentId; }
    }

    /**
     * dem.demolition.completed (v0.3) — 해체 완료 신고 접수(프로젝트 COMPLETING 전이). 소비: FAC(건축물 멸실 · 점검주기 중지).
     * 필드 = fac::api DemolitionCompletedContract.
     */
    public record DemolitionCompleted(UUID projectId, UUID workPackageId, UUID buildingId, LocalDate completedOn,
                                      UUID submissionId) implements DomainEvent {
        public static final String TYPE = "dem.demolition.completed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "DemProject"; }
        @Override public UUID aggregateId() { return projectId; }
    }

    /** dem.project.closed — 완료보고서 제출 · 종료. 소비: CST 정산, SAL 후속 영업 */
    public record ProjectClosed(UUID projectId, UUID buildingId, LocalDate closedOn) implements DomainEvent {
        public static final String TYPE = "dem.project.closed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "DemProject"; }
        @Override public UUID aggregateId() { return projectId; }
    }
}
