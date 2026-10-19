package com.atomeng.erp.qar.adapter.in.web;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.qar.application.AiReviewService;
import com.atomeng.erp.qar.application.AnchorService;
import com.atomeng.erp.qar.application.AssignmentService;
import com.atomeng.erp.qar.application.AutoCheckService;
import com.atomeng.erp.qar.application.ChecklistService;
import com.atomeng.erp.qar.application.CommentService;
import com.atomeng.erp.qar.application.ReviewService;
import com.atomeng.erp.qar.application.WaiverService;
import com.atomeng.erp.qar.application.WorkflowEngine;
import com.atomeng.erp.qar.domain.Command;
import com.atomeng.erp.qar.domain.CommentStatus.ReplyAction;
import com.atomeng.erp.qar.domain.QarCodes;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/qar (설계 07 B4). 권한: 8.2 표 qar:review:read + 세부 권한(V0024) author · answer · thread · remark ·
 * decision · approval · waiver · assignment · suggestion · ai-job. 프로젝트 범위(◐)는 서비스가 검토 → 프로젝트로 확인.
 * 상태 전이 명령은 검토 ETag "v{n}" 를 If-Match 로 받는다(불일치 409 QAR_VERSION, TC-QAR-012).
 */
@RestController
@RequestMapping("/api/v1/qar")
class QarController {

    record CreateReviewRequest(@NotNull UUID revisionId) {}

    record DecisionRequest(@NotBlank String decision, @Size(max = 4000) String rejectSummary) {}

    record AnswerRequest(@NotBlank String stage, @NotBlank String answer, @Size(max = 2000) String note) {}

    record AssignRequest(@NotBlank String stepCode, @NotNull UUID assigneeId) {}

    record CommentRequest(@NotBlank String severity, @NotBlank String issueCode, @NotNull Map<String, Object> anchor,
                          @NotBlank @Size(max = 4000) String body) {}

    record ReplyRequest(@NotBlank String action, @Size(max = 4000) String body) {}

    record AiDecisionRequest(@NotBlank String decision, String reason, String severity) {}

    record WaiverRequest(@NotBlank @Size(max = 2000) String reason) {}

    private final ReviewService reviews;
    private final WorkflowEngine engine;
    private final AutoCheckService autochecks;
    private final ChecklistService checklist;
    private final AssignmentService assignments;
    private final CommentService comments;
    private final AnchorService anchors;
    private final AiReviewService ai;
    private final WaiverService waivers;

    QarController(ReviewService reviews, WorkflowEngine engine, AutoCheckService autochecks, ChecklistService checklist,
                  AssignmentService assignments, CommentService comments, AnchorService anchors, AiReviewService ai,
                  WaiverService waivers) {
        this.reviews = reviews;
        this.engine = engine;
        this.autochecks = autochecks;
        this.checklist = checklist;
        this.assignments = assignments;
        this.comments = comments;
        this.anchors = anchors;
        this.ai = ai;
        this.waivers = waivers;
    }

    private static ResponseEntity<Map<String, Object>> withEtag(Map<String, Object> v) {
        return ResponseEntity.ok().eTag(IfMatch.etagOf(((Number) v.get("version")).longValue())).body(v);
    }

    // ------------------------------------------------------------------ 검토 · 전이

    @PostMapping("/reviews")
    @RequirePermission("qar:author:create")
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateReviewRequest r) {
        Map<String, Object> v = reviews.create(r.revisionId());
        return ResponseEntity.created(URI.create("/api/v1/qar/reviews/" + v.get("id")))
                .eTag(IfMatch.etagOf(((Number) v.get("version")).longValue())).body(v);
    }

    @GetMapping("/reviews/{id}")
    @RequirePermission("qar:review:read")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        return withEtag(reviews.get(id));
    }

    @PostMapping("/reviews/{id}/autocheck-runs")
    @RequirePermission("qar:author:update")
    public ResponseEntity<Map<String, Object>> autocheck(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(autochecks.run(id));
    }

    @PostMapping("/reviews/{id}/submit")
    @RequirePermission("qar:author:update")
    public ResponseEntity<Map<String, Object>> submit(@PathVariable UUID id,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(engine.handle(id, Command.SUBMIT, IfMatch.require(ifMatch), WorkflowEngine.Input.NONE));
    }

    /** BR-QAR-010 AI_START — 제출 후 AI 검토 수동 실행 */
    @PostMapping("/reviews/{id}/ai-start")
    @RequirePermission("qar:ai-job:run")
    public ResponseEntity<Map<String, Object>> aiStart(@PathVariable UUID id,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(engine.handle(id, Command.AI_START, IfMatch.require(ifMatch),
                new WorkflowEngine.Input(null, "MANUAL", null)));
    }

    @PostMapping("/reviews/{id}/approve")
    @RequirePermission("qar:approval:approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable UUID id,
                                                       @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(engine.handle(id, Command.APPROVE, IfMatch.require(ifMatch), WorkflowEngine.Input.NONE));
    }

    /** RESUBMIT_PREP — 반려 후 수정본으로 새 라운드(미해결 코멘트 이월) */
    @PostMapping("/reviews/{id}/resubmit")
    @RequirePermission("qar:author:update")
    public ResponseEntity<Map<String, Object>> resubmit(@PathVariable UUID id,
                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(engine.handle(id, Command.RESUBMIT_PREP, IfMatch.require(ifMatch), WorkflowEngine.Input.NONE));
    }

    /** BR-QAR-010 REOPEN_FOR_CORRECTION — 정정본(RPT Rev.N) 재검토: APPROVED → SELF_CHECK */
    @PostMapping("/reviews/{id}/reopen-for-correction")
    @RequirePermission("qar:author:update")
    public ResponseEntity<Map<String, Object>> reopen(@PathVariable UUID id,
                                                      @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        return withEtag(engine.handle(id, Command.REOPEN_FOR_CORRECTION, IfMatch.require(ifMatch),
                WorkflowEngine.Input.NONE));
    }

    /** 단계 결정 PASS / REJECT — QA 단계는 QA 담당자, 승인 단계 REJECT 는 책임기술자 */
    @PostMapping("/rounds/{roundId}/decision")
    @RequirePermission("qar:decision:update")
    public ResponseEntity<Map<String, Object>> decision(@PathVariable UUID roundId, @Valid @RequestBody DecisionRequest r,
                                                        @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
        Command cmd = switch (r.decision()) {
            case "PASS" -> Command.PASS;
            case "REJECT" -> Command.REJECT;
            default -> throw new DomainException(QarCodes.INVALID, "decision 은 PASS · REJECT");
        };
        UUID reviewId = reviews.reviewIdOfRound(roundId);
        return withEtag(engine.handle(reviewId, cmd, IfMatch.require(ifMatch),
                new WorkflowEngine.Input(r.rejectSummary(), null, null)));
    }

    // ------------------------------------------------------------------ 자동검사 · 체크리스트 · 배정 · waiver

    @GetMapping("/rounds/{roundId}/autocheck-results")
    @RequirePermission("qar:review:read")
    public Map<String, Object> autocheckResults(@PathVariable UUID roundId) {
        return autochecks.latestResults(roundId);
    }

    @GetMapping("/rounds/{roundId}/checklist")
    @RequirePermission("qar:review:read")
    public Map<String, Object> checklist(@PathVariable UUID roundId) {
        return checklist.view(roundId);
    }

    @PutMapping("/rounds/{roundId}/checklist/{itemId}")
    @RequirePermission("qar:answer:update")
    public Map<String, Object> answer(@PathVariable UUID roundId, @PathVariable UUID itemId,
                                      @Valid @RequestBody AnswerRequest r) {
        ChecklistService.Stage stage;
        try {
            stage = ChecklistService.Stage.valueOf(r.stage());
        } catch (IllegalArgumentException e) {
            throw new DomainException(QarCodes.INVALID, "stage 는 SELF · QA");
        }
        return checklist.answer(roundId, itemId, stage, r.answer(), r.note());
    }

    @PostMapping("/rounds/{roundId}/assignments")
    @RequirePermission("qar:assignment:update")
    public ResponseEntity<Map<String, Object>> assign(@PathVariable UUID roundId, @Valid @RequestBody AssignRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assignments.manual(roundId, r.stepCode(), r.assigneeId()));
    }

    @PostMapping("/autocheck-results/{id}/waiver")
    @RequirePermission("qar:waiver:create")
    public Map<String, Object> waiver(@PathVariable UUID id, @Valid @RequestBody WaiverRequest r) {
        return waivers.addReason(id, r.reason());
    }

    // ------------------------------------------------------------------ 코멘트 · 앵커

    @PostMapping("/rounds/{roundId}/comments")
    @RequirePermission("qar:remark:create")
    public ResponseEntity<Map<String, Object>> comment(@PathVariable UUID roundId, @Valid @RequestBody CommentRequest r) {
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.create(roundId,
                new CommentService.NewComment(r.severity(), r.issueCode(), r.anchor(), r.body())));
    }

    @GetMapping("/rounds/{roundId}/comments")
    @RequirePermission("qar:review:read")
    public List<Map<String, Object>> comments(@PathVariable UUID roundId) {
        return comments.list(roundId);
    }

    @PostMapping("/comments/{id}/replies")
    @RequirePermission("qar:thread:create")
    public ResponseEntity<Map<String, Object>> reply(@PathVariable UUID id, @Valid @RequestBody ReplyRequest r) {
        ReplyAction a;
        try {
            a = ReplyAction.valueOf(r.action());
        } catch (IllegalArgumentException e) {
            throw new DomainException(QarCodes.INVALID, "action 은 ANSWER · REQUEST_RESOLUTION · REOPEN · CONFIRM · WITHDRAW");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(comments.reply(id, a, r.body()));
    }

    @GetMapping("/rounds/{roundId}/anchors")
    @RequirePermission("qar:review:read")
    public ResponseEntity<Map<String, Object>> anchors(@PathVariable UUID roundId,
                                                       @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false)
                                                       String ifNoneMatch) {
        AnchorService.Result r = anchors.anchors(roundId, ifNoneMatch);
        if (r.notModified()) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(r.etag()).build();
        }
        return ResponseEntity.ok().eTag(r.etag()).body(r.body());
    }

    // ------------------------------------------------------------------ AI 검토

    @GetMapping("/rounds/{roundId}/ai-findings")
    @RequirePermission("qar:suggestion:read")
    public List<Map<String, Object>> aiFindings(@PathVariable UUID roundId) {
        return ai.visibleFindings(roundId);
    }

    @PostMapping("/ai-findings/{id}/decision")
    @RequirePermission("qar:suggestion:decide")
    public Map<String, Object> aiDecision(@PathVariable UUID id, @Valid @RequestBody AiDecisionRequest r) {
        return ai.decide(id, r.decision(), r.reason(), r.severity());
    }

    /** 운영 수동 실행: 대기 AI 작업 처리 · deadline 지난 작업 TIMEOUT */
    @PostMapping("/ai-jobs:run")
    @RequirePermission("qar:ai-job:run")
    public Map<String, Object> runJobs() {
        AiReviewService.RunSummary s = ai.runPending(10);
        return Map.of("processed", s.processed(), "done", s.done(), "skipped", s.skipped(),
                "timedOut", ai.scanTimeouts());
    }
}
