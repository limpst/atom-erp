package com.atomeng.erp.rpt.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.rpt.adapter.out.persistence.RptJdbc;
import com.atomeng.erp.rpt.api.RptEvents;
import com.atomeng.erp.rpt.api.RptSubscriptionContracts.QarReviewDecided;
import com.atomeng.erp.rpt.application.RevisionSupport.Ctx;
import com.atomeng.erp.rpt.domain.ReportStatus;
import com.atomeng.erp.rpt.domain.ReportType;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토 요청 · QAR 결정 반영 · 발행 · 납품 확인 (FR-RPT-015 · 016, BR-RPT-005 · 007 · 008 · 011, v0.2 납품 확인).
 * <ul>
 *   <li>검토 요청: COMPOSING → IN_REVIEW (등급 확정 + 최신 렌더 필수)</li>
 *   <li>qar.review.approved → APPROVED / rejected → COMPOSING (QAR 스텁 계약)</li>
 *   <li>발행: APPROVED → SIGNING → PUBLISHED. 전자서명(PAdES)은 후속 단계라 서명 계획이 비어 있는 SIGNING 을 즉시 통과.
 *       최종 PDF · HWPX · anchors.json · 스냅샷을 reports(WORM)에 저장, SHA-256 기록, 이전 발행본은 SUPERSEDED.
 *       {@code rpt.report.published}(FAC 등급 · 차기 점검일 갱신) · {@code rpt.report.superseded}</li>
 *   <li>납품 확인: PUBLISHED → DELIVERED + rpt.delivery_confirmation(append-only) + {@code rpt.report.delivered}</li>
 * </ul>
 */
@Service
public class PublishService {

    private static final Logger log = LoggerFactory.getLogger(PublishService.class);

    public record DeliveryConfirmation(Instant deliveredAt, String recipientName, String recipientOrg,
                                       UUID receiptAttachmentId, String note) {}

    private final RevisionSupport sup;
    private final RptJdbc rpt;
    private final RenderService render;
    private final SnapshotService snapshots;
    private final RptStorage storage;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final ReportService reports;
    private final FacilityOwnershipGuard ownership;

    public PublishService(RevisionSupport sup, RenderService render, SnapshotService snapshots, RptStorage storage,
                          OutboxPublisher outbox, AuditLog audit, ReportService reports,
                          FacilityOwnershipGuard ownership) {
        this.ownership = ownership;
        this.sup = sup;
        this.rpt = sup.jdbc();
        this.render = render;
        this.snapshots = snapshots;
        this.storage = storage;
        this.outbox = outbox;
        this.audit = audit;
        this.reports = reports;
    }

    @Transactional
    public Map<String, Object> requestReview(UUID revId) {
        Ctx c = sup.lock(revId, "rpt:report:update");
        sup.requireStatus(c, ReportStatus.COMPOSING);
        requireGradeDecided(revId);
        render.requireFreshRender(c);
        sup.transition(c, ReportStatus.IN_REVIEW);
        return reports.revisionView(revId);
    }

    /** QAR 결정(스텁 계약) — 상태가 IN_REVIEW 가 아니면 무시(재전달 · 순서 뒤바뀜 대비) */
    @Transactional
    public void onReviewDecided(QarReviewDecided e, boolean approved) {
        Map<String, Object> rev = rpt.row("report_revision", e.revisionId(), true).orElse(null);
        if (rev == null) {
            log.warn("QAR 결정의 리비전 없음 — 무시 revision={}", e.revisionId());
            return;
        }
        Ctx c = new Ctx(rev, rpt.lock("report", RptJdbc.uuid(rev, "report_id")));
        if (c.status() != ReportStatus.IN_REVIEW) {
            log.info("QAR 결정 무시 — 리비전 상태 {} revision={}", c.status(), e.revisionId());
            return;
        }
        if (approved) {
            rpt.jdbc().update("UPDATE rpt.report_revision SET review_id = ?, approved_at = now() WHERE id = ?",
                    e.reviewId(), e.revisionId());
            sup.transition(c, ReportStatus.APPROVED);
        } else {
            sup.transition(c, ReportStatus.COMPOSING);
        }
    }

    @Transactional
    public Map<String, Object> publish(UUID revId) {
        Ctx c = sup.lock(revId, "rpt:report:approve");
        sup.requireStatus(c, ReportStatus.APPROVED);
        Map<String, Object> grade = requireGradeDecided(revId);
        render.requireFreshRender(c);
        Map<String, Object> job = render.latestDone(revId).orElseThrow();
        // 서명 단계(BR-RPT-007): 전자서명 기능 전 — 서명 계획 0건이라 SIGNING 을 바로 통과
        sup.transition(c, ReportStatus.SIGNING);
        Ctx signing = sup.lock(revId, "rpt:report:approve");

        byte[] pdf = storage.get(RptStorage.RENDERS, RptJdbc.str(job, "pdf_object_key"));
        byte[] hwpx = storage.get(RptStorage.RENDERS, RptJdbc.str(job, "hwpx_object_key"));
        byte[] snapshotJson = storage.get(RptStorage.SNAPSHOTS, RptJdbc.str(rpt.get("snapshot", c.snapshotId()),
                "object_key"));
        String anchors = rpt.jdbc().queryForObject("SELECT anchors::text FROM rpt.revision_anchor WHERE revision_id = ?",
                String.class, revId);
        String pdfSha = Hashing.sha256Hex(pdf);
        String hwpxSha = Hashing.sha256Hex(hwpx);
        String base = c.reportId() + "/rev-" + c.revNo() + "/";
        storage.putWorm(base + pdfSha + ".pdf", pdf, "application/pdf");
        storage.putWorm(base + hwpxSha + ".hwpx", hwpx, "application/hwp+zip");
        storage.putWorm(base + "anchors.json", anchors.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json");
        storage.putWorm(base + "snapshot.json", snapshotJson, "application/json");
        Instant now = sup.clock().instant();
        UUID by = sup.actorId();
        rpt.jdbc().update("""
                UPDATE rpt.report_revision SET status = 'PUBLISHED', pdf_object_key = ?, pdf_sha256 = ?, hwpx_object_key = ?,
                       hwpx_sha256 = ?, anchors_object_key = ?, page_count = ?, published_at = ?, published_by = ?,
                       version = version + 1, updated_at = now(), updated_by = ?
                 WHERE id = ?""", base + pdfSha + ".pdf", pdfSha, base + hwpxSha + ".hwpx", hwpxSha, base + "anchors.json",
                job.get("page_count"), RptJdbc.ts(now), by, by, revId);
        signing.status().transitionTo(ReportStatus.PUBLISHED);   // A6 검사(SIGNING → PUBLISHED)

        // 정정본이면 이전 발행본 SUPERSEDED (트리거 허용 경로)
        List<Map<String, Object>> prev = rpt.jdbc().queryForList("""
                SELECT id, rev_no, status FROM rpt.report_revision
                 WHERE report_id = ? AND id <> ? AND status IN ('PUBLISHED','DELIVERED') FOR UPDATE""", c.reportId(), revId);
        for (Map<String, Object> p : prev) {
            ReportStatus.valueOf(RptJdbc.str(p, "status")).transitionTo(ReportStatus.SUPERSEDED);
            rpt.jdbc().update("""
                    UPDATE rpt.report_revision SET status = 'SUPERSEDED', superseded_by = ?, superseded_at = now(),
                           version = version + 1, updated_at = now(), updated_by = ? WHERE id = ?""",
                    revId, by, p.get("id"));
            outbox.publish(new RptEvents.ReportSuperseded(c.reportId(), RptJdbc.uuid(p, "id"), revId,
                    RptJdbc.integer(p, "rev_no"), c.revNo(), RptJdbc.str(c.rev(), "change_note")));
        }
        sup.syncReport(c.reportId());

        ReportType type = ReportType.valueOf(RptJdbc.str(c.report(), "report_type"));
        SnapshotDocument doc = snapshots.document(c.snapshotId());
        Set<UUID> buildings = new LinkedHashSet<>();
        doc.buildings().stream().map(SnapshotDocument.Building::buildingId).filter(java.util.Objects::nonNull)
                .forEach(buildings::add);
        // RPT-02b: 스냅샷에 FAC 동이 없으면 보고서 시설물로 대신하되, 프로젝트에 연결된(발주처 소유) FAC 시설물일 때만
        UUID facility = RptJdbc.uuid(c.report(), "facility_id");
        if (buildings.isEmpty() && ownership.isLinkedToProject(c.projectId(), facility)) {
            buildings.add(facility);
        }
        String finalGrade = RptJdbc.str(grade, "final_grade");
        String inspectionType = type.facInspectionType().orElse(null);
        outbox.publish(new RptEvents.ReportPublished(c.reportId(), revId, c.revNo(), c.projectId(), type.name(),
                List.copyOf(new ArrayList<>(buildings)), inspectionType == null ? null : finalGrade, inspectionType,
                RptJdbc.localDate(c.report().get("inspection_date")), by, pdfSha));
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("pdfSha256", pdfSha);
        after.put("hwpxSha256", hwpxSha);
        after.put("key", base + pdfSha + ".pdf");
        after.put("finalGrade", finalGrade);
        audit.append("RPT", AuditLog.Action.APPROVE, "ReportRevision", revId, Map.of("status", "APPROVED"), after);
        return reports.revisionView(revId);
    }

    @Transactional
    public Map<String, Object> confirmDelivery(UUID revId, DeliveryConfirmation d) {
        Ctx c = sup.lock(revId, "rpt:delivery:confirm");
        if (c.status() == ReportStatus.DELIVERED) {
            throw new DomainException(RptCodes.ALREADY_DELIVERED, "이미 납품 확인된 리비전입니다");
        }
        sup.requireStatus(c, ReportStatus.PUBLISHED);
        Instant at = d.deliveredAt() == null ? sup.clock().instant() : d.deliveredAt();
        UUID id = UuidV7.next();
        rpt.jdbc().update("""
                INSERT INTO rpt.delivery_confirmation(id, revision_id, delivered_at, recipient_name, recipient_org,
                                                      receipt_attachment_id, note, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, revId, RptJdbc.ts(at), d.recipientName(), d.recipientOrg(),
                d.receiptAttachmentId(), d.note(), sup.actorId());
        rpt.jdbc().update("UPDATE rpt.report_revision SET delivered_at = ? WHERE id = ?", RptJdbc.ts(at), revId);
        sup.transition(c, ReportStatus.DELIVERED);
        outbox.publish(new RptEvents.ReportDelivered(c.reportId(), revId, c.revNo(), c.projectId(), at, null,
                d.receiptAttachmentId()));
        audit.append("RPT", AuditLog.Action.UPDATE, "ReportRevision", revId, Map.of("status", "PUBLISHED"),
                Map.of("status", "DELIVERED", "confirmationId", id));
        Map<String, Object> v = new LinkedHashMap<>(reports.revisionView(revId));
        v.put("confirmationId", id);
        return v;
    }

    private Map<String, Object> requireGradeDecided(UUID revId) {
        List<Map<String, Object>> g = rpt.jdbc().queryForList("SELECT * FROM rpt.grade_result WHERE revision_id = ?", revId);
        if (g.isEmpty() || g.get(0).get("final_grade") == null) {
            throw new DomainException(RptCodes.GRADE_NOT_DECIDED, "책임기술자 등급 확정 전입니다(BR-RPT-005)");
        }
        return g.get(0);
    }
}
