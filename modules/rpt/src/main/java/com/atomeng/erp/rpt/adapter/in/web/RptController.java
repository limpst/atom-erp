package com.atomeng.erp.rpt.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.rpt.application.ComposeService;
import com.atomeng.erp.rpt.application.PublishService;
import com.atomeng.erp.rpt.application.RenderService;
import com.atomeng.erp.rpt.application.ReportService;
import com.atomeng.erp.rpt.application.AiDraftService;
import com.atomeng.erp.rpt.application.RptRevisionQueryService;
import com.atomeng.erp.rpt.application.RuleTableService;
import com.atomeng.erp.rpt.application.SectionService;
import com.atomeng.erp.rpt.application.SnapshotService;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/rpt (설계 06 B4). 권한: 8.2 표의 rpt:report 셀(create · read · update · approve) + 세부 권한(V6_0_1)
 * rpt:grade:decide(책임기술자) · rpt:delivery:confirm(PM · ADMIN) · rpt:rule:read. 프로젝트 범위(◐)는 서비스가 확인.
 * 섹션 저장 · 등급 확정은 ETag/If-Match(412). 발행본 삭제 API 는 두지 않는다(TC-RPT-007: DELETE → 405).
 */
@RestController
@RequestMapping("/api/v1/rpt")
class RptController {

    record CreateReportRequest(@NotNull UUID projectId, @NotNull UUID facilityId, @NotBlank String reportType,
                               @NotBlank @Size(max = 300) String title, @NotNull LocalDate inspectionDate,
                               @Pattern(regexp = "ko|en|vi") String locale, @Size(max = 30) String facilityClass,
                               LocalDate dueDate, UUID templateVersionId) {}

    record BuildingIn(@NotBlank @Size(max = 20) String code, @Size(max = 200) String name, UUID buildingId,
                      @NotNull @Positive BigDecimal grossAreaM2) {}

    record GroupIn(@NotBlank @Size(max = 20) String buildingCode, @NotBlank @Size(max = 10) String floorCode,
                   @NotBlank @Size(max = 20) String memberType, @Positive int memberCount) {}

    record DefectIn(@NotBlank @Size(max = 30) String id, @NotBlank String buildingCode, @NotBlank String floorCode,
                    String memberType, String memberId, @NotBlank String defectType, BigDecimal widthMm,
                    BigDecimal lengthM, BigDecimal areaM2, UUID equipmentId, LocalDate measuredOn,
                    List<List<BigDecimal>> polyline, @Size(max = 2000) String note) {}

    /** 결함 = fldRoundId(fld::api 조사 결과) 또는 요청 본문(합성 FLD export) 중 하나. 동 · 부재 그룹은 요청 본문 */
    record FreezeRequest(@Size(max = 300) String facilityName, @NotNull List<@Valid BuildingIn> buildings,
                         @NotNull List<@Valid GroupIn> memberGroups, List<@Valid DefectIn> defects, UUID fldRoundId) {

        SnapshotDocument document() {
            return new SnapshotDocument("1", null,
                    buildings.stream().map(b -> new SnapshotDocument.Building(b.code(), b.name(), b.buildingId(),
                            b.grossAreaM2())).toList(),
                    memberGroups.stream().map(g -> new SnapshotDocument.MemberGroup(g.buildingCode(), g.floorCode(),
                            g.memberType(), g.memberCount())).toList(),
                    defects == null ? List.of() : defects.stream().map(d -> new SnapshotDocument.Defect(d.id(),
                            d.buildingCode(), d.floorCode(), d.memberType(), d.memberId(), d.defectType(), d.widthMm(),
                            d.lengthM(), d.areaM2(), d.equipmentId(), d.measuredOn(), d.polyline(), d.note())).toList());
        }
    }

    record GradeDecisionRequest(@NotBlank @Pattern(regexp = "[A-E]") String finalGrade, @Size(max = 4000) String reason,
                                List<@NotBlank String> evidenceDefectIds) {}

    record BlockIn(UUID blockId, @Pattern(regexp = "HEADING|PARAGRAPH|TABLE|IMAGE") String kind,
                   @Size(max = 20000) String text) {}

    record SectionRequest(@NotNull List<@Valid BlockIn> blocks) {}

    record CorrectionRequest(@NotBlank @Size(min = 5, max = 2000) String changeNote, Boolean reuseSnapshot) {}

    record DeliveryRequest(OffsetDateTime deliveredAt, @NotBlank @Size(max = 100) String recipientName,
                           @Size(max = 200) String recipientOrg, UUID receiptAttachmentId,
                           @Size(max = 2000) String note) {}

    private final ReportService reports;
    private final SnapshotService snapshots;
    private final ComposeService compose;
    private final SectionService sections;
    private final RenderService render;
    private final PublishService publish;
    private final RuleTableService rules;
    private final RptRevisionQueryService query;
    private final AiDraftService aiDraft;

    RptController(ReportService reports, SnapshotService snapshots, ComposeService compose, SectionService sections,
                  RenderService render, PublishService publish, RuleTableService rules, RptRevisionQueryService query,
                  AiDraftService aiDraft) {
        this.reports = reports;
        this.snapshots = snapshots;
        this.compose = compose;
        this.sections = sections;
        this.render = render;
        this.publish = publish;
        this.rules = rules;
        this.query = query;
        this.aiDraft = aiDraft;
    }

    private static ResponseEntity<Map<String, Object>> withEtag(Map<String, Object> v) {
        Object ver = v.get("version");
        return ResponseEntity.ok().eTag(IfMatch.etagOf(ver == null ? 0L : ((Number) ver).longValue())).body(v);
    }

    // ------------------------------------------------------------------------------------- 보고서
    @PostMapping("/reports")
    @RequirePermission("rpt:report:create")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateReportRequest r) {
        Map<String, Object> v = reports.create(new ReportService.CreateReport(r.projectId(), r.facilityId(),
                r.reportType(), r.title(), r.inspectionDate(), r.locale(), r.facilityClass(), r.dueDate(),
                r.templateVersionId()));
        return ResponseEntity.created(URI.create("/api/v1/rpt/reports/" + v.get("id")))
                .eTag(IfMatch.etagOf(((Number) v.get("version")).longValue())).body(v);
    }

    @GetMapping("/reports")
    @RequirePermission("rpt:report:read")
    public List<Map<String, Object>> list(@RequestParam UUID projectId) {
        return reports.list(projectId);
    }

    @GetMapping("/reports/{id}")
    @RequirePermission("rpt:report:read")
    public ResponseEntity<Map<String, Object>> report(@PathVariable UUID id) {
        return withEtag(reports.get(id));
    }

    @PostMapping("/reports/{id}/snapshots")
    @RequirePermission("rpt:report:create")
    public ResponseEntity<Map<String, Object>> freeze(@PathVariable UUID id, @Valid @RequestBody FreezeRequest r) {
        return ResponseEntity.status(201).body(snapshots.freeze(id, r.document(), r.facilityName(), r.fldRoundId()));
    }

    @PostMapping("/reports/{id}/revisions")
    @RequirePermission("rpt:report:create")
    public ResponseEntity<Map<String, Object>> correction(@PathVariable UUID id, @Valid @RequestBody CorrectionRequest r) {
        Map<String, Object> v = reports.createCorrection(id, r.changeNote(), r.reuseSnapshot() == null || r.reuseSnapshot());
        return ResponseEntity.created(URI.create("/api/v1/rpt/revisions/" + v.get("id"))).body(v);
    }

    // ------------------------------------------------------------------------------------- 리비전
    @GetMapping("/revisions/{revId}")
    @RequirePermission("rpt:report:read")
    public ResponseEntity<Map<String, Object>> revision(@PathVariable UUID revId) {
        return withEtag(reports.revision(revId));
    }

    @GetMapping("/revisions/{revId}/validation-issues")
    @RequirePermission("rpt:report:read")
    public List<Map<String, Object>> issues(@PathVariable UUID revId) {
        return snapshots.issues(revId);
    }

    @PostMapping("/revisions/{revId}/compose")
    @RequirePermission("rpt:report:update")
    public Map<String, Object> compose(@PathVariable UUID revId) {
        return compose.compose(revId);
    }

    /** AI 소견 초안(aip::api REPORT_DRAFT 동기 Job) — 제안만 돌려주고 본문에는 반영하지 않는다 */
    @PostMapping("/revisions/{revId}/ai-draft")
    @RequirePermission("rpt:report:update")
    public Map<String, Object> aiDraft(@PathVariable UUID revId) {
        return aiDraft.draft(revId);
    }

    @GetMapping("/revisions/{revId}/grade")
    @RequirePermission("rpt:report:read")
    public Map<String, Object> grade(@PathVariable UUID revId) {
        return compose.grade(revId);
    }

    @PutMapping("/revisions/{revId}/grade/decision")
    @RequirePermission("rpt:grade:decide")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable UUID revId,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      @Valid @RequestBody GradeDecisionRequest r) {
        Map<String, Object> v = compose.decide(revId, IfMatch.require(ifMatch), r.finalGrade(), r.reason(),
                r.evidenceDefectIds());
        return ResponseEntity.ok().eTag(IfMatch.etagOf(((Number) v.get("revisionVersion")).longValue())).body(v);
    }

    @GetMapping("/revisions/{revId}/sections")
    @RequirePermission("rpt:report:read")
    public List<Map<String, Object>> sections(@PathVariable UUID revId) {
        return sections.list(revId);
    }

    @PutMapping("/revisions/{revId}/sections/{key}")
    @RequirePermission("rpt:report:update")
    public ResponseEntity<Map<String, Object>> saveSection(@PathVariable UUID revId, @PathVariable String key,
                                                           @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                           @Valid @RequestBody SectionRequest r) {
        return withEtag(sections.update(revId, key, IfMatch.require(ifMatch), r.blocks().stream()
                .map(b -> new SectionService.EditBlock(b.blockId(), b.kind() == null ? "PARAGRAPH" : b.kind(), b.text()))
                .toList()));
    }

    @GetMapping(value = "/revisions/{revId}/drawings/{building}/{floor}.dxf")
    @RequirePermission("rpt:report:read")
    public ResponseEntity<byte[]> dxf(@PathVariable UUID revId, @PathVariable String building, @PathVariable String floor) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/dxf"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + building + "-" + floor + ".dxf\"")
                .body(compose.drawingDxf(revId, building, floor));
    }

    @PostMapping("/revisions/{revId}/render")
    @RequirePermission("rpt:report:update")
    public Map<String, Object> render(@PathVariable UUID revId) {
        return render.render(revId);
    }

    @GetMapping("/revisions/{revId}/files/report.{ext}")
    @RequirePermission("rpt:report:read")
    public ResponseEntity<byte[]> file(@PathVariable UUID revId, @PathVariable String ext) {
        String kind = "pdf".equals(ext) ? "pdf" : "hwpx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("pdf".equals(kind) ? "application/pdf" : "application/hwp+zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"report." + kind + "\"")
                .body(render.file(revId, kind));
    }

    @GetMapping("/revisions/{revId}/anchors")
    @RequirePermission("rpt:report:read")
    public ResponseEntity<Object> anchors(@PathVariable UUID revId) {
        reports.revision(revId);   // 존재 · 범위 확인
        Object v = query.anchorsJson(revId);
        return v == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(v);
    }

    @GetMapping("/revisions/{revId}/section-editors")
    @RequirePermission("rpt:report:read")
    public Object sectionEditors(@PathVariable UUID revId) {
        reports.revision(revId);
        return query.sectionEditors(revId);
    }

    @PostMapping("/revisions/{revId}/review-request")
    @RequirePermission("rpt:report:update")
    public Map<String, Object> reviewRequest(@PathVariable UUID revId) {
        return publish.requestReview(revId);
    }

    @PostMapping("/revisions/{revId}/publish")
    @RequirePermission("rpt:report:approve")
    public Map<String, Object> publish(@PathVariable UUID revId) {
        return publish.publish(revId);
    }

    /** v0.2: 발주처 납품 확인(수령증 첨부) → DELIVERED + rpt.report.delivered */
    @PostMapping("/revisions/{revId}/delivery-confirmation")
    @RequirePermission("rpt:delivery:confirm")
    public ResponseEntity<Map<String, Object>> deliveryConfirmation(@PathVariable UUID revId,
                                                                    @Valid @RequestBody DeliveryRequest r) {
        return ResponseEntity.status(201).body(publish.confirmDelivery(revId, new PublishService.DeliveryConfirmation(
                r.deliveredAt() == null ? null : r.deliveredAt().toInstant(), r.recipientName(), r.recipientOrg(),
                r.receiptAttachmentId(), r.note())));
    }

    // ------------------------------------------------------------------------------------- rule table
    @GetMapping("/rule-sets")
    @RequirePermission("rpt:rule:read")
    public Map<String, Object> ruleSets(@RequestParam(defaultValue = "BLD_RC") String facilityClass,
                                        @RequestParam LocalDate asOf) {
        RuleTableService.Resolved r = rules.resolve(facilityClass, asOf);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("facilityClass", facilityClass);
        v.put("asOf", asOf.toString());
        v.put("pinned", r.tables().pinned());
        v.put("sets", r.sets());
        v.put("rules", r.json());
        return v;
    }
}
