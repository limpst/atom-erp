package com.atomeng.erp.dem.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.dem.application.AdjacentSurveyService;
import com.atomeng.erp.dem.application.DailyLogService;
import com.atomeng.erp.dem.application.DemProjectService;
import com.atomeng.erp.dem.application.EvidenceService;
import com.atomeng.erp.dem.application.StructuralService;
import com.atomeng.erp.dem.application.SupervisorService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/dem (설계 10 B4). POST 는 공통 Idempotency-Key 필터(현장 앱 X-Client-Id=field-app 이면 필수), 감리일지는
 * 오프라인 동기화 멱등 키 clientOpId(본문) + 선택 If-Match. 감리일지 작성은 8.2 표 셀 권한 dem:supervision-log:*,
 * 그 외는 DEM 세부 권한(V0029).
 */
@RestController
@RequestMapping("/api/v1/dem")
class DemController {

    static final String PACKAGE_TYPES = "DEM_PLAN|DEM_SAFETY|DEM_TEMP|DEM_SUPERVISION|STR_DESIGN|STR_REVIEW|STR_SUPERVISION|VE";

    record PackageRequest(@NotNull @Pattern(regexp = PACKAGE_TYPES) String packageType, UUID facilityId,
                          @Pattern(regexp = "ko|en|vi|ru") String locale, @Size(max = 40) String designCode,
                          @Pattern(regexp = "[A-Z]{3}") String currency, BigDecimal fxRateSnapshot, LocalDate plannedStart,
                          LocalDate plannedEnd) {}

    record ProjectRequest(@NotNull UUID projectId, UUID buildingId, @NotEmpty List<@Valid PackageRequest> packages) {}

    record TransitionRequest(@NotBlank String to) {}

    record CompleteRequest(LocalDate completedOn, UUID submissionId) {}

    record SubmissionRequest(@NotBlank @Size(max = 100) String authorityName,
                             @NotNull @Pattern(regexp = "PLAN_PERMIT|START_NOTICE|SUPERVISION_REPORT|COMPLETION") String submissionType,
                             Instant submittedAt, @Size(max = 60) String receiptNo,
                             @Pattern(regexp = "SUBMITTED|SUPPLEMENT_REQUESTED|ACCEPTED") String status,
                             List<UUID> bundleAttachmentIds) {}

    record AssignRequest(@NotNull UUID employeeId, @NotNull @Pattern(regexp = "CHIEF|ASSISTANT") String role,
                         @NotNull LocalDate validFrom, LocalDate validTo, @Size(max = 1000) String reason,
                         UUID basisAttachmentId) {}

    record ReleaseRequest(@NotNull LocalDate releasedOn, @Size(max = 60) String reason) {}

    record ChecklistItemRequest(@NotNull UUID templateItemId, @NotNull @Pattern(regexp = "PASS|FAIL|NA") String result,
                                @Pattern(regexp = "LOW|MEDIUM|HIGH|CRITICAL") String severity,
                                @Size(max = 2000) String note, List<UUID> photoIds) {}

    record DailyLogRequest(@NotNull UUID clientOpId,
                           @NotNull @Pattern(regexp = "TEMPORARY|ASBESTOS_CHECK|UPPER_DEMOLITION|LOWER_DEMOLITION|DEBRIS_OUT") String phaseCode,
                           @Min(0) @Max(100) Integer progressPct, @Min(0) Integer workersCount, Map<String, Object> weather,
                           List<Map<String, Object>> equipment, List<@Valid ChecklistItemRequest> checklist,
                           @Size(max = 4000) String remarks, Instant deviceSignedAt, boolean submit) {}

    record AmendRequest(@NotBlank String fieldPath, Object newValue, @NotBlank @Size(max = 2000) String reason) {}

    record AmendmentDecisionRequest(@Size(max = 2000) String note) {}

    record PhotoRequest(@NotNull UUID attachmentId, @NotNull @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256,
                        @NotNull UUID workPackageId, Instant capturedAt,
                        @DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
                        @DecimalMin("-180") @DecimalMax("180") BigDecimal lng, @Size(max = 80) String deviceId) {}

    record DocumentRequest(@NotBlank @Size(max = 40) String docNo, @NotBlank @Size(max = 200) String title,
                           @Size(max = 20) String discipline, boolean isInput) {}

    record RevisionRequest(@NotNull UUID fileAttachmentId, @Size(max = 2000) String changeSummary, UUID modelVersionId,
                           List<UUID> dependsOn) {}

    record ReplaceFileRequest(@NotNull UUID fileAttachmentId, @Size(max = 2000) String changeSummary) {}

    record ModelVersionRequest(@NotBlank @Size(max = 40) String software, @NotNull UUID fileAttachmentId,
                               Map<String, Object> loadCombinations, @Size(max = 40) String designCode,
                               @NotBlank String changeSummary) {}

    record TransmittalRequest(@NotNull UUID workPackageId,
                              @NotNull @Pattern(regexp = "FOR_REVIEW|FOR_APPROVAL|FOR_CONSTRUCTION") String purpose,
                              @NotEmpty List<UUID> revisionIds, UUID recipientClientId,
                              @Size(max = 200) String recipientName) {}

    record CommentRequest(@Size(max = 20) String commentNo,
                          @Pattern(regexp = "INTERNAL|CLIENT|AUTHORITY|EXTERNAL_DESIGNER") String source,
                          @Pattern(regexp = "MINOR|MAJOR|CRITICAL") String severity, @NotBlank String body,
                          Map<String, Object> markup) {}

    record ReplyRequest(@NotBlank String body,
                        @NotNull @Pattern(regexp = "RESPONDED|ACCEPTED|REJECTED|CLOSED") String resultingStatus) {}

    record DefectRequest(@NotBlank @Size(max = 30) String defectNo, @NotBlank @Size(max = 10) String floor,
                         @NotBlank @Size(max = 60) String memberRef, @NotBlank @Size(max = 30) String defectType,
                         @NotNull BigDecimal x, @NotNull BigDecimal y, BigDecimal crackWidthMm, BigDecimal lengthMm,
                         UUID photoId) {}

    record SurveyRequest(UUID adjacentBuildingId, @NotBlank @Size(max = 200) String adjacentName,
                         @NotNull @Pattern(regexp = "PRE|POST") String phase, @NotNull LocalDate surveyedOn,
                         @Size(max = 40) String drawingVersion, List<@Valid DefectRequest> defects) {}

    record ComparisonRequest(@NotNull UUID preSurveyId, @NotNull UUID postSurveyId) {}

    private final DemProjectService projects;
    private final SupervisorService supervisors;
    private final DailyLogService logs;
    private final EvidenceService evidence;
    private final StructuralService structural;
    private final AdjacentSurveyService surveys;

    DemController(DemProjectService projects, SupervisorService supervisors, DailyLogService logs,
                  EvidenceService evidence, StructuralService structural, AdjacentSurveyService surveys) {
        this.projects = projects;
        this.supervisors = supervisors;
        this.logs = logs;
        this.evidence = evidence;
        this.structural = structural;
        this.surveys = surveys;
    }

    // ------------------------------------------------------------------ 프로젝트 · 업무 패키지 (FR-DEM-001, A6.1)

    @PostMapping("/projects")
    @RequirePermission(value = "dem:project:create", scopeCheckedInService = true)
    public ResponseEntity<Map<String, Object>> createProject(@Valid @RequestBody ProjectRequest r) {
        var body = projects.create(new DemProjectService.Create(r.projectId(), r.buildingId(), r.packages().stream()
                .map(p -> new DemProjectService.PackageSpec(p.packageType(), p.facilityId(), p.locale(), p.designCode(),
                        p.currency(), p.fxRateSnapshot(), p.plannedStart(), p.plannedEnd())).toList()));
        return ResponseEntity.created(URI.create("/api/v1/dem/projects/" + r.projectId()))
                .eTag(IfMatch.etagOf(((Number) body.get("version")).longValue())).body(body);
    }

    @GetMapping("/projects/{projectId}")
    @RequirePermission(value = "dem:project:read", projectIdParam = "projectId")
    public ResponseEntity<Map<String, Object>> project(@PathVariable UUID projectId) {
        var body = projects.view(projectId);
        return ResponseEntity.ok().eTag(IfMatch.etagOf(((Number) body.get("version")).longValue())).body(body);
    }

    @PostMapping("/projects/{projectId}:transition")
    @RequirePermission(value = "dem:project:transition", projectIdParam = "projectId")
    public ResponseEntity<Map<String, Object>> transition(@PathVariable UUID projectId,
                                                          @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                          @Valid @RequestBody TransitionRequest r) {
        var body = projects.transition(projectId, r.to(), ifMatch == null ? null : IfMatch.require(ifMatch));
        return ResponseEntity.ok().eTag(IfMatch.etagOf(((Number) body.get("version")).longValue())).body(body);
    }

    /** 해체 완료 신고 → COMPLETING + dem.demolition.completed (FAC 멸실 처리) */
    @PostMapping("/projects/{projectId}:complete-demolition")
    @RequirePermission(value = "dem:project:transition", projectIdParam = "projectId")
    public Map<String, Object> completeDemolition(@PathVariable UUID projectId,
                                                  @RequestBody(required = false) CompleteRequest r) {
        return projects.completeDemolition(projectId, r == null ? null : r.completedOn(), r == null ? null : r.submissionId());
    }

    @PostMapping("/projects/{projectId}:close")
    @RequirePermission(value = "dem:project:transition", projectIdParam = "projectId")
    public Map<String, Object> close(@PathVariable UUID projectId) {
        return projects.close(projectId);
    }

    @PostMapping("/projects/{projectId}/submissions")
    @RequirePermission(value = "dem:submission:write", projectIdParam = "projectId")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> submission(@PathVariable UUID projectId, @Valid @RequestBody SubmissionRequest r) {
        return projects.submit(projectId, new DemProjectService.Submission(r.authorityName(), r.submissionType(),
                r.submittedAt(), r.receiptNo(), r.status(), r.bundleAttachmentIds()));
    }

    // ------------------------------------------------------------------ 감리자 (FR-DEM-002)

    @PostMapping("/work-packages/{workPackageId}/supervisors")
    @RequirePermission(value = "dem:supervisor:assign", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> assign(@PathVariable UUID workPackageId, @Valid @RequestBody AssignRequest r) {
        return supervisors.assign(workPackageId, new SupervisorService.Assign(r.employeeId(), r.role(), r.validFrom(),
                r.validTo(), r.reason(), r.basisAttachmentId()));
    }

    @GetMapping("/work-packages/{workPackageId}/supervisors")
    @RequirePermission(value = "dem:supervisor:read", scopeCheckedInService = true)
    public Map<String, Object> supervisors(@PathVariable UUID workPackageId) {
        return supervisors.listWithAlerts(workPackageId);
    }

    @PostMapping("/supervisors/{assignmentId}:release")
    @RequirePermission(value = "dem:supervisor:assign", scopeCheckedInService = true)
    public Map<String, Object> release(@PathVariable UUID assignmentId, @Valid @RequestBody ReleaseRequest r) {
        return supervisors.release(assignmentId, r.releasedOn(), r.reason());
    }

    // ------------------------------------------------------------------ 점검표 · 감리일지 (FR-DEM-006·007)

    @GetMapping("/checklist-templates/{phaseCode}")
    @RequirePermission("dem:checklist:read")
    public Map<String, Object> template(@PathVariable String phaseCode,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return logs.resolveTemplate(phaseCode, date);
    }

    /** 감리일지 업서트(오프라인 동기화 멱등: clientOpId) — 200, 잠금 이후 도착이면 202 + amendmentPending */
    @PutMapping("/daily-logs/{workPackageId}/{date}")
    @RequirePermission(value = "dem:supervision-log:create", scopeCheckedInService = true)
    public ResponseEntity<Map<String, Object>> upsertLog(@PathVariable UUID workPackageId,
                                                         @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                         @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                         @Valid @RequestBody DailyLogRequest r) {
        var result = logs.upsert(workPackageId, date, new DailyLogService.Upsert(r.clientOpId(), r.phaseCode(),
                r.progressPct(), r.workersCount(), r.weather(), r.equipment(), r.checklist() == null ? null
                : r.checklist().stream().map(c -> new DailyLogService.ChecklistInput(c.templateItemId(), c.result(),
                        c.severity(), c.note(), c.photoIds())).toList(), r.remarks(), r.deviceSignedAt(), r.submit()),
                ifMatch == null ? null : IfMatch.require(ifMatch));
        var b = ResponseEntity.status(result.httpStatus()).eTag(IfMatch.etagOf(result.version()));
        if (Boolean.TRUE.equals(result.body().get("replayed"))) {
            b.header("Idempotent-Replayed", "true");
        }
        return b.body(result.body());
    }

    @GetMapping("/daily-logs/{logId}")
    @RequirePermission(value = "dem:supervision-log:read", scopeCheckedInService = true)
    public ResponseEntity<Map<String, Object>> log(@PathVariable UUID logId) {
        var body = logs.read(logId);
        return ResponseEntity.ok().eTag(IfMatch.etagOf((Long) body.get("version"))).body(body);
    }

    @PostMapping("/daily-logs/{logId}/amendments")
    @RequirePermission(value = "dem:log-amendment:create", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> amend(@PathVariable UUID logId, @Valid @RequestBody AmendRequest r) {
        return logs.amend(logId, r.fieldPath(), r.newValue(), r.reason());
    }

    /** DEM-03(b) 정정 요청(PENDING) 승인 — 요청자 · 일지 작성자 아닌 승인 권한자(PM 투입 · EXEC) */
    @PostMapping("/daily-logs/{logId}/amendments/{amendmentId}:approve")
    @RequirePermission(value = "dem:supervision-log:approve", scopeCheckedInService = true)
    public Map<String, Object> approveAmendment(@PathVariable UUID logId, @PathVariable UUID amendmentId,
                                                @Valid @RequestBody(required = false) AmendmentDecisionRequest r) {
        return logs.decideAmendment(logId, amendmentId, true, r == null ? null : r.note());
    }

    /** DEM-03(b) 정정 요청 반려(사유 note 필수) */
    @PostMapping("/daily-logs/{logId}/amendments/{amendmentId}:reject")
    @RequirePermission(value = "dem:supervision-log:approve", scopeCheckedInService = true)
    public Map<String, Object> rejectAmendment(@PathVariable UUID logId, @PathVariable UUID amendmentId,
                                               @Valid @RequestBody(required = false) AmendmentDecisionRequest r) {
        return logs.decideAmendment(logId, amendmentId, false, r == null ? null : r.note());
    }

    /** 운영 · 시험용 즉시 잠금(스케줄러와 같은 로직) */
    @PostMapping("/daily-logs:lock-due")
    @RequirePermission("dem:log-lock:run")
    public Map<String, Object> lockDue() {
        return Map.of("locked", logs.lockDue());
    }

    // ------------------------------------------------------------------ 증거 사진 (FR-DEM-008)

    @PostMapping("/evidence/photos")
    @RequirePermission(value = "dem:evidence:register", scopeCheckedInService = true)
    public ResponseEntity<Map<String, Object>> registerPhoto(@Valid @RequestBody PhotoRequest r) {
        var res = evidence.register(new EvidenceService.Register(r.attachmentId(), r.sha256(), r.workPackageId(),
                r.capturedAt(), r.lat(), r.lng(), r.deviceId()));
        return ResponseEntity.status(res.created() ? HttpStatus.CREATED : HttpStatus.OK).body(res.body());
    }

    @GetMapping("/evidence/photos/{photoId}/verify")
    @RequirePermission(value = "dem:evidence:verify", scopeCheckedInService = true)
    public Map<String, Object> verifyPhoto(@PathVariable UUID photoId) {
        return evidence.verify(photoId);
    }

    @GetMapping("/work-packages/{workPackageId}/evidence")
    @RequirePermission(value = "dem:evidence:verify", scopeCheckedInService = true)
    public List<Map<String, Object>> evidenceManifest(@PathVariable UUID workPackageId) {
        return evidence.manifest(workPackageId);
    }

    // ------------------------------------------------------------------ 도서 · 리비전 · Transmittal · 의견 (FR-DEM-012~014)

    @PostMapping("/work-packages/{workPackageId}/documents")
    @RequirePermission(value = "dem:revision:write", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> document(@PathVariable UUID workPackageId, @Valid @RequestBody DocumentRequest r) {
        return structural.createDocument(workPackageId, r.docNo(), r.title(), r.discipline(), r.isInput());
    }

    @GetMapping("/documents/{documentId}")
    @RequirePermission(value = "dem:revision:read", scopeCheckedInService = true)
    public Map<String, Object> getDocument(@PathVariable UUID documentId) {
        return structural.readDocument(documentId);
    }

    @PostMapping("/documents/{documentId}/revisions")
    @RequirePermission(value = "dem:revision:write", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> revision(@PathVariable UUID documentId, @Valid @RequestBody RevisionRequest r) {
        return structural.createRevision(documentId, new StructuralService.NewRevision(r.fileAttachmentId(),
                r.changeSummary(), r.modelVersionId(), r.dependsOn()));
    }

    @PostMapping("/revisions/{revisionId}:replace-file")
    @RequirePermission(value = "dem:revision:write", scopeCheckedInService = true)
    public Map<String, Object> replaceFile(@PathVariable UUID revisionId, @Valid @RequestBody ReplaceFileRequest r) {
        return structural.replaceFile(revisionId, r.fileAttachmentId(), r.changeSummary());
    }

    @PostMapping("/revisions/{revisionId}:request-review")
    @RequirePermission(value = "dem:revision:write", scopeCheckedInService = true)
    public Map<String, Object> requestReview(@PathVariable UUID revisionId) {
        return structural.move(revisionId, "request-review");
    }

    @PostMapping("/revisions/{revisionId}:approve")
    @RequirePermission(value = "dem:revision:approve", scopeCheckedInService = true)
    public Map<String, Object> approve(@PathVariable UUID revisionId) {
        return structural.move(revisionId, "approve");
    }

    @PostMapping("/revisions/{revisionId}:reject")
    @RequirePermission(value = "dem:revision:approve", scopeCheckedInService = true)
    public Map<String, Object> reject(@PathVariable UUID revisionId) {
        return structural.move(revisionId, "reject");
    }

    @PostMapping("/work-packages/{workPackageId}/model-versions")
    @RequirePermission(value = "dem:revision:write", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> modelVersion(@PathVariable UUID workPackageId, @Valid @RequestBody ModelVersionRequest r) {
        return structural.createModelVersion(workPackageId, new StructuralService.ModelVersion(r.software(),
                r.fileAttachmentId(), r.loadCombinations(), r.designCode(), r.changeSummary()));
    }

    @PostMapping("/transmittals")
    @RequirePermission(value = "dem:transmittal:issue", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> transmittal(@Valid @RequestBody TransmittalRequest r) {
        return structural.issue(new StructuralService.Issue(r.workPackageId(), r.purpose(), r.revisionIds(),
                r.recipientClientId(), r.recipientName()));
    }

    @PostMapping("/revisions/{revisionId}/comments")
    @RequirePermission(value = "dem:comment:write", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> comment(@PathVariable UUID revisionId, @Valid @RequestBody CommentRequest r) {
        return structural.addComment(revisionId, new StructuralService.NewComment(r.commentNo(), r.source(),
                r.severity(), r.body(), r.markup()));
    }

    @GetMapping("/revisions/{revisionId}/comments")
    @RequirePermission(value = "dem:revision:read", scopeCheckedInService = true)
    public List<Map<String, Object>> comments(@PathVariable UUID revisionId) {
        return structural.comments(revisionId);
    }

    @PostMapping("/comments/{commentId}/replies")
    @RequirePermission(value = "dem:comment:write", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> reply(@PathVariable UUID commentId, @Valid @RequestBody ReplyRequest r) {
        return structural.reply(commentId, r.body(), r.resultingStatus());
    }

    // ------------------------------------------------------------------ 인접건축물 사전 · 사후 (FR-DEM-018)

    @PostMapping("/projects/{projectId}/adjacent-surveys")
    @RequirePermission(value = "dem:survey:write", projectIdParam = "projectId")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> survey(@PathVariable UUID projectId, @Valid @RequestBody SurveyRequest r) {
        return surveys.createSurvey(projectId, new AdjacentSurveyService.Survey(r.adjacentBuildingId(), r.adjacentName(),
                r.phase(), r.surveyedOn(), r.drawingVersion(), r.defects() == null ? null : r.defects().stream()
                .map(d -> new AdjacentSurveyService.DefectInput(d.defectNo(), d.floor(), d.memberRef(), d.defectType(),
                        d.x(), d.y(), d.crackWidthMm(), d.lengthMm(), d.photoId())).toList()));
    }

    @PostMapping("/projects/{projectId}/adjacent-comparisons")
    @RequirePermission(value = "dem:survey:write", projectIdParam = "projectId")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> compare(@PathVariable UUID projectId, @Valid @RequestBody ComparisonRequest r) {
        return surveys.compare(projectId, r.preSurveyId(), r.postSurveyId());
    }
}
