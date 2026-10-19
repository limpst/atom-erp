package com.atomeng.erp.aip.adapter.in.web;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.application.AipAccess;
import com.atomeng.erp.aip.application.AsyncJobProcessor;
import com.atomeng.erp.aip.application.InferenceAuditService;
import com.atomeng.erp.aip.application.JobService;
import com.atomeng.erp.aip.application.ModelRegistryService;
import com.atomeng.erp.aip.application.SuggestionService;
import com.atomeng.erp.aip.domain.AipCodes;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * /api/v1/aip (설계 11 B4.1). 권한: 8.2 표 aip:inference-job · model · audit-log + 세부 권한(V0032) suggestion · adjudication ·
 * dataset · consent · promotion · job. 프로젝트 범위(◐)는 서비스(AipAccess)가 Job · 제안 → 프로젝트로 확인한다.
 * SSE(진행률 스트림)는 이번 단계에서 JSON 이벤트 목록으로 대신한다(LISTEN aip_job_progress 트리거는 준비됨).
 */
@RestController
@RequestMapping("/api/v1/aip")
class AipController {

    record InputRequest(UUID attachmentId, @Size(min = 64, max = 64) String sha256) {}

    record CreateJobRequest(@NotBlank String serviceCode, Integer priority, UUID projectId, List<@Valid InputRequest> inputs,
                            JsonNode params, Integer deadlineSeconds, Integer maxAttempts) {}

    record DecisionRequest(@NotBlank String decision, String reasonCode, Map<String, Object> modified) {}

    record ConsentRequest(@NotNull Boolean trainingConsent, String source) {}

    record PrivacyRequest(@NotNull Boolean blurDone, Integer facesBlurred, Integer platesBlurred) {}

    record AdjudicationRequest(@NotNull Boolean approved, JsonNode finalResult, BigDecimal agreementIou, String feedbackCard) {}

    record CommitRequest(@NotBlank String tag) {}

    record PromoteRequest(@NotBlank String targetStage, String serviceCode, Integer trafficPct) {}

    record ApprovalRequest(@NotBlank String role) {}

    private final JobService jobs;
    private final AsyncJobProcessor processor;
    private final SuggestionService suggestions;
    private final ModelRegistryService registry;
    private final InferenceAuditService audit;
    private final AipAccess access;

    AipController(JobService jobs, AsyncJobProcessor processor, SuggestionService suggestions, ModelRegistryService registry,
                  InferenceAuditService audit, AipAccess access) {
        this.jobs = jobs;
        this.processor = processor;
        this.suggestions = suggestions;
        this.registry = registry;
        this.audit = audit;
        this.access = access;
    }

    // ------------------------------------------------------------------ Job

    @PostMapping("/jobs")
    @RequirePermission("aip:inference-job:create")
    ResponseEntity<Map<String, Object>> create(@RequestHeader(value = "Idempotency-Key", required = false) String key,
                                               @Valid @RequestBody CreateJobRequest req) {
        AipServiceCode code;
        try {
            code = AipServiceCode.valueOf(req.serviceCode());
        } catch (IllegalArgumentException e) {
            throw new DomainException(AipCodes.INVALID, "알 수 없는 serviceCode: " + req.serviceCode());
        }
        access.requireProject("aip:inference-job:create", req.projectId());
        List<JobService.InputRef> inputs = new ArrayList<>();
        if (req.inputs() != null) {
            req.inputs().forEach(i -> inputs.add(new JobService.InputRef(i.attachmentId(), i.sha256())));
        }
        JobService.Created c = jobs.create(new JobService.CreateCommand(code, req.priority(), req.projectId(), inputs,
                req.params(), key, req.deadlineSeconds() == null ? null : Duration.ofSeconds(req.deadlineSeconds()),
                req.maxAttempts(), null, "ASYNC"), true);
        UUID id = AipJdbc.uuid(c.job(), "id");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("status", AipJdbc.str(c.job(), "status"));
        body.put("queuePosition", "QUEUED".equals(AipJdbc.str(c.job(), "status")) ? jobs.queuePosition(id) : 0);
        body.put("replayed", c.replayed());
        return ResponseEntity.accepted().location(URI.create("/api/v1/aip/jobs/" + id)).body(body);
    }

    @GetMapping("/jobs/{id}")
    @RequirePermission("aip:inference-job:read")
    Map<String, Object> job(@PathVariable UUID id) {
        Map<String, Object> j = jobs.get(id);
        access.requireProject("aip:inference-job:read", AipJdbc.uuid(j, "project_id"), AipJdbc.uuid(j, "requested_by"));
        return view(j);
    }

    @GetMapping("/jobs/{id}/events")
    @RequirePermission("aip:inference-job:read")
    List<Map<String, Object>> events(@PathVariable UUID id) {
        Map<String, Object> j = jobs.get(id);
        access.requireProject("aip:inference-job:read", AipJdbc.uuid(j, "project_id"), AipJdbc.uuid(j, "requested_by"));
        return AipJdbc.cleanAll(jobs.events(id));
    }

    @DeleteMapping("/jobs/{id}")
    @RequirePermission("aip:inference-job:create")
    Map<String, Object> cancel(@PathVariable UUID id) {
        Map<String, Object> j = jobs.get(id);
        access.requireProject("aip:inference-job:create", AipJdbc.uuid(j, "project_id"), AipJdbc.uuid(j, "requested_by"));
        return view(jobs.cancel(id));
    }

    /** 운영 · 시험용 수동 처리(스케줄러 1틱과 같다) */
    @PostMapping("/jobs:run-next")
    @RequirePermission("aip:job:operate")
    Map<String, Object> runNext() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("processed", processor.runNext().orElse(null));
        return out;
    }

    @PostMapping("/jobs:reap")
    @RequirePermission("aip:job:operate")
    JobService.ReapSummary reap() {
        return jobs.reap();
    }

    static Map<String, Object> view(Map<String, Object> j) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", j.get("id"));
        v.put("serviceCode", j.get("service_code"));
        v.put("mode", j.get("mode"));
        v.put("priority", j.get("priority"));
        v.put("status", j.get("status"));
        v.put("projectId", j.get("project_id"));
        v.put("attempts", j.get("attempts"));
        v.put("maxAttempts", j.get("max_attempts"));
        v.put("progressPct", j.get("progress_pct"));
        v.put("routeAlias", j.get("route_alias"));
        v.put("modelVersionId", j.get("model_version_id"));
        v.put("inputSha256", j.get("input_sha256"));
        v.put("deadlineAt", j.get("deadline_at"));
        v.put("cancelRequested", j.get("cancel_requested"));
        v.put("result", j.get("result_ref") == null ? null : AipJdbc.tree(j.get("result_ref")));
        v.put("error", j.get("error") == null ? null : AipJdbc.tree(j.get("error")));
        v.put("queuedAt", j.get("queued_at"));
        v.put("startedAt", j.get("started_at"));
        v.put("finishedAt", j.get("finished_at"));
        return v;
    }

    // ------------------------------------------------------------------ 제안 · 피드백

    @GetMapping("/photos/{attachmentId}/suggestions")
    @RequirePermission("aip:suggestion:read")
    List<Map<String, Object>> suggestionsOf(@PathVariable UUID attachmentId) {
        List<Map<String, Object>> rows = suggestions.byPhoto(attachmentId);
        rows.forEach(r -> access.requireProject("aip:suggestion:read", AipJdbc.uuid(r, "project_id"),
                AipJdbc.uuid(jobs.get(AipJdbc.uuid(r, "job_id")), "requested_by")));
        return AipJdbc.cleanAll(rows);
    }

    @PostMapping("/suggestions/{id}/decision")
    @RequirePermission("aip:suggestion:update")
    Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest req) {
        Map<String, Object> s = suggestions.get(id);
        access.requireProject("aip:suggestion:update", AipJdbc.uuid(s, "project_id"),
                AipJdbc.uuid(jobs.get(AipJdbc.uuid(s, "job_id")), "requested_by"));
        return AipJdbc.clean(suggestions.decide(id, new SuggestionService.Decision(req.decision(), req.reasonCode(), req.modified())));
    }

    @PutMapping("/projects/{projectId}/consent")
    @RequirePermission("aip:consent:update")
    Map<String, Object> consent(@PathVariable UUID projectId, @Valid @RequestBody ConsentRequest req) {
        return AipJdbc.clean(suggestions.setConsent(projectId, req.trainingConsent(), req.source()));
    }

    @PutMapping("/attachments/{attachmentId}/privacy")
    @RequirePermission("aip:consent:update")
    Map<String, Object> privacy(@PathVariable UUID attachmentId, @Valid @RequestBody PrivacyRequest req) {
        return AipJdbc.clean(suggestions.setPrivacy(attachmentId, req.blurDone(),
                req.facesBlurred() == null ? 0 : req.facesBlurred(), req.platesBlurred() == null ? 0 : req.platesBlurred()));
    }

    @PostMapping("/label-tasks/{id}/adjudication")
    @RequirePermission("aip:adjudication:create")
    Map<String, Object> adjudicate(@PathVariable UUID id, @Valid @RequestBody AdjudicationRequest req) {
        return AipJdbc.clean(suggestions.adjudicate(id, req.approved(), req.finalResult(), req.agreementIou(), req.feedbackCard()));
    }

    @PostMapping("/datasets/{name}/commits")
    @RequirePermission("aip:dataset:create")
    Map<String, Object> commit(@PathVariable String name, @Valid @RequestBody CommitRequest req) {
        return AipJdbc.clean(suggestions.commitDataset(name, req.tag()));
    }

    // ------------------------------------------------------------------ 레지스트리 · 감사

    @GetMapping("/models")
    @RequirePermission("aip:inference-job:read")
    List<Map<String, Object>> models() {
        return AipJdbc.cleanAll(registry.models());
    }

    @PostMapping("/models/{id}/approval")
    @RequirePermission("aip:promotion:approve")
    Map<String, Object> approve(@PathVariable UUID id, @Valid @RequestBody ApprovalRequest req) {
        return AipJdbc.clean(registry.approve(id, req.role()));
    }

    @PostMapping("/models/{id}/promote")
    @RequirePermission("aip:model:deploy")
    Map<String, Object> promote(@PathVariable UUID id, @Valid @RequestBody PromoteRequest req) {
        return AipJdbc.clean(registry.promote(id, req.targetStage(), req.serviceCode(), req.trafficPct()));
    }

    @GetMapping("/audit")
    @RequirePermission("aip:audit-log:read")
    List<Map<String, Object>> audit(@RequestParam(required = false) UUID jobId, @RequestParam(required = false) UUID projectId,
                                    @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return AipJdbc.cleanAll(audit.query(jobId, projectId, from, to));
    }
}
