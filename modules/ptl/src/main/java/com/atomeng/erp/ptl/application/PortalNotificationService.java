package com.atomeng.erp.ptl.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.authz.ProjectScopeResolver;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.fac.api.FacQueryApi;
import com.atomeng.erp.ptl.domain.InspectionSeverity;
import com.atomeng.erp.rpt.api.RptEvents;
import com.atomeng.erp.rpt.api.RptPublishedDocumentQuery;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.sal.api.SalQueryApi;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 구독 이벤트 → 고객 알림함 · 공개 프로젝션 (설계 09 B6 구독, 09-IMPL Step 5 · 6 일부 · 8 일부).
 * 이벤트 payload 의 대상(보고서 리비전 · 시설물 · 영업 기회 · 계약)을 공개 API 로 고객사에 연결한 뒤, 그 고객사의 RLS 컨텍스트로만
 * 쓴다(시스템 작업도 BYPASSRLS 없음). 고객사를 정할 수 없으면(소유 고객 미지정 시설물 등) 조용히 건너뛴다 —
 * 같은 이벤트를 받는 다른 모듈 리스너(SAL 등)와 한 트랜잭션이므로 예외로 함께 롤백시키지 않는다.
 * 알림은 (고객사, dedupe_key) 유일 — 재배달 · 재발행에도 1건. 채널(알림톡 · 메일) 발송은 후속(Step 8).
 */
@Service
public class PortalNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PortalNotificationService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JdbcTemplate jdbc;
    private final PortalRls rls;
    private final FacQueryApi fac;
    private final RptPublishedDocumentQuery rptDocs;
    private final SalQueryApi sal;
    private final ProjectScopeResolver projects;
    private final PortalAccessLog accessLog;
    private final ObjectMapper om;
    private final Clock clock;

    public PortalNotificationService(JdbcTemplate jdbc, PortalRls rls, FacQueryApi fac, RptPublishedDocumentQuery rptDocs,
                                     SalQueryApi sal, ProjectScopeResolver projects, PortalAccessLog accessLog,
                                     ObjectMapper om, Clock clock) {
        this.jdbc = jdbc;
        this.rls = rls;
        this.fac = fac;
        this.rptDocs = rptDocs;
        this.sal = sal;
        this.projects = projects;
        this.accessLog = accessLog;
        this.om = om;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ RPT

    /** rpt.report.delivered → 공개본 프로젝션(PUBLISHED) + 알림. 고객사 = 보고서 프로젝트의 발주 고객사 */
    @Transactional
    public void onReportDelivered(RptEvents.ReportDelivered e) {
        Optional<RptPublishedDocumentQuery.PublishedDocument> doc = rptDocs.publishedDocument(e.revisionId());
        if (doc.isEmpty()) {
            log.warn("PTL: 납품 이벤트의 발행본 없음 revision={}", e.revisionId());
            return;
        }
        RptPublishedDocumentQuery.PublishedDocument d = doc.get();
        Optional<UUID> customer = projects.clientOf(d.projectId());
        if (customer.isEmpty()) {
            log.warn("PTL: 보고서 프로젝트의 고객사 없음 project={}", d.projectId());
            return;
        }
        UUID cid = customer.get();
        Instant deliveredAt = e.deliveredAt() != null ? e.deliveredAt()
                : d.deliveredAt() != null ? d.deliveredAt() : clock.instant();
        rls.runAsCustomer(cid, () -> {
            int n = jdbc.update("""
                    INSERT INTO ptl.published_report(id, customer_id, report_id, revision_id, rev_no, project_id, facility_id,
                                                     report_type, title, inspection_date, safety_grade, pdf_sha256,
                                                     page_count, delivered_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (customer_id, revision_id) DO NOTHING""", UuidV7.next(), cid, d.reportId(),
                    d.revisionId(), d.revNo(), d.projectId(), d.facilityId(), d.reportType(), d.title(),
                    Date.valueOf(d.inspectionDate()), d.finalGrade(), d.pdfSha256(), d.pageCount(),
                    Timestamp.from(deliveredAt));
            if (n > 0) {
                notify(cid, "REPORT_DELIVERED", "보고서가 납품되었습니다",
                        "%s (Rev.%d)%s".formatted(d.title(), d.revNo(),
                                d.finalGrade() == null ? "" : " — 안전등급 " + d.finalGrade()),
                        "PublishedReport", d.revisionId(), RptEvents.ReportDelivered.TYPE + ":" + d.revisionId());
            }
        });
    }

    /** rpt.report.superseded → 기존 공개본 SUPERSEDED(배너 · 다운로드 차단, BR-PTL-012) + 정정 알림 */
    @Transactional
    public void onReportSuperseded(RptEvents.ReportSuperseded e) {
        Optional<UUID> customer = rptDocs.publishedDocument(e.oldRevId())
                .flatMap(d -> projects.clientOf(d.projectId()));
        if (customer.isEmpty()) {
            log.warn("PTL: 정정 이벤트의 고객사를 찾을 수 없음 oldRev={}", e.oldRevId());
            return;
        }
        UUID cid = customer.get();
        rls.runAsCustomer(cid, () -> {
            int n = jdbc.update("""
                    UPDATE ptl.published_report
                       SET status = 'SUPERSEDED', superseded_by_revision_id = ?, superseded_reason = ?, superseded_at = ?,
                           version = version + 1
                     WHERE revision_id = ? AND status = 'PUBLISHED'""", e.newRevId(), e.reason(),
                    Timestamp.from(clock.instant()), e.oldRevId());
            if (n > 0) {
                notify(cid, "REPORT_SUPERSEDED", "보고서 정정본이 발행되었습니다",
                        "Rev.%d 이 Rev.%d 로 대체되었습니다. 사유: %s".formatted(e.oldRevNo(), e.newRevNo(),
                                e.reason() == null ? "-" : e.reason()),
                        "PublishedReport", e.oldRevId(), RptEvents.ReportSuperseded.TYPE + ":" + e.oldRevId());
            }
        });
    }

    // ------------------------------------------------------------------ FAC

    @Transactional
    public void onGradeRecorded(FacEvents.GradeRecorded e) {
        ownerOf(e.buildingId()).ifPresent(b -> rls.runAsCustomer(b.ownerClientId(), () ->
                notify(b.ownerClientId(), "GRADE_RECORDED", "안전등급이 기록되었습니다",
                        "%s: %s등급(%s, %s)%s".formatted(b.name(), e.grade(), e.inspectionType(), e.performedOn(),
                                e.previousGrade() == null ? "" : " — 이전 " + e.previousGrade()),
                        "Building", e.buildingId(), FacEvents.GradeRecorded.TYPE + ":" + e.buildingId() + ":"
                                + e.performedOn() + ":" + e.grade() + ":" + e.reportId())));
    }

    @Transactional
    public void onDueApproaching(FacEvents.InspectionDueApproaching e) {
        ownerOf(e.buildingId()).ifPresent(b -> rls.runAsCustomer(b.ownerClientId(), () ->
                notify(b.ownerClientId(), "INSPECTION_DUE_APPROACHING", "법정점검 예정일이 다가옵니다",
                        "%s: %s 점검 예정일 %s (D-%d, %s)".formatted(b.name(), e.inspectionType(), e.dueDate(),
                                e.threshold(), InspectionSeverity.of(e.dueDate(), today())),
                        "Building", e.buildingId(), FacEvents.InspectionDueApproaching.TYPE + ":"
                                + (e.idempotencyKey() != null ? e.idempotencyKey()
                                : e.buildingId() + ":" + e.inspectionType() + ":" + e.dueDate() + ":" + e.threshold()))));
    }

    @Transactional
    public void onOverdue(FacEvents.InspectionOverdue e) {
        ownerOf(e.buildingId()).ifPresent(b -> rls.runAsCustomer(b.ownerClientId(), () ->
                notify(b.ownerClientId(), "INSPECTION_OVERDUE", "법정점검 예정일이 지났습니다",
                        "%s: %s 점검 예정일 %s 경과 — 법정 의무 미이행 위험".formatted(b.name(), e.inspectionType(),
                                e.dueDate()),
                        "Building", e.buildingId(), FacEvents.InspectionOverdue.TYPE + ":" + e.buildingId() + ":"
                                + e.inspectionType() + ":" + e.dueDate())));
    }

    // ------------------------------------------------------------------ SAL

    @Transactional
    public void onEstimateIssued(SalEvents.EstimateIssued e) {
        Optional<UUID> customer = sal.clientOfOpportunity(e.opportunityId());
        if (customer.isEmpty()) {
            log.info("PTL: 견적의 고객사 미지정(리드) — 포털 공유 생략 estimate={}", e.estimateId());
            return;
        }
        UUID cid = customer.get();
        rls.runAsCustomer(cid, () -> {
            int n = jdbc.update("""
                    INSERT INTO ptl.sales_document(id, customer_id, doc_kind, sal_ref_id, doc_no, version_no, amount_krw,
                                                   vat_krw, status)
                    VALUES (?, ?, 'ESTIMATE', ?, ?, ?, ?, ?, 'ISSUED')
                    ON CONFLICT (customer_id, doc_kind, sal_ref_id) DO NOTHING""", UuidV7.next(), cid, e.estimateId(),
                    e.estimateNo(), e.version(), e.offerAmount(), e.vat());
            if (n > 0) {
                notify(cid, "ESTIMATE_ISSUED", "견적서가 발행되었습니다",
                        "견적 %s (v%d) 제안 금액 %s원".formatted(e.estimateNo(), e.version(), e.offerAmount()),
                        "SalesDocument", e.estimateId(), SalEvents.EstimateIssued.TYPE + ":" + e.estimateId());
            }
        });
    }

    @Transactional
    public void onContractReadyToSign(SalEvents.ContractReadyToSign e) {
        if (e.clientId() == null) {
            return;
        }
        UUID cid = e.clientId();
        String signers;
        try {
            signers = om.writeValueAsString(e.signers() == null ? List.of() : e.signers());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        rls.runAsCustomer(cid, () -> {
            int n = jdbc.update("""
                    INSERT INTO ptl.sales_document(id, customer_id, doc_kind, sal_ref_id, amount_krw, sign_deadline, signers,
                                                   status)
                    VALUES (?, ?, 'CONTRACT', ?, ?, ?, ?::jsonb, 'AWAITING_SIGNATURE')
                    ON CONFLICT (customer_id, doc_kind, sal_ref_id) DO NOTHING""", UuidV7.next(), cid, e.contractId(),
                    e.amountKrw(), e.signDeadline() == null ? null : Date.valueOf(e.signDeadline()), signers);
            if (n > 0) {
                notify(cid, "CONTRACT_READY_TO_SIGN", "계약서 서명 요청이 도착했습니다",
                        "계약 금액 %s원, 서명 기한 %s".formatted(e.amountKrw(),
                                e.signDeadline() == null ? "-" : e.signDeadline()),
                        "SalesDocument", e.contractId(), SalEvents.ContractReadyToSign.TYPE + ":" + e.contractId());
            }
        });
    }

    // ------------------------------------------------------------------ 고객 조회

    @Transactional
    public List<Map<String, Object>> list(PortalAccess.Customer c, boolean unreadOnly) {
        return rls.asCustomer(c.customerId(), () -> jdbc.queryForList("""
                SELECT id, kind, category, title, body, ref_type AS "refType", ref_id AS "refId", created_at AS "createdAt",
                       read_at AS "readAt"
                  FROM ptl.notification WHERE (? = false OR read_at IS NULL)
                 ORDER BY created_at DESC, id DESC""", unreadOnly).stream().map(PtlRows::normalize).toList());
    }

    @Transactional
    public void markRead(PortalAccess.Customer c, UUID id) {
        boolean exists = rls.asCustomer(c.customerId(), () -> {
            jdbc.update("UPDATE ptl.notification SET read_at = ? WHERE id = ? AND read_at IS NULL",
                    Timestamp.from(clock.instant()), id);
            Integer k = jdbc.queryForObject("SELECT count(*) FROM ptl.notification WHERE id = ?", Integer.class, id);
            return k != null && k > 0;
        });
        if (!exists) {
            accessLog.denied(c, "Notification", id, "NOT_FOUND_OR_OTHER_CUSTOMER");
            throw new DomainException(PtlErrors.NOT_FOUND, "알림 없음: " + id);
        }
    }

    @Transactional
    public List<Map<String, Object>> salesDocuments(PortalAccess.Customer c) {
        return rls.asCustomer(c.customerId(), () -> jdbc.queryForList("""
                SELECT id, doc_kind AS "kind", sal_ref_id AS "salRefId", doc_no AS "docNo", version_no AS "versionNo",
                       amount_krw AS "amountKrw", vat_krw AS "vatKrw", sign_deadline AS "signDeadline",
                       signers::text AS "signers", status, created_at AS "createdAt"
                  FROM ptl.sales_document ORDER BY created_at DESC, id DESC""").stream().map(PtlRows::normalize).toList());
    }

    // ------------------------------------------------------------------ 내부

    /** RLS 컨텍스트 안에서 호출 */
    private void notify(UUID customerId, String kind, String title, String body, String refType, UUID refId,
                        String dedupeKey) {
        jdbc.update("""
                INSERT INTO ptl.notification(id, customer_id, kind, category, title, body, ref_type, ref_id, dedupe_key,
                                             created_at)
                VALUES (?, ?, ?, 'INFO', ?, ?, ?, ?, ?, ?)
                ON CONFLICT (customer_id, dedupe_key) DO NOTHING""", UuidV7.next(), customerId, kind, title, body,
                refType, refId, dedupeKey.length() > 200 ? dedupeKey.substring(0, 200) : dedupeKey,
                Timestamp.from(clock.instant()));
    }

    private Optional<FacQueryApi.BuildingSummary> ownerOf(UUID buildingId) {
        Optional<FacQueryApi.BuildingSummary> b = fac.building(buildingId).filter(x -> x.ownerClientId() != null);
        if (b.isEmpty()) {
            log.debug("PTL: 소유 고객사 없는 시설물 — 알림 생략 building={}", buildingId);
        }
        return b;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }
}
