package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.api.QarEvents;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.atomeng.erp.qar.domain.Command;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.ReviewStatus;
import com.atomeng.erp.qar.domain.ai.CitationGuard;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext;
import com.atomeng.erp.rpt.api.RptRevisionQuery.AnchorSet;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Block;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Defect;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Section;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AI 검토 (FR-QAR-010 · 011, BR-QAR-005, B7.4, 07-IMPL Step 9).
 * <ol>
 *   <li>AI_START 효과가 qar.ai_review_job(QUEUED, deadline_at = 요청 + 10분)을 만든다</li>
 *   <li>워커({@link #runPending}): 작업 RUNNING → 리비전 블록 · 근거 facts 로 Atom AI Core 호출(트랜잭션 밖) →
 *       지적마다 CitationGuard(인용 블록이 리비전 · anchors.json 에 있고 인용문 · 값이 실제와 같아야 통과) → ai_finding 저장
 *       (거부분도 guard_passed=false 로 저장, 비노출) → DONE + AI_DONE 명령</li>
 *   <li>장애 · 비활성 → FAILED, 응답 시간 초과 → TIMEOUT, 그리고 AI_TIMEOUT 명령(AI 단계 스킵 · "AI 검토 미수행" 배지)</li>
 *   <li>스케줄러({@link #scanTimeouts}): deadline_at 지난 QUEUED · RUNNING 작업 → TIMEOUT + AI_TIMEOUT (TC-QAR-015)</li>
 *   <li>QA 채택 → 코멘트 생성(앵커 = report 인용), 기각 → 사유 필수, {@code qar.ai-finding.decided}</li>
 * </ol>
 */
@Service
public class AiReviewService {

    private static final Logger log = LoggerFactory.getLogger(AiReviewService.class);
    private static final Map<String, String> ISSUE_BY_CATEGORY = Map.of("NUM_MISMATCH", "QI-NUM", "MISSING_EVIDENCE",
            "QI-EVIDENCE", "LOGIC_GAP", "QI-LOGIC", "RISKY_PHRASE", "QI-STYLE", "MISSING_SECTION", "QI-SCOPE");
    private static final Set<String> DISMISS_REASONS = Set.of("FALSE_POSITIVE", "NOT_IMPORTANT", "ALREADY_FIXED");
    static final List<String> CHECKS = List.of("NUM_MISMATCH", "MISSING_EVIDENCE", "LOGIC_GAP", "RISKY_PHRASE",
            "MISSING_SECTION");

    private final ReviewSupport sup;
    private final QarJdbc qar;
    private final WorkflowEngine engine;
    private final AtomAiCorePort ai;
    private final CommentService comments;
    private final ChecklistService checklist;
    private final OutboxPublisher outbox;
    private final TransactionTemplate tx;

    public AiReviewService(ReviewSupport sup, WorkflowEngine engine, AtomAiCorePort ai, CommentService comments,
                           ChecklistService checklist, OutboxPublisher outbox, PlatformTransactionManager tm) {
        this.sup = sup;
        this.qar = sup.jdbc();
        this.engine = engine;
        this.ai = ai;
        this.comments = comments;
        this.checklist = checklist;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(tm);
    }

    public record RunSummary(int processed, int done, int skipped) {}

    /** 대기 작업 처리(최대 limit 건) */
    public RunSummary runPending(int limit) {
        int processed = 0;
        int done = 0;
        for (int i = 0; i < limit; i++) {
            Optional<Map<String, Object>> job = tx.execute(s -> qar.jdbc().queryForList("""
                    SELECT * FROM qar.ai_review_job WHERE status = 'QUEUED' ORDER BY created_at, id
                     LIMIT 1 FOR UPDATE SKIP LOCKED""").stream().findFirst().map(j -> {
                        qar.jdbc().update("UPDATE qar.ai_review_job SET status = 'RUNNING', started_at = ? WHERE id = ?",
                                QarJdbc.ts(sup.now()), j.get("id"));
                        return j;
                    }));
            if (job == null || job.isEmpty()) {
                break;
            }
            processed++;
            if (process(job.get())) {
                done++;
            }
        }
        return new RunSummary(processed, done, processed - done);
    }

    private boolean process(Map<String, Object> job) {
        UUID jobId = QarJdbc.uuid(job, "id");
        Map<String, Object> round = sup.round(QarJdbc.uuid(job, "round_id"));
        UUID revisionId = QarJdbc.uuid(round, "revision_id");
        RevisionContent content;
        AtomAiCorePort.Request request;
        try {
            content = sup.content(revisionId);
            request = request(round, content);
        } catch (RuntimeException e) {
            finishSkipped(jobId, "FAILED", "FAILED", e.getMessage());
            return false;
        }
        AtomAiCorePort.Response resp;
        try {
            resp = ai.review(request);
        } catch (AtomAiCorePort.AiTimeoutException e) {
            finishSkipped(jobId, "TIMEOUT", "TIMEOUT", e.getMessage());
            return false;
        } catch (AtomAiCorePort.AiUnavailableException e) {
            String reason = e.getMessage() != null && e.getMessage().startsWith("DISABLED") ? "DISABLED" : "FAILED";
            finishSkipped(jobId, "FAILED", reason, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("AI 검토 실패 job={}: {}", jobId, e.toString());
            finishSkipped(jobId, "FAILED", "FAILED", e.toString());
            return false;
        }
        String promptHash = Hashing.sha256Hex(QarJdbc.json(request).getBytes(StandardCharsets.UTF_8));
        Boolean ok = tx.execute(s -> {
            Map<String, Object> j = qar.lock("ai_review_job", jobId);
            if (!"RUNNING".equals(QarJdbc.str(j, "status"))) {
                log.info("AI 응답 무시 — 작업이 이미 {} job={}", j.get("status"), jobId);
                return false;
            }
            CitationGuard.Context gctx = guardContext(content, request);
            int passed = 0;
            for (CitationGuard.Draft f : resp.findings() == null ? List.<CitationGuard.Draft>of() : resp.findings()) {
                CitationGuard.Verdict v = CitationGuard.guard(f, gctx);
                passed += v.passed() ? 1 : 0;
                qar.jdbc().update("""
                        INSERT INTO qar.ai_finding(id, round_id, job_id, category, severity_suggested, statement, citations,
                                                   guard_passed, guard_reason, model, prompt_hash)
                        VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""", UuidV7.next(), round.get("id"), jobId,
                        f.category() == null ? "UNKNOWN" : f.category(), severity(f.severity()),
                        f.statement() == null ? "" : f.statement(), QarJdbc.json(f.citations()), v.passed(), v.reason(),
                        resp.model() == null ? "unknown" : resp.model(), promptHash);
            }
            qar.jdbc().update("""
                    UPDATE qar.ai_review_job SET status = 'DONE', finished_at = ?, chunk_count = ?, finding_count = ?
                     WHERE id = ?""", QarJdbc.ts(sup.now()), request.sections().size(),
                    resp.findings() == null ? 0 : resp.findings().size(), jobId);
            log.info("AI 검토 완료 job={} 지적 {}건 중 인용 검증 통과 {}건", jobId,
                    resp.findings() == null ? 0 : resp.findings().size(), passed);
            advance(round, Command.AI_DONE, null);
            return true;
        });
        return Boolean.TRUE.equals(ok);
    }

    private void finishSkipped(UUID jobId, String jobStatus, String skipReason, String error) {
        tx.executeWithoutResult(s -> {
            Map<String, Object> j = qar.lock("ai_review_job", jobId);
            if (!Set.of("QUEUED", "RUNNING").contains(QarJdbc.str(j, "status"))) {
                return;
            }
            qar.jdbc().update("UPDATE qar.ai_review_job SET status = ?, finished_at = ?, error = ? WHERE id = ?",
                    jobStatus, QarJdbc.ts(sup.now()), error, jobId);
            advance(sup.round(QarJdbc.uuid(j, "round_id")), Command.AI_TIMEOUT, skipReason);
        });
    }

    /** 검토가 아직 이 라운드의 AI_REVIEWING 이면 AI_DONE / AI_TIMEOUT 명령 */
    private void advance(Map<String, Object> round, Command cmd, String skipReason) {
        Map<String, Object> review = sup.review(QarJdbc.uuid(round, "review_id"), false);
        if (ReviewStatus.valueOf(QarJdbc.str(review, "status")) != ReviewStatus.AI_REVIEWING
                || QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")) {
            log.info("AI 결과 반영 생략 — 검토 상태 {} round {}", review.get("status"), round.get("round_no"));
            return;
        }
        engine.handle(QarJdbc.uuid(review, "id"), cmd, null, new WorkflowEngine.Input(null, null, skipReason));
    }

    /** deadline_at 지난 작업 → TIMEOUT + AI_TIMEOUT (워커 정지 대비, TC-QAR-015) */
    public int scanTimeouts() {
        List<UUID> expired = qar.jdbc().queryForList("""
                SELECT id FROM qar.ai_review_job WHERE status IN ('QUEUED','RUNNING') AND deadline_at < ?
                 ORDER BY deadline_at""", UUID.class, QarJdbc.ts(sup.now()));
        for (UUID id : expired) {
            finishSkipped(id, "TIMEOUT", "TIMEOUT", "DEADLINE_EXCEEDED");
        }
        return expired.size();
    }

    // ------------------------------------------------------------------ 요청 · 인용 검증 문맥

    AtomAiCorePort.Request request(Map<String, Object> round, RevisionContent content) {
        List<AtomAiCorePort.SectionIn> sections = new ArrayList<>();
        for (Section s : content.sections()) {
            List<AtomAiCorePort.BlockIn> blocks = new ArrayList<>();
            for (Block b : s.blocks()) {
                if (b.text() != null && !b.text().isBlank()) {
                    blocks.add(new AtomAiCorePort.BlockIn(b.blockId(), b.text()));
                }
            }
            sections.add(new AtomAiCorePort.SectionIn(s.sectionKey(), blocks));
        }
        return new AtomAiCorePort.Request(QarJdbc.uuid(round, "id"), content.revision().reportType(), "ko", sections,
                facts(content), CHECKS);
    }

    /** 근거 데이터(토큰 상한 고려해 물량표 · 등급 · 결함 요약만) */
    static ObjectNode facts(RevisionContent content) {
        ObjectNode f = QarJdbc.MAPPER.createObjectNode();
        ArrayNode q = f.putArray("quantityTable");
        ReviewContext rc = new ReviewContext(content, null, ReviewContext.ExternalEvidence.NONE);
        for (ReviewContext.QuantityRow r : rc.quantityRows()) {
            ObjectNode n = q.addObject();
            n.put("buildingCode", r.buildingCode());
            n.put("floorCode", r.floorCode());
            n.put("memberType", r.memberType());
            n.put("defectType", r.defectType());
            n.put("count", ReviewContext.num(r.count()));
            n.put("lengthM", ReviewContext.num(r.lengthM()));
            n.put("areaM2", ReviewContext.num(r.areaM2()));
        }
        ObjectNode g = f.putObject("grade");
        if (content.grade() != null) {
            g.put("computed", content.grade().computedGrade());
            g.put("final", content.grade().finalGrade());
        }
        ArrayNode d = f.putArray("defects");
        for (Defect x : content.defects()) {
            ObjectNode n = d.addObject();
            n.put("id", x.id());
            n.put("floorCode", x.floorCode());
            n.put("memberType", x.memberType());
            n.put("defectType", x.defectType());
            n.put("widthMm", x.widthMm());
            n.put("lengthM", x.lengthM());
        }
        return f;
    }

    private CitationGuard.Context guardContext(RevisionContent content, AtomAiCorePort.Request request) {
        Map<UUID, String> texts = new HashMap<>();
        content.sections().forEach(s -> s.blocks().forEach(b -> texts.put(b.blockId(), b.text())));
        Optional<AnchorSet> anchors = sup.rpt().anchors(content.revision().revisionId());
        Set<UUID> anchorIds = anchors.map(a -> {
            Set<UUID> ids = new HashSet<>();
            a.anchors().forEach(x -> ids.add(x.blockId()));
            return ids;
        }).orElse(null);
        return new CitationGuard.Context(texts, anchorIds, request.facts());
    }

    private static String severity(String s) {
        return s != null && Set.of("CRITICAL", "MAJOR", "MINOR", "INFO").contains(s) ? s : "MINOR";
    }

    // ------------------------------------------------------------------ 조회 · 채택/기각

    @Transactional(readOnly = true)
    public List<Map<String, Object>> visibleFindings(UUID roundId) {
        Map<String, Object> round = sup.round(roundId);
        sup.reviewOfRound(round, "qar:suggestion:read");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> f : qar.jdbc().queryForList("""
                SELECT * FROM qar.ai_finding WHERE round_id = ? AND guard_passed ORDER BY created_at, id""", roundId)) {
            out.add(view(f));
        }
        return out;
    }

    Map<String, Object> view(Map<String, Object> f) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", f.get("id"));
        v.put("category", f.get("category"));
        v.put("severitySuggested", f.get("severity_suggested"));
        v.put("statement", f.get("statement"));
        v.put("citations", QarJdbc.tree(f.get("citations")));
        v.put("model", f.get("model"));
        v.put("status", "SUGGESTED");
        v.put("decision", f.get("decision"));
        v.put("dismissReason", f.get("dismiss_reason"));
        v.put("adoptedCommentId", f.get("adopted_comment_id"));
        return v;
    }

    @Transactional
    public Map<String, Object> decide(UUID findingId, String decision, String reason, String severityOverride) {
        Map<String, Object> f = qar.lock("ai_finding", findingId);
        if (!Boolean.TRUE.equals(f.get("guard_passed"))) {
            throw new DomainException(QarCodes.NOT_FOUND, "AI 제안 없음: " + findingId).with("reason", "QAR_AI_NOT_VISIBLE");
        }
        if (f.get("decision") != null) {
            throw new DomainException(QarCodes.AI_DECIDED, "이미 결정된 AI 제안입니다: " + f.get("decision"));
        }
        Map<String, Object> round = sup.round(QarJdbc.uuid(f, "round_id"));
        Map<String, Object> review = sup.reviewFor(QarJdbc.uuid(round, "review_id"), "qar:suggestion:decide", true);
        if (ReviewStatus.valueOf(QarJdbc.str(review, "status")) != ReviewStatus.QA_REVIEWING
                || QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")) {
            throw new DomainException(QarCodes.STATE, "AI 제안은 현재 라운드 QA 검토 중에만 결정합니다");
        }
        UUID actor = sup.actorId();
        if (!actor.equals(checklist.activeAssignee(QarJdbc.uuid(round, "id"), "QA_REVIEW"))) {
            throw new DomainException(QarCodes.NOT_ASSIGNEE, "이 라운드 QA 담당자만 AI 제안을 결정합니다");
        }
        UUID commentId = null;
        if ("ADOPTED".equals(decision)) {
            Map<String, Object> anchor = anchorFromCitations(QarJdbc.tree(f.get("citations")), round);
            String sev = severityOverride == null ? QarJdbc.str(f, "severity_suggested") : severityOverride;
            String code = ISSUE_BY_CATEGORY.getOrDefault(QarJdbc.str(f, "category"), "QI-AI");
            String body = "[AI 제안 채택] " + f.get("statement");
            Map<String, Object> c;
            try {
                c = comments.insert(round, new CommentService.NewComment(sev, code, anchor, body), actor, findingId);
            } catch (DomainException e) {
                if (!QarCodes.ANCHOR_INVALID.equals(e.code())) {
                    throw e;
                }
                // 인용문이 공백만 다른 경우(CitationGuard 는 정규화 비교) → 블록 앵커로 낮춘다
                Map<String, Object> blockAnchor = new LinkedHashMap<>(anchor);
                blockAnchor.put("type", "BLOCK");
                c = comments.insert(round, new CommentService.NewComment(sev, code, blockAnchor, body), actor, findingId);
            }
            commentId = (UUID) c.get("id");
            qar.jdbc().update("""
                    UPDATE qar.ai_finding SET decision = 'ADOPTED', adopted_comment_id = ?, decided_by = ?, decided_at = now()
                     WHERE id = ?""", commentId, actor, findingId);
        } else if ("DISMISSED".equals(decision)) {
            if (reason == null || !DISMISS_REASONS.contains(reason)) {
                throw new DomainException(QarCodes.DISMISS_REASON_REQUIRED,
                        "기각 사유(FALSE_POSITIVE · NOT_IMPORTANT · ALREADY_FIXED)가 필요합니다")
                        .with("reason", "QAR_DISMISS_REASON_REQUIRED");
            }
            qar.jdbc().update("""
                    UPDATE qar.ai_finding SET decision = 'DISMISSED', dismiss_reason = ?, decided_by = ?, decided_at = now()
                     WHERE id = ?""", reason, actor, findingId);
        } else {
            throw new DomainException(QarCodes.INVALID, "decision 은 ADOPTED · DISMISSED");
        }
        outbox.publish(new QarEvents.AiFindingDecided(findingId, QarJdbc.uuid(round, "id"), decision,
                "DISMISSED".equals(decision) ? reason : null, QarJdbc.str(f, "category"), QarJdbc.str(f, "model"),
                QarJdbc.str(f, "prompt_hash")));
        sup.audit().append("QAR", AuditLog.Action.UPDATE, "AiFinding", findingId, null,
                commentId == null ? Map.of("decision", decision, "reason", reason) : Map.of("decision", decision,
                        "commentId", commentId));
        return view(qar.get("ai_finding", findingId));
    }

    private Map<String, Object> anchorFromCitations(com.fasterxml.jackson.databind.JsonNode cites,
                                                    Map<String, Object> round) {
        for (var c : cites) {
            if ("report".equalsIgnoreCase(c.path("kind").asText()) && c.hasNonNull("blockId")) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("type", "TEXT_RANGE");
                a.put("revisionId", round.get("revision_id"));
                a.put("blockId", c.path("blockId").asText());
                a.put("quote", c.path("quote").asText());
                return a;
            }
        }
        throw new DomainException(QarCodes.ANCHOR_INVALID, "AI 제안에 보고서 인용이 없습니다");
    }
}
