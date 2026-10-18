package com.atomeng.erp.rpt.api;

import com.atomeng.common.events.DomainEvent;
import com.atomeng.erp.fac.api.ReportPublishedContract;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * RPT 발행 이벤트 (설계 06 B6 v0.2 정식 세트 drafted · published · delivered · superseded + snapshot.frozen).
 * payload = 레코드 컴포넌트. 모두 업무 트랜잭션 안에서 core.outbox_event 에 기록된다.
 */
public final class RptEvents {

    private RptEvents() {}

    /** rpt.snapshot.frozen — 스냅샷 동결 + 사전검증 결과(BLOCKING 수 → FLD 보완 요청) */
    public record SnapshotFrozen(UUID reportId, UUID revisionId, int snapshotVer, String sha256, int blockingCount)
            implements DomainEvent {
        public static final String TYPE = "rpt.snapshot.frozen";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Report"; }
        @Override public UUID aggregateId() { return reportId; }
    }

    /** rpt.report.drafted (v0.2) — 리비전 최초 compose 완료(CST 진척 마일스톤 "보고서 초안"). 리비전당 1회 */
    public record ReportDrafted(UUID reportId, UUID revisionId, UUID projectId, int revNo, Instant composedAt)
            implements DomainEvent {
        public static final String TYPE = "rpt.report.drafted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Report"; }
        @Override public UUID aggregateId() { return reportId; }
    }

    /**
     * rpt.report.published — 발행(불변 보관 + 해시). 필드는 FAC 구독 계약(fac::api {@link ReportPublishedContract})과
     * 정확히 같다(계약 테스트 RptFacContractTest: 알 수 없는 필드 = 실패). grade = 책임기술자 확정 등급,
     * inspectionType = FAC 점검 종류(RT_REG → REGULAR …, 등급 없는 유형은 null), performedOn = 점검 수행일.
     */
    public record ReportPublished(UUID reportId, UUID revisionId, int revNo, UUID projectId, String reportType,
                                  List<UUID> buildingIds, String grade, String inspectionType, LocalDate performedOn,
                                  UUID approvedBy, String pdfSha256) implements DomainEvent {
        public static final String TYPE = ReportPublishedContract.TYPE;   // "rpt.report.published" — FAC 와 같은 정식명

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Report"; }
        @Override public UUID aggregateId() { return reportId; }
    }

    /** rpt.report.delivered (v0.2) — 발주처 납품 확인(CST 준공 기성 BR-CST-009 · PTL · SAL) */
    public record ReportDelivered(UUID reportId, UUID revisionId, int revNo, UUID projectId, Instant deliveredAt,
                                  UUID packageId, UUID receiptAttachmentId) implements DomainEvent {
        public static final String TYPE = "rpt.report.delivered";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Report"; }
        @Override public UUID aggregateId() { return reportId; }
    }

    /** rpt.report.superseded — 정정본 발행으로 이전 발행본 대체(BR-RPT-008) */
    public record ReportSuperseded(UUID reportId, UUID oldRevId, UUID newRevId, int oldRevNo, int newRevNo,
                                   String reason) implements DomainEvent {
        public static final String TYPE = "rpt.report.superseded";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Report"; }
        @Override public UUID aggregateId() { return reportId; }
    }
}
