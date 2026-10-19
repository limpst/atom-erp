package com.atomeng.erp.fld.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.fld.application.AiSuggestionService;
import com.atomeng.erp.fld.application.PhotoUploadService;
import com.atomeng.erp.fld.application.SurveyService;
import com.atomeng.erp.fld.application.SyncService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/fld (설계 05 B4). 권한: 8.2 표 fld:defect 셀(push = update, pull · 조회 = read) + 세부 권한(V5_0_1).
 * 프로젝트 범위(◐)는 서비스가 회차 → 프로젝트로 확인한다(FldAccess). 이어 올리기는 tus 1.0 core 헤더
 * (Upload-Offset · Upload-Length · Tus-Resumable)를 쓴다.
 */
@RestController
@RequestMapping("/api/v1/fld")
class FldController {

    static final String TUS_VERSION = "1.0.0";
    static final String OFFSET_OCTET_STREAM = "application/offset+octet-stream";

    record RoundRequest(@NotNull UUID projectId, @NotNull UUID buildingId, Integer roundNo, @NotBlank String surveyType,
                        Boolean evidenceMode, LocalDate startedOn) {}

    record ZoneRequest(@NotBlank @Size(max = 50) String zoneName, UUID floorNodeId, UUID sheetId, JsonNode zoneGeomNorm,
                       @NotNull UUID assigneeId) {}

    record OpIn(@NotNull UUID opId, @NotNull UUID defectId, @NotBlank String eventType, @NotBlank String hlc,
                Long baseSeq, @Size(max = 500) String lockOverrideReason, @NotNull JsonNode payload) {}

    record PushIn(@NotNull UUID deviceId, @NotNull UUID roundId, Long clientLastPulledSeq,
                  @NotNull @Size(min = 1) List<@Valid OpIn> ops) {}

    record ResolveIn(UUID opId, UUID deviceId, String hlc, @NotBlank String resolution, JsonNode merged) {}

    record UnlockIn(@NotBlank @Size(min = 5, max = 2000) String reason) {}

    record UploadIn(@NotNull UUID photoId, @NotNull UUID roundId, UUID defectId, UUID deviceId,
                    @NotBlank String sha256, @NotNull Long sizeBytes, String mimeType, String kind, Instant takenAt,
                    JsonNode exif) {}

    record AiAttachIn(@NotNull UUID defectId, UUID photoId, @NotBlank String modelVersion, UUID jobId,
                      BigDecimal widthMm, BigDecimal confidence, JsonNode result) {}

    private final SurveyService survey;
    private final SyncService sync;
    private final PhotoUploadService uploads;
    private final AiSuggestionService ai;

    FldController(SurveyService survey, SyncService sync, PhotoUploadService uploads, AiSuggestionService ai) {
        this.survey = survey;
        this.sync = sync;
        this.uploads = uploads;
        this.ai = ai;
    }

    // ------------------------------------------------------------------ 회차 · 구역 · 결함

    @PostMapping("/rounds")
    @RequirePermission("fld:round:create")
    public ResponseEntity<Map<String, Object>> createRound(@Valid @RequestBody RoundRequest r) {
        Map<String, Object> v = survey.createRound(new SurveyService.CreateRound(r.projectId(), r.buildingId(),
                r.roundNo(), r.surveyType(), r.evidenceMode(), r.startedOn()));
        return ResponseEntity.created(URI.create("/api/v1/fld/rounds/" + v.get("id"))).body(v);
    }

    @GetMapping("/rounds/{id}")
    @RequirePermission("fld:round:read")
    public Map<String, Object> round(@PathVariable UUID id) {
        return survey.round(id);
    }

    @PostMapping("/rounds/{id}/zones")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fld:zone:assign")
    public Map<String, Object> addZone(@PathVariable UUID id, @Valid @RequestBody ZoneRequest z) {
        return survey.addZone(id, new SurveyService.CreateZone(z.zoneName(), z.floorNodeId(), z.sheetId(),
                z.zoneGeomNorm(), z.assigneeId()));
    }

    @GetMapping("/rounds/{id}/defects")
    @RequirePermission("fld:defect:read")
    public List<Map<String, Object>> defects(@PathVariable UUID id, @RequestParam(required = false) UUID zoneId) {
        return survey.defects(id, zoneId);
    }

    @GetMapping("/defects/{id}")
    @RequirePermission("fld:defect:read")
    public Map<String, Object> defect(@PathVariable UUID id) {
        return survey.defect(id);
    }

    @GetMapping("/defects/{id}/history")
    @RequirePermission("fld:defect:read")
    public Map<String, Object> history(@PathVariable UUID id, @RequestParam(required = false) Instant asOf) {
        return survey.history(id, asOf);
    }

    @PostMapping("/rounds/{id}/projection:rebuild")
    @RequirePermission("fld:projection:rebuild")
    public Map<String, Object> rebuild(@PathVariable UUID id) {
        return survey.rebuild(id);
    }

    // ------------------------------------------------------------------ 구역 잠금

    @GetMapping("/zones/{id}/lock-check")
    @RequirePermission("fld:zone:lock")
    public Map<String, Object> lockCheck(@PathVariable UUID id) {
        var issues = survey.lockIssues(id);
        return Map.of("zoneId", id, "ready", issues.isEmpty(), "issues", issues);
    }

    @PostMapping("/zones/{id}/lock")
    @RequirePermission("fld:zone:lock")
    public Map<String, Object> lock(@PathVariable UUID id) {
        return survey.lock(id);
    }

    @PostMapping("/zones/{id}/unlock")
    @RequirePermission("fld:zone:lock")
    public Map<String, Object> unlock(@PathVariable UUID id, @Valid @RequestBody UnlockIn r) {
        return survey.unlock(id, r.reason());
    }

    // ------------------------------------------------------------------ 동기화

    @PostMapping("/sync/push")
    @RequirePermission("fld:defect:update")
    public Map<String, Object> push(@Valid @RequestBody PushIn r) {
        return sync.push(new SyncService.PushRequest(r.deviceId(), r.roundId(),
                r.clientLastPulledSeq() == null ? 0 : r.clientLastPulledSeq(),
                r.ops().stream().map(o -> new SyncService.PushOp(o.opId(), o.defectId(), o.eventType(), o.hlc(),
                        o.baseSeq(), o.lockOverrideReason(), o.payload())).toList()));
    }

    @GetMapping("/sync/pull")
    @RequirePermission("fld:defect:read")
    public Map<String, Object> pull(@RequestParam UUID roundId, @RequestParam(required = false) UUID deviceId,
                                    @RequestParam(required = false) Long sinceSeq,
                                    @RequestParam(required = false) String changeToken,
                                    @RequestParam(required = false) Integer limit) {
        return sync.pull(roundId, deviceId, sinceSeq, changeToken, limit);
    }

    @GetMapping("/sync/conflicts")
    @RequirePermission("fld:defect:read")
    public List<Map<String, Object>> conflicts(@RequestParam UUID roundId, @RequestParam(required = false) UUID zoneId) {
        survey.round(roundId);   // 범위 확인
        return sync.openConflicts(roundId, zoneId);
    }

    @GetMapping("/sync/conflicts/{id}")
    @RequirePermission("fld:defect:read")
    public Map<String, Object> conflict(@PathVariable UUID id) {
        Map<String, Object> c = sync.conflictView(id);
        survey.round((UUID) c.get("roundId"));
        return c;
    }

    @PostMapping("/sync/conflicts/{id}/resolve")
    @RequirePermission("fld:conflict:resolve")
    public Map<String, Object> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveIn r) {
        return sync.resolve(id, new SyncService.Resolve(r.opId(), r.deviceId(), r.hlc(), r.resolution(), r.merged()));
    }

    // ------------------------------------------------------------------ 사진 이어 올리기 (tus 1.0 core 의미)

    private static HttpHeaders tus(PhotoUploadService.UploadState s) {
        HttpHeaders h = new HttpHeaders();
        h.set("Tus-Resumable", TUS_VERSION);
        h.set("Upload-Offset", Long.toString(s.offset()));
        h.set("Upload-Length", Long.toString(s.length()));
        h.setCacheControl("no-store");
        return h;
    }

    private static Map<String, Object> view(PhotoUploadService.UploadState s) {
        return Map.of("uploadId", s.uploadId(), "photoId", s.photoId(), "offset", s.offset(), "length", s.length(),
                "status", s.status(), "photoState", s.photoState());
    }

    @PostMapping("/uploads")
    @RequirePermission("fld:photo:upload")
    public ResponseEntity<Map<String, Object>> createUpload(@Valid @RequestBody UploadIn r) {
        var s = uploads.create(new PhotoUploadService.CreateUpload(r.photoId(), r.roundId(), r.defectId(), r.deviceId(),
                r.sha256(), r.sizeBytes(), r.mimeType(), r.kind(), r.takenAt(), r.exif()));
        HttpHeaders h = tus(s);
        h.setLocation(URI.create("/api/v1/fld/uploads/" + s.uploadId()));
        h.set("Fld-Max-Chunk-Bytes", Integer.toString(uploads.maxChunkBytes()));
        return ResponseEntity.status(s.created() ? HttpStatus.CREATED : HttpStatus.OK).headers(h).body(view(s));
    }

    @RequestMapping(value = "/uploads/{id}", method = RequestMethod.HEAD)
    @RequirePermission("fld:photo:upload")
    public ResponseEntity<Void> head(@PathVariable UUID id) {
        return ResponseEntity.ok().headers(tus(uploads.head(id))).build();
    }

    @GetMapping("/uploads/{id}")
    @RequirePermission("fld:photo:upload")
    public ResponseEntity<Map<String, Object>> upload(@PathVariable UUID id) {
        var s = uploads.head(id);
        return ResponseEntity.ok().headers(tus(s)).body(view(s));
    }

    @PatchMapping(value = "/uploads/{id}", consumes = OFFSET_OCTET_STREAM)
    @RequirePermission("fld:photo:upload")
    public ResponseEntity<Map<String, Object>> patch(@PathVariable UUID id,
                                                     @RequestHeader("Upload-Offset") long offset,
                                                     @RequestHeader(value = "Upload-Checksum", required = false)
                                                     String checksum,
                                                     @RequestBody byte[] chunk) {
        var s = uploads.append(id, offset, chunk, checksum);
        return ResponseEntity.ok().headers(tus(s)).body(view(s));
    }

    @GetMapping("/photos/{id}/verify")
    @RequirePermission("fld:photo:verify")
    public Map<String, Object> verifyPhoto(@PathVariable UUID id) {
        return uploads.verify(id);
    }

    @GetMapping("/rounds/{id}/evidence/verify")
    @RequirePermission("fld:photo:verify")
    public Map<String, Object> verifyChain(@PathVariable UUID id) {
        return uploads.verifyChain(id);
    }

    // ------------------------------------------------------------------ AI 제안

    @PostMapping("/photos/{id}/ai:analyze")
    @RequirePermission("fld:ai-suggestion:request")
    public Map<String, Object> analyze(@PathVariable UUID id) {
        return ai.analyze(id);
    }

    /** AIP 워커 · 내부 콜백(B5.3 /internal/fld/ai-suggestions) — 운영은 서비스 계정(mTLS) 권한 */
    @PostMapping("/internal/ai-suggestions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fld:ai-suggestion:attach")
    public Map<String, Object> attachSuggestion(@Valid @RequestBody AiAttachIn r) {
        return ai.attach(new AiSuggestionService.Attach(r.defectId(), r.photoId(), r.modelVersion(), r.jobId(),
                r.widthMm(), r.confidence(), r.result()));
    }
}
