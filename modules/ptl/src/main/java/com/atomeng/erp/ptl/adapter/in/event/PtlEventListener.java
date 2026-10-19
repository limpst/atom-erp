package com.atomeng.erp.ptl.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.ptl.application.PortalNotificationService;
import com.atomeng.erp.rpt.api.RptEvents;
import com.atomeng.erp.sal.api.SalEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * PTL 구독 (설계 09 v0.3 B6) — 생산 모듈 api 의 이벤트 레코드로만 연결, core.processed_event 로 소비자 이름별 멱등.
 * <ul>
 *   <li>{@code rpt.report.delivered} → 납품 보고서 공개본 + 알림 · {@code rpt.report.superseded} → 대체 표시(배너) + 정정 알림</li>
 *   <li>{@code fac.grade.recorded} · {@code fac.inspection.due-approaching} · {@code fac.inspection.overdue} → 알림</li>
 *   <li>{@code sal.estimate.issued} · {@code sal.contract.ready-to-sign} → 공유 영업 문서 + 알림</li>
 * </ul>
 * {@code qar.review.approved} 는 구독하지 않는다 — RPT 가 QAR 승인 없이는 발행하지 않으므로 납품 = 3조건 중 2개 충족(README 5장 26번).
 */
@Component
public class PtlEventListener {

    public static final String DELIVERED_CONSUMER = "ptl.report-delivered";
    public static final String SUPERSEDED_CONSUMER = "ptl.report-superseded";
    public static final String GRADE_CONSUMER = "ptl.grade-recorded";
    public static final String DUE_CONSUMER = "ptl.inspection-due";
    public static final String OVERDUE_CONSUMER = "ptl.inspection-overdue";
    public static final String ESTIMATE_CONSUMER = "ptl.estimate-issued";
    public static final String SIGN_CONSUMER = "ptl.contract-ready-to-sign";

    private final PortalNotificationService portal;
    private final ObjectMapper om;

    public PtlEventListener(PortalNotificationService portal, ObjectMapper om) {
        this.portal = portal;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'rpt.report.delivered'")
    @IdempotentListener(consumer = DELIVERED_CONSUMER)
    public void onReportDelivered(EventEnvelope env) {
        portal.onReportDelivered(om.convertValue(env.payload(), RptEvents.ReportDelivered.class));
    }

    @EventListener(condition = "#env.eventType == 'rpt.report.superseded'")
    @IdempotentListener(consumer = SUPERSEDED_CONSUMER)
    public void onReportSuperseded(EventEnvelope env) {
        portal.onReportSuperseded(om.convertValue(env.payload(), RptEvents.ReportSuperseded.class));
    }

    @EventListener(condition = "#env.eventType == 'fac.grade.recorded'")
    @IdempotentListener(consumer = GRADE_CONSUMER)
    public void onGradeRecorded(EventEnvelope env) {
        portal.onGradeRecorded(om.convertValue(env.payload(), FacEvents.GradeRecorded.class));
    }

    @EventListener(condition = "#env.eventType == 'fac.inspection.due-approaching'")
    @IdempotentListener(consumer = DUE_CONSUMER)
    public void onDueApproaching(EventEnvelope env) {
        portal.onDueApproaching(om.convertValue(env.payload(), FacEvents.InspectionDueApproaching.class));
    }

    @EventListener(condition = "#env.eventType == 'fac.inspection.overdue'")
    @IdempotentListener(consumer = OVERDUE_CONSUMER)
    public void onOverdue(EventEnvelope env) {
        portal.onOverdue(om.convertValue(env.payload(), FacEvents.InspectionOverdue.class));
    }

    @EventListener(condition = "#env.eventType == 'sal.estimate.issued'")
    @IdempotentListener(consumer = ESTIMATE_CONSUMER)
    public void onEstimateIssued(EventEnvelope env) {
        portal.onEstimateIssued(om.convertValue(env.payload(), SalEvents.EstimateIssued.class));
    }

    @EventListener(condition = "#env.eventType == 'sal.contract.ready-to-sign'")
    @IdempotentListener(consumer = SIGN_CONSUMER)
    public void onContractReadyToSign(EventEnvelope env) {
        portal.onContractReadyToSign(om.convertValue(env.payload(), SalEvents.ContractReadyToSign.class));
    }
}
