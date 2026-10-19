package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.Role;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.CommentStatus;
import com.atomeng.erp.qar.domain.CommentStatus.ReplyAction;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.ReviewStatus;
import com.atomeng.erp.qar.domain.anchor.AnchorResolver;
import com.atomeng.erp.rpt.api.RptRevisionQuery.AnchorSet;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Block;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 앵커 코멘트 · 스레드 (FR-QAR-004 · 005 · 012, A6 코멘트 상태, BR-QAR-004, TC-QAR-014).
 * <ul>
 *   <li>앵커: TEXT_RANGE 는 라운드 고정 리비전의 블록 원문과 [start, end) · quote 가 일치해야 하고, 그 리비전에
 *       anchors.json 이 있으면 블록 앵커가 있어야 한다. anchors.json 이 없는 리비전(렌더 전)은 SECTION 앵커로 폴백</li>
 *   <li>답변 · 해결 요청은 보고서 작성 측, 해결 확인 · 재오픈 · 철회는 코멘트 작성자(QA)만(QAR_ONLY_COMMENT_AUTHOR)</li>
 *   <li>답변(comment_reply)은 append-only(DB 트리거, TC-QAR-013)</li>
 *   <li>재제출 시 미해결 코멘트를 새 라운드로 이월(origin_round_no 유지) + 앵커 재매핑(OK · REMAPPED · LOST)</li>
 * </ul>
 */
@Service
public class CommentService {

    private static final Set<String> SEVERITIES = Set.of("CRITICAL", "MAJOR", "MINOR", "INFO");
    private static final Set<String> ANCHOR_TYPES = Set.of("TEXT_RANGE", "TABLE_CELL", "PHOTO", "DRAWING_PIN", "GRADE",
            "SECTION", "BLOCK");
    private static final Pattern ISSUE_CODE = Pattern.compile("^QI-[A-Z]+$");

    public record NewComment(String severity, String issueCode, Map<String, Object> anchor, String body) {}

    private final ReviewSupport sup;
    private final QarJdbc qar;

    public CommentService(ReviewSupport sup) {
        this.sup = sup;
        this.qar = sup.jdbc();
    }

    @Transactional
    public Map<String, Object> create(UUID roundId, NewComment c) {
        Map<String, Object> round = sup.round(roundId);
        Map<String, Object> review = sup.reviewFor(QarJdbc.uuid(round, "review_id"), "qar:remark:create", true);
        ReviewStatus st = ReviewStatus.valueOf(QarJdbc.str(review, "status"));
        if (QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")
                || !(st == ReviewStatus.QA_REVIEWING || st == ReviewStatus.PENDING_APPROVAL)) {
            throw new DomainException(QarCodes.STATE, "코멘트는 현재 라운드의 QA 검토 · 승인 단계에서만 남깁니다: " + st);
        }
        return insert(round, c, sup.actorId(), null);
    }

    /** 코멘트 저장(검증된 앵커) — AI 제안 채택도 이 경로 */
    Map<String, Object> insert(Map<String, Object> round, NewComment c, UUID author, UUID aiFindingId) {
        if (c.severity() == null || !SEVERITIES.contains(c.severity())) {
            throw new DomainException(QarCodes.INVALID, "severity 는 CRITICAL · MAJOR · MINOR · INFO");
        }
        if (c.issueCode() == null || !ISSUE_CODE.matcher(c.issueCode()).matches()) {
            throw new DomainException(QarCodes.INVALID, "issueCode 형식(QI-XXX) 오류");
        }
        if (c.body() == null || c.body().isBlank()) {
            throw new DomainException(QarCodes.INVALID, "코멘트 본문이 필요합니다");
        }
        UUID revisionId = QarJdbc.uuid(round, "revision_id");
        Map<String, Object> anchor = resolveAnchor(revisionId, c.anchor());
        UUID id = UuidV7.next();
        qar.jdbc().update("""
                INSERT INTO qar.review_comment(id, round_id, origin_round_no, author_id, severity, issue_code, anchor,
                                               anchor_status, body, status, ai_finding_id, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, 'OK', ?, 'OPEN', ?, ?, ?)""", id, round.get("id"),
                round.get("round_no"), author, c.severity(), c.issueCode(), QarJdbc.json(anchor), c.body().strip(),
                aiFindingId, author, author);
        sup.audit().append("QAR", AuditLog.Action.CREATE, "ReviewComment", id, null,
                Map.of("severity", c.severity(), "issueCode", c.issueCode(), "anchorType", anchor.get("type")));
        return view(qar.get("review_comment", id));
    }

    /** 앵커 검증 · 폴백 (TC-QAR-014) */
    public Map<String, Object> resolveAnchor(UUID revisionId, Map<String, Object> in) {
        if (in == null || in.get("type") == null || !ANCHOR_TYPES.contains(String.valueOf(in.get("type")))) {
            throw new DomainException(QarCodes.ANCHOR_INVALID, "anchor.type 은 " + ANCHOR_TYPES + " 중 하나");
        }
        if (in.get("revisionId") != null && !revisionId.toString().equals(String.valueOf(in.get("revisionId")))) {
            throw new DomainException(QarCodes.ANCHOR_INVALID, "앵커 리비전이 라운드 고정 리비전과 다릅니다");
        }
        String type = String.valueOf(in.get("type"));
        RevisionContent content = sup.content(revisionId);
        Optional<AnchorSet> anchors = sup.rpt().anchors(revisionId);
        Map<String, Object> out = new LinkedHashMap<>(in);
        out.put("revisionId", revisionId);
        if ("SECTION".equals(type)) {
            String key = String.valueOf(in.get("sectionKey"));
            if (content.sections().stream().noneMatch(s -> s.sectionKey().equals(key))) {
                throw new DomainException(QarCodes.ANCHOR_INVALID, "섹션 없음: " + key);
            }
            return out;
        }
        Block block = Optional.ofNullable(in.get("blockId")).map(Object::toString).flatMap(id -> content.sections().stream()
                .flatMap(s -> s.blocks().stream()).filter(b -> b.blockId().toString().equals(id)).findFirst())
                .orElseThrow(() -> new DomainException(QarCodes.ANCHOR_INVALID, "리비전에 없는 blockId: " + in.get("blockId")));
        if (anchors.isEmpty()) {
            // anchors.json 없는 리비전 → SECTION 폴백(원래 요청은 requested 로 보존)
            Map<String, Object> fb = new LinkedHashMap<>();
            fb.put("type", "SECTION");
            fb.put("revisionId", revisionId);
            fb.put("sectionKey", block.sectionKey());
            fb.put("fallback", "NO_ANCHORS_JSON");
            fb.put("requested", in);
            return fb;
        }
        if (anchors.get().anchors().stream().noneMatch(a -> a.blockId().equals(block.blockId()))) {
            throw new DomainException(QarCodes.ANCHOR_INVALID, "anchors.json 에 없는 블록: " + block.blockId());
        }
        anchors.get().anchors().stream().filter(a -> a.blockId().equals(block.blockId())).findFirst()
                .ifPresent(a -> out.putIfAbsent("pdf", Map.of("page", a.page())));
        if ("TEXT_RANGE".equals(type)) {
            return AnchorResolver.validateTextRange(out, block).orElseThrow(() -> new DomainException(
                    QarCodes.ANCHOR_INVALID, "앵커 start/end · quote 가 블록 원문과 일치하지 않습니다"));
        }
        out.put("sectionKey", block.sectionKey());
        return out;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID roundId) {
        Map<String, Object> round = sup.round(roundId);
        sup.reviewOfRound(round, "qar:review:read");
        return qar.jdbc().queryForList("SELECT * FROM qar.review_comment WHERE round_id = ? ORDER BY created_at, id",
                roundId).stream().map(this::view).toList();
    }

    @Transactional
    public Map<String, Object> reply(UUID commentId, ReplyAction action, String body) {
        Map<String, Object> c = qar.lock("review_comment", commentId);
        Map<String, Object> round = sup.round(QarJdbc.uuid(c, "round_id"));
        Map<String, Object> review = sup.reviewFor(QarJdbc.uuid(round, "review_id"), "qar:thread:create", false);
        // SEC-QAR-04: 이월로 닫힌 지난 라운드 코멘트 · 승인 완료 검토의 코멘트는 상태를 바꿀 수 없다
        if (ReviewStatus.valueOf(QarJdbc.str(review, "status")) == ReviewStatus.APPROVED
                || QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")) {
            throw new DomainException(QarCodes.COMMENT_STATE, "현재 라운드의 진행 중인 검토 코멘트만 답변 · 상태 변경할 수 있습니다");
        }
        UUID actor = sup.actorId();
        boolean isCommentAuthor = actor.equals(QarJdbc.uuid(c, "author_id"));
        if (CommentStatus.commentAuthorOnly(action)) {
            if (!isCommentAuthor) {
                throw new DomainException(QarCodes.ONLY_COMMENT_AUTHOR, "코멘트 작성자만 할 수 있습니다: " + action)
                        .with("reason", "QAR_ONLY_COMMENT_AUTHOR");
            }
        } else if (isCommentAuthor || (sup.access().has(Role.QA) && !sup.access().has(Role.PM)
                && !sup.access().has(Role.ENG))) {
            throw new DomainException(QarCodes.NOT_AUTHOR_SIDE, "답변 · 해결 요청은 보고서 작성 측(PM · ENG)만 합니다");
        }
        CommentStatus from = CommentStatus.valueOf(QarJdbc.str(c, "status"));
        CommentStatus to = from.next(action).orElseThrow(() -> new DomainException(QarCodes.COMMENT_STATE,
                "코멘트 상태 전이 불가: %s → %s".formatted(from, action)));
        qar.jdbc().update("""
                INSERT INTO qar.comment_reply(id, comment_id, author_id, body, action, from_status, to_status)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), commentId, actor, body == null ? "" : body.strip(),
                action.name(), from.name(), to.name());
        qar.jdbc().update("""
                UPDATE qar.review_comment SET status = ?, resolved_by = CASE WHEN ? THEN ? ELSE resolved_by END,
                       resolved_at = CASE WHEN ? THEN now() ELSE resolved_at END,
                       version = version + 1, updated_at = now(), updated_by = ? WHERE id = ?""",
                to.name(), to == CommentStatus.RESOLVED, actor, to == CommentStatus.RESOLVED, actor, commentId);
        sup.audit().append("QAR", AuditLog.Action.UPDATE, "ReviewComment", commentId, Map.of("status", from.name()),
                Map.of("status", to.name(), "action", action.name()));
        return view(qar.get("review_comment", commentId));
    }

    /** 효과 CARRY_OVER_OPEN_COMMENTS — 미해결 코멘트를 새 라운드로 복제 + 앵커 재매핑 */
    int carryOver(UUID oldRoundId, Map<String, Object> newRound) {
        UUID newRevision = QarJdbc.uuid(newRound, "revision_id");
        RevisionContent content = sup.content(newRevision);
        int n = 0;
        for (Map<String, Object> c : qar.jdbc().queryForList("""
                SELECT * FROM qar.review_comment WHERE round_id = ? AND status NOT IN ('RESOLVED','WITHDRAWN')
                 ORDER BY created_at, id""", oldRoundId)) {
            AnchorResolver.Remap remap = AnchorResolver.remap(QarJdbc.map(c.get("anchor")), newRevision, content.sections());
            qar.jdbc().update("""
                    INSERT INTO qar.review_comment(id, round_id, origin_round_no, carried_from, author_id, severity,
                                                   issue_code, anchor, anchor_status, body, status, ai_finding_id,
                                                   created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), newRound.get("id"),
                    c.get("origin_round_no"), c.get("id"), c.get("author_id"), c.get("severity"), c.get("issue_code"),
                    QarJdbc.json(remap.anchor()), remap.status().name(), c.get("body"), c.get("status"),
                    c.get("ai_finding_id"), sup.actorId(), sup.actorId());
            n++;
        }
        return n;
    }

    /** 미해결 코멘트(심각도별) */
    List<Map<String, Object>> open(UUID roundId, Set<String> severities) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : qar.jdbc().queryForList("""
                SELECT id, severity, issue_code, body FROM qar.review_comment
                 WHERE round_id = ? AND status NOT IN ('RESOLVED','WITHDRAWN') ORDER BY created_at, id""", roundId)) {
            if (severities == null || severities.contains(QarJdbc.str(c, "severity"))) {
                out.add(c);
            }
        }
        return out;
    }

    Map<String, Object> view(Map<String, Object> c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", c.get("id"));
        v.put("roundId", c.get("round_id"));
        v.put("originRoundNo", c.get("origin_round_no"));
        v.put("carriedFrom", c.get("carried_from"));
        v.put("authorId", c.get("author_id"));
        v.put("severity", c.get("severity"));
        v.put("issueCode", c.get("issue_code"));
        v.put("anchor", QarJdbc.tree(c.get("anchor")));
        v.put("anchorStatus", c.get("anchor_status"));
        v.put("body", c.get("body"));
        v.put("status", c.get("status"));
        v.put("aiFindingId", c.get("ai_finding_id"));
        v.put("version", QarJdbc.version(c));
        List<Map<String, Object>> replies = new ArrayList<>();
        for (Map<String, Object> r : qar.jdbc().queryForList(
                "SELECT * FROM qar.comment_reply WHERE comment_id = ? ORDER BY created_at, id", c.get("id"))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", r.get("id"));
            x.put("authorId", r.get("author_id"));
            x.put("action", r.get("action"));
            x.put("body", r.get("body"));
            x.put("from", r.get("from_status"));
            x.put("to", r.get("to_status"));
            replies.add(x);
        }
        v.put("replies", replies);
        return v;
    }
}
