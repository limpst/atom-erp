package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.identity.Role;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.api.QarEvents;
import com.atomeng.erp.qar.application.port.QarEvidencePort;
import com.atomeng.erp.qar.domain.Command;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.ReviewStatus;
import com.atomeng.erp.qar.domain.TransitionTable;
import com.atomeng.erp.qar.domain.TransitionTable.Effect;
import com.atomeng.erp.qar.domain.TransitionTable.Guard;
import com.atomeng.erp.qar.domain.TransitionTable.Transition;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 워크플로우 엔진 (설계 B7.1 · 07-IMPL Step 2). 모든 전이는 한 트랜잭션에서
 * 검토 행 잠금(FOR UPDATE) → 낙관적 잠금(If-Match version, 불일치 409 QAR_VERSION) → 전이 표 조회(없으면 409 QAR_STATE) →
 * 가드 전부 평가(첫 실패에서 멈추지 않고 사유 목록 409, TC-QAR-001) → 상태 변경 → 효과 → core.audit_log + core.outbox_event
 * (NFR-QAR-04). 효과 AUTO_START_AI 는 같은 트랜잭션에서 AI_START(AUTO)를 이어 실행한다.
 */
@Service
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final Set<String> SEVERE = Set.of("CRITICAL", "MAJOR");
    public static final int ROUND_LIMIT = 3;

    /** 명령 입력 — rejectSummary(반려 요약, 비우면 미해결 코멘트로 자동 생성) · requestedBy(AI_START AUTO/MANUAL) · skipReason */
    public record Input(String rejectSummary, String requestedBy, String skipReason) {
        public static final Input NONE = new Input(null, null, null);
    }

    public record Violation(String code, String message, List<Object> refs) {}

    private final ReviewSupport sup;
    private final QarJdbc qar;
    private final ReviewService reviews;
    private final AutoCheckService autochecks;
    private final ChecklistService checklist;
    private final CommentService comments;
    private final AssignmentService assignments;
    private final QarRuleSetService rules;
    private final QarEvidencePort evidence;
    private final OutboxPublisher outbox;
    private final QarProperties props;

    public WorkflowEngine(ReviewSupport sup, ReviewService reviews, AutoCheckService autochecks,
                          ChecklistService checklist, CommentService comments, AssignmentService assignments,
                          QarRuleSetService rules, QarEvidencePort evidence, OutboxPublisher outbox, QarProperties props) {
        this.sup = sup;
        this.qar = sup.jdbc();
        this.reviews = reviews;
        this.autochecks = autochecks;
        this.checklist = checklist;
        this.comments = comments;
        this.assignments = assignments;
        this.rules = rules;
        this.evidence = evidence;
        this.outbox = outbox;
        this.props = props;
    }

    @Transactional
    public Map<String, Object> handle(UUID reviewId, Command cmd, Long expectedVersion, Input in) {
        Map<String, Object> review = sup.review(reviewId, true);
        sup.access().requireProject(permission(cmd), QarJdbc.uuid(review, "project_id"));
        if (expectedVersion != null && expectedVersion != QarJdbc.version(review)) {
            throw new DomainException(QarCodes.VERSION, "다른 사용자가 먼저 처리했습니다(요청 v%d, 현재 v%d)"
                    .formatted(expectedVersion, QarJdbc.version(review))).with("reason", "QAR_VERSION")
                    .with("currentVersion", QarJdbc.version(review));
        }
        apply(review, cmd, in == null ? Input.NONE : in);
        return reviews.view(reviewId);
    }

    static String permission(Command cmd) {
        return switch (cmd) {
            case SUBMIT, RESUBMIT_PREP, REOPEN_FOR_CORRECTION -> "qar:author:update";
            case AI_START, AI_DONE, AI_TIMEOUT -> "qar:ai-job:run";
            case PASS, REJECT -> "qar:decision:update";
            case APPROVE -> "qar:approval:approve";
        };
    }

    private void apply(Map<String, Object> review, Command cmd, Input in) {
        ReviewStatus from = ReviewStatus.valueOf(QarJdbc.str(review, "status"));
        Transition t = TransitionTable.find(from, cmd).orElseThrow(() -> new DomainException(QarCodes.STATE,
                "현재 상태(%s)에서 할 수 없는 명령: %s".formatted(from, cmd)).with("reason", "QAR_STATE")
                .with("status", from.name()).with("command", cmd.name()));
        if (from == ReviewStatus.PENDING_APPROVAL && !sup.access().roles().isEmpty() && !sup.access().has(Role.PM)) {
            throw new DomainException(QarCodes.NOT_ASSIGNEE, "최종 승인 단계 결정은 책임기술자(PM)만 합니다");
        }
        Ctx ctx = new Ctx(review, sup.currentRound(review), in, cmd);
        List<Violation> violations = new ArrayList<>();
        for (Guard g : t.guards()) {
            check(g, ctx).ifPresent(violations::add);
        }
        if (!violations.isEmpty()) {
            List<Map<String, Object>> list = violations.stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", v.code());
                m.put("message", v.message());
                m.put("refs", v.refs());
                return m;
            }).toList();
            throw new DomainException(QarCodes.GUARD_FAILED, "가드 실패 %d건: %s".formatted(violations.size(),
                    violations.stream().map(Violation::code).toList())).with("violations", list)
                    .with("command", cmd.name());
        }
        UUID reviewId = QarJdbc.uuid(review, "id");
        qar.jdbc().update("UPDATE qar.review SET status = ?, version = version + 1, updated_at = now(), updated_by = ? "
                + "WHERE id = ?", t.to().name(), sup.actorId(), reviewId);
        boolean chainAi = false;
        for (Effect e : t.effects()) {
            chainAi |= effect(e, ctx);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("status", from.name());
        before.put("cmd", cmd.name());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", t.to().name());
        after.put("round", qar.get("review", reviewId).get("current_round"));
        if (ctx.summary != null) {
            after.put("rejectSummary", ctx.summary);
        }
        sup.audit().append("QAR", cmd == Command.APPROVE ? AuditLog.Action.APPROVE : AuditLog.Action.UPDATE, "Review",
                reviewId, before, after);
        if (chainAi && props.ai().autoStart()) {
            apply(qar.get("review", reviewId), Command.AI_START, new Input(null, "AUTO", null));
        }
    }

    // ------------------------------------------------------------------ 가드

    /** 가드 평가 문맥(지연 조회) */
    private final class Ctx {
        final Map<String, Object> review;
        final Map<String, Object> round;
        final Input in;
        final Command cmd;
        RevisionInfo info;
        RevisionInfo current;
        String summary;

        Ctx(Map<String, Object> review, Map<String, Object> round, Input in, Command cmd) {
            this.review = review;
            this.round = round;
            this.in = in;
            this.cmd = cmd;
        }

        UUID roundId() {
            return QarJdbc.uuid(round, "id");
        }

        RevisionInfo info() {
            if (info == null) {
                info = sup.revision(QarJdbc.uuid(round, "revision_id"));
            }
            return info;
        }

        RevisionInfo current() {
            if (current == null) {
                current = sup.rpt().currentRevision(QarJdbc.uuid(review, "report_id")).orElse(info());
            }
            return current;
        }

        String reportType() {
            return QarJdbc.str(review, "report_type");
        }
    }

    private Optional<Violation> check(Guard g, Ctx c) {
        UUID actor = sup.actorId();
        return switch (g) {
            case REVISION_LOCKED -> {
                RevisionInfo i = c.info();
                if (!"IN_REVIEW".equals(i.status())) {
                    yield v("QAR_REVISION_NOT_LOCKED", "RPT 리비전이 검토 요청(IN_REVIEW · 편집 잠금) 상태가 아닙니다: "
                            + i.status(), i.revisionId());
                }
                if (!i.renderFresh() || i.renderedPdfSha256() == null) {
                    yield v("QAR_RENDER_STALE", "현재 본문으로 렌더된 PDF 가 없습니다", i.revisionId());
                }
                yield Optional.empty();
            }
            case AUTOCHECK_FRESH -> {
                Optional<Map<String, Object>> run = autochecks.latestRun(c.roundId());
                if (run.isEmpty()) {
                    yield v("QAR_AUTOCHECK_MISSING", "AUTO-CHECK 를 먼저 실행하세요", c.roundId());
                }
                String expectVersion = rules.ruleSetAsOf(sup.today()).version();
                if (!c.info().contentHash().equals(QarJdbc.str(run.get(), "content_hash"))
                        || !expectVersion.equals(QarJdbc.str(run.get(), "rule_version"))) {
                    yield v("QAR_AUTOCHECK_STALE", "본문 또는 유효 규칙 세트(" + expectVersion
                            + ")가 마지막 AUTO-CHECK 이후 바뀌었습니다 — 다시 실행하세요", run.get().get("id"));
                }
                yield Optional.empty();
            }
            case NO_BLOCKING -> {
                List<UUID> blocking = autochecks.blockingOfLatest(c.roundId());
                yield blocking.isEmpty() ? Optional.empty()
                        : Optional.of(new Violation("QAR_BLOCKING_EXISTS", "BLOCKING 자동검사 결과 %d건(waiver 불가, BR-QAR-001)"
                                .formatted(blocking.size()), List.copyOf(blocking)));
            }
            case CHECKLIST_SELF_COMPLETE, CHECKLIST_QA_COMPLETE -> {
                ChecklistService.Stage s = g == Guard.CHECKLIST_SELF_COMPLETE ? ChecklistService.Stage.SELF
                        : ChecklistService.Stage.QA;
                List<String> missing = checklist.missing(c.roundId(), checklist.template(c.round, c.reportType()).id(), s);
                yield missing.isEmpty() ? Optional.empty() : Optional.of(new Violation("QAR_CHECKLIST_INCOMPLETE",
                        s + " 체크리스트 미응답 문항 %d건".formatted(missing.size()), List.copyOf(missing)));
            }
            case NO_OPEN_SEVERE -> {
                List<Map<String, Object>> open = comments.open(c.roundId(), SEVERE);
                yield open.isEmpty() ? Optional.empty() : Optional.of(new Violation("QAR_OPEN_SEVERE_COMMENTS",
                        "미해결 CRITICAL · MAJOR 코멘트 %d건".formatted(open.size()),
                        open.stream().map(m -> m.get("id")).toList()));
            }
            case ACTOR_IS_QA_ASSIGNEE -> {
                UUID assignee = checklist.activeAssignee(c.roundId(), "QA_REVIEW");
                yield actor.equals(assignee) ? Optional.empty()
                        : v("QAR_NOT_ASSIGNEE", "이 라운드 QA 담당자만 결정할 수 있습니다", assignee);
            }
            case SOD_APPROVER -> {
                if (assignments.qaAssignees(c.roundId()).contains(actor)) {
                    yield v("QAR_SOD_VIOLATION", "QA 검토자는 최종 승인자가 될 수 없습니다(BR-QAR-002)", actor);
                }
                // 보안 검토 2026-10-19 QAR-01: 최종 승인자 ≠ 작성자 · 섹션 편집자 · 제출자 · 등급 결정자(3자 분리)
                String role = c.cmd == Command.APPROVE ? sup.authorRole(c.review, c.round, actor, true) : null;
                yield role == null ? Optional.empty()
                        : v("QAR_SOD_VIOLATION", "작성 · 편집 · 제출 · 등급 결정에 참여한 사람은 최종 승인자가 될 수 없습니다("
                                + role + ")", actor);
            }
            case QUALIFICATION_VALID -> evidence.approverQualification(actor, QarJdbc.uuid(c.review, "project_id"),
                    sup.today()) == QarEvidencePort.Qualification.INVALID
                    ? v("QAR_QUALIFICATION_INVALID", "책임기술자 자격이 유효하지 않습니다(" + sup.today() + " 기준)", actor)
                    : Optional.empty();
            case REVISION_UNCHANGED -> {
                RevisionInfo i = c.info();
                yield "IN_REVIEW".equals(i.status()) && i.contentHash().equals(QarJdbc.str(c.round, "content_hash"))
                        ? Optional.empty()
                        : v("QAR_REVISION_CHANGED", "제출 후 RPT 리비전 상태 · 본문이 바뀌었습니다: " + i.status(), i.revisionId());
            }
            case HAS_REJECT_SUMMARY -> {
                c.summary = rejectSummary(c);
                yield c.summary == null ? v("QAR_REJECT_SUMMARY_REQUIRED", "반려 사유(요약)가 필요합니다", c.roundId())
                        : Optional.empty();
            }
            case REVISION_CHANGED -> {
                RevisionInfo cur = c.current();
                boolean changed = !cur.revisionId().equals(QarJdbc.uuid(c.round, "revision_id"))
                        || !cur.contentHash().equals(QarJdbc.str(c.round, "content_hash"));
                yield changed ? Optional.empty()
                        : v("QAR_REVISION_NOT_CHANGED", "반려 후 RPT 본문이 수정되지 않았습니다", cur.revisionId());
            }
            case CORRECTION_REVISION_EXISTS -> c.current().revNo() > QarJdbc.integer(c.round, "rev_no")
                    ? Optional.empty()
                    : v("QAR_NO_CORRECTION_REVISION", "정정본(새 Rev) 이 없습니다", QarJdbc.uuid(c.review, "report_id"));
        };
    }

    private static Optional<Violation> v(String code, String message, Object ref) {
        List<Object> refs = new ArrayList<>();
        if (ref != null) {
            refs.add(ref);
        }
        return Optional.of(new Violation(code, message, refs));
    }

    /** 반려 요약: 입력값 또는 미해결 코멘트 심각도 · 유형별 묶음(FR-QAR-012) */
    private String rejectSummary(Ctx c) {
        if (c.in.rejectSummary() != null && !c.in.rejectSummary().isBlank()) {
            return c.in.rejectSummary().strip();
        }
        List<Map<String, Object>> open = comments.open(c.roundId(), null);
        if (open.isEmpty()) {
            return null;
        }
        Map<String, Map<String, Integer>> bySev = new TreeMap<>();
        for (Map<String, Object> o : open) {
            bySev.computeIfAbsent(QarJdbc.str(o, "severity"), k -> new TreeMap<>())
                    .merge(QarJdbc.str(o, "issue_code"), 1, Integer::sum);
        }
        StringBuilder sb = new StringBuilder("미해결 코멘트 " + open.size() + "건 — ");
        for (String sev : List.of("CRITICAL", "MAJOR", "MINOR", "INFO")) {
            Map<String, Integer> codes = bySev.get(sev);
            if (codes != null) {
                sb.append(sev).append(' ').append(codes.values().stream().mapToInt(Integer::intValue).sum())
                        .append("건").append(codes).append("; ");
            }
        }
        return sb.toString().strip();
    }

    // ------------------------------------------------------------------ 효과

    /** @return true 면 AI_START(AUTO) 를 이어서 실행 */
    private boolean effect(Effect e, Ctx c) {
        UUID reviewId = QarJdbc.uuid(c.review, "id");
        UUID roundId = c.roundId();
        switch (e) {
            case FREEZE_ROUND -> {
                RevisionInfo i = c.info();
                Map<String, String> versions = rules.versionsFor(Map.of(), c.reportType(), sup.today());
                qar.jdbc().update("""
                        UPDATE qar.review_round SET rev_no = ?, snapshot_ver = ?, pdf_sha256 = ?, content_hash = ?,
                               rule_versions = ?::jsonb, submitted_at = ?, submitted_by = ? WHERE id = ?""",
                        i.revNo(), i.snapshotVer(), i.renderedPdfSha256(), i.contentHash(), QarJdbc.json(versions),
                        QarJdbc.ts(sup.now()), sup.actorId(), roundId);
            }
            case PUBLISH_SUBMITTED -> {
                Map<String, Object> r = sup.round(roundId);
                Map<String, String> versions = new LinkedHashMap<>();
                QarJdbc.map(r.get("rule_versions")).forEach((k, v) -> versions.put(k, String.valueOf(v)));
                outbox.publish(new QarEvents.ReviewSubmitted(reviewId, QarJdbc.uuid(c.review, "report_id"),
                        QarJdbc.integer(r, "round_no"), QarJdbc.uuid(r, "revision_id"), QarJdbc.str(r, "pdf_sha256"),
                        versions));
            }
            case AUTO_START_AI -> {
                return true;
            }
            case REQUEST_AI -> {
                String by = "AUTO".equals(c.in.requestedBy()) ? "AUTO" : "MANUAL";
                qar.jdbc().update("""
                        INSERT INTO qar.ai_review_job(id, round_id, status, requested_by, deadline_at)
                        VALUES (?, ?, 'QUEUED', ?, ?)""", UuidV7.next(), roundId, by,
                        QarJdbc.ts(sup.now().plus(props.ai().deadline())));
                qar.jdbc().update("UPDATE qar.review_round SET ai_status = 'RUNNING', ai_skip_reason = NULL WHERE id = ?",
                        roundId);
            }
            case MARK_AI_DONE -> qar.jdbc().update("UPDATE qar.review_round SET ai_status = 'DONE' WHERE id = ?", roundId);
            case MARK_AI_SKIPPED -> qar.jdbc().update(
                    "UPDATE qar.review_round SET ai_status = 'SKIPPED', ai_skip_reason = ? WHERE id = ?",
                    c.in.skipReason() == null ? "TIMEOUT" : c.in.skipReason(), roundId);
            case ASSIGN_QA -> assignments.autoAssign(c.review, sup.round(roundId), "QA_REVIEW",
                    reviews.slaHours(c.review, "QA_REVIEW"));
            case COMPLETE_QA_ASSIGNMENT -> qar.jdbc().update("""
                    UPDATE qar.review_assignment SET completed_at = now(), decision = ?
                     WHERE round_id = ? AND step_code = 'QA_REVIEW' AND completed_at IS NULL""",
                    c.cmd == com.atomeng.erp.qar.domain.Command.PASS ? "PASS" : "REJECT", roundId);
            case CLOSE_ROUND_REJECTED -> qar.jdbc().update("""
                    UPDATE qar.review_round SET closed_at = now(), outcome = 'REJECTED', reject_summary = ? WHERE id = ?""",
                    c.summary, roundId);
            case PUBLISH_REJECTED -> {
                Map<String, Integer> openCounts = new TreeMap<>();
                comments.open(roundId, null).forEach(o -> openCounts.merge(QarJdbc.str(o, "severity"), 1, Integer::sum));
                String step = ReviewStatus.valueOf(QarJdbc.str(c.review, "status")) == ReviewStatus.PENDING_APPROVAL
                        ? "FINAL_APPROVAL" : "QA_REVIEW";
                outbox.publish(new QarEvents.ReviewRejected(reviewId, QarJdbc.uuid(c.round, "revision_id"), sup.actorId(),
                        c.summary, QarJdbc.integer(c.round, "round_no"), step, c.summary, openCounts));
            }
            case CLOSE_ROUND_APPROVED -> {
                qar.jdbc().update("UPDATE qar.review_round SET closed_at = now(), outcome = 'APPROVED' WHERE id = ?",
                        roundId);
                qar.jdbc().update("UPDATE qar.review SET approved_at = ?, approved_by = ? WHERE id = ?",
                        QarJdbc.ts(sup.now()), sup.actorId(), reviewId);
            }
            case PUBLISH_APPROVED -> {
                List<UUID> waivers = qar.jdbc().queryForList("""
                        SELECT w.id FROM qar.waiver w JOIN qar.autocheck_result r ON r.id = w.autocheck_result_id
                         WHERE r.round_id = ? AND w.status = 'GRANTED' ORDER BY w.created_at, w.id""", UUID.class, roundId);
                outbox.publish(new QarEvents.ReviewApproved(reviewId, QarJdbc.uuid(c.round, "revision_id"), sup.actorId(),
                        "QAR 최종 승인(라운드 " + c.round.get("round_no") + ")", QarJdbc.integer(c.round, "round_no"),
                        sup.actorId(), waivers, QarJdbc.str(c.round, "pdf_sha256")));
            }
            case NEW_ROUND -> {
                RevisionInfo cur = c.current();
                int next = QarJdbc.integer(c.review, "current_round") + 1;
                reviews.insertRound(reviewId, next, cur, c.cmd.name());
                qar.jdbc().update("UPDATE qar.review SET current_round = ? WHERE id = ?", next, reviewId);
            }
            case CARRY_OVER_OPEN_COMMENTS -> {
                Map<String, Object> newRound = sup.currentRound(qar.get("review", reviewId));
                int n = comments.carryOver(roundId, newRound);
                log.info("미해결 코멘트 {}건 이월 review={} round={}", n, reviewId, newRound.get("round_no"));
            }
            case ESCALATE_IF_ROUND_LIMIT -> {
                int roundNo = QarJdbc.integer(qar.get("review", reviewId), "current_round");
                if (roundNo > ROUND_LIMIT) {
                    outbox.publish(new QarEvents.ReviewEscalated(reviewId, "ROUND_LIMIT", roundNo));
                }
            }
            case NOTIFY_AUTHOR -> log.info("작성자 알림(알림 서비스 미연동) review={} cmd={}", reviewId, c.cmd);
        }
        return false;
    }
}
