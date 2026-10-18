package com.atomeng.erp.sal.api;

import com.atomeng.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * SAL 발행 이벤트 (설계 01 B6 v0.3 정본 이름). payload = 레코드 컴포넌트.
 * {@code sal.contract.signed} 필드는 08 CST 구독 계약(cst::api CstSubscriptionContracts.SalContractSigned)과 같아야 한다 —
 * 계약 테스트(apps SalCstContractTest)가 JSON 왕복(알 수 없는 필드 = 실패)으로 확인한다.
 */
public final class SalEvents {

    private SalEvents() {}

    /** sal.bid-notice.collected — 신규 공고 수집(적합도 점수 · 알림 트리거). fitScore 는 Step 11 전까지 null */
    public record BidNoticeCollected(UUID bidNoticeId, String bidNo, String bidSeq, Integer fitScore, boolean isNew)
            implements DomainEvent {
        public static final String TYPE = "sal.bid-notice.collected";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "BidNotice"; }
        @Override public UUID aggregateId() { return bidNoticeId; }
    }

    /** sal.opportunity.stage-changed — 단계 이동(CST 예측 · 대시보드) */
    public record OpportunityStageChanged(UUID opportunityId, String from, String to) implements DomainEvent {
        public static final String TYPE = "sal.opportunity.stage-changed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Opportunity"; }
        @Override public UUID aggregateId() { return opportunityId; }
    }

    /** sal.estimate.issued — 견적 발행(PTL 고객 공유). documentId 는 RPT 렌더 포트 연동 전 null */
    public record EstimateIssued(UUID estimateId, UUID opportunityId, String estimateNo, int version,
                                 BigDecimal offerAmount, BigDecimal vat, UUID documentId) implements DomainEvent {
        public static final String TYPE = "sal.estimate.issued";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Estimate"; }
        @Override public UUID aggregateId() { return estimateId; }
    }

    /**
     * sal.contract.ready-to-sign (v0.3) — 계약서 초안이 사내 결재(EXEC) 승인되어 고객 서명 대기로 전이될 때 1회(PTL 전자서명 요청).
     * contractDocId 는 계약서 문서(core.attachment) — 렌더 연동 전 scan_attachment_id 또는 null.
     */
    public record ContractReadyToSign(UUID contractId, UUID clientId, UUID contractDocId, BigDecimal amountKrw,
                                      LocalDate signDeadline, List<String> signers) implements DomainEvent {
        public static final String TYPE = "sal.contract.ready-to-sign";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Contract"; }
        @Override public UUID aggregateId() { return contractId; }
    }

    /** 견적 산출내역 1줄(실행예산 초안 원천) — LABOR: workItem × grade 인·일 / EXPENSE: 경비 항목 */
    public record EstimateLine(String lineType, String workItem, String grade, BigDecimal manDays, BigDecimal unitPrice,
                               BigDecimal quantity, BigDecimal amount) {}

    /**
     * sal.contract.signed — 고객 서명 기록 → core.project 생성(같은 트랜잭션) 후 1회. 구독: CST(실행예산 초안 TC-CST-01) · HRQ · FAC · PTL.
     * amount = 자사 지분 원화 금액(amount_krw × joint_share/100). estimateLines = 견적 산출내역(없으면 빈 목록).
     */
    public record ContractSigned(UUID contractId, UUID projectId, String projectNo, UUID clientId,
                                 List<UUID> facilityIds, UUID estimateId, BigDecimal amount, String currency,
                                 BigDecimal jointShare, LocalDate signedDate, LocalDate startDate, LocalDate endDate,
                                 List<EstimateLine> estimateLines) implements DomainEvent {
        public static final String TYPE = "sal.contract.signed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Contract"; }
        @Override public UUID aggregateId() { return contractId; }
    }
}
