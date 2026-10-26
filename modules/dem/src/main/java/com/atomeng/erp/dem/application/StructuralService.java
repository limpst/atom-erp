package com.atomeng.erp.dem.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.atomeng.erp.dem.api.DemEvents;
import com.atomeng.erp.dem.domain.DemStates.CommentStatus;
import com.atomeng.erp.dem.domain.DemStates.RevisionStatus;
import com.atomeng.erp.dem.domain.RevCode;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도서 · 리비전 · 해석모델 · Transmittal · 검토의견서 (FR-DEM-012·013·014, BR-DEM-008·009, A6.2 · A6.3,
 * 10-IMPL Step 11·12, TC-DEM-007·008·013).
 * <ul>
 *   <li>리비전 채번: 내부 A → B … (생성 시), 발행 0 → 1 …(Transmittal 발행 시 issued_rev)</li>
 *   <li>발행(ISSUED)된 리비전은 DB 트리거가 status → SUPERSEDED 외 모든 변경 · 삭제를 거부(BR-DEM-008) — 서비스도 409 선검사</li>
 *   <li>FOR_CONSTRUCTION 발행은 해당 도서들의 미결 의견(OPEN · RESPONDED · REJECTED) 0건일 때만(BR-DEM-009)</li>
 *   <li>발행 트랜잭션: 이전 ISSUED → SUPERSEDED → 새 리비전 ISSUED → 참조 해석모델 freeze → {@code dem.revision.issued}</li>
 *   <li>QAR 워크플로우 대신 PM 내부 승인(10-IMPL 0.3 대안: "DEM 내부 단순 승인 플래그")</li>
 *   <li>보안 검토 2026-10-19 DEM-02: 모든 쓰기 · 읽기는 도서 · 리비전 · 의견 → 업무 패키지 → 프로젝트 범위 검사(DemAccess)</li>
 *   <li>DEM-03(c): 승인자 ≠ 리비전 작성자 · 검토 요청자 · 파일 교체자(403 DEM-403-003)</li>
 *   <li>DEM-03(d): 의견 수용(ACCEPTED) · 종결(CLOSED)은 의견 작성자 또는 검토자(dem:revision:approve 보유)만,
 *       리비전 작성자 · 파일 교체자는 불가(403 DEM-403-004)</li>
 * </ul>
 */
@Service
public class StructuralService {

    public record NewRevision(UUID fileAttachmentId, String changeSummary, UUID modelVersionId, List<UUID> dependsOn) {}

    public record ModelVersion(String software, UUID fileAttachmentId, Map<String, Object> loadCombinations,
                               String designCode, String changeSummary) {}

    public record Issue(UUID workPackageId, String purpose, List<UUID> revisionIds, UUID recipientClientId,
                        String recipientName) {}

    public record NewComment(String commentNo, String source, String severity, String body,
                             Map<String, Object> markup) {}

    private final DemJdbc dem;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;

    private final DemAccess access;

    public StructuralService(DemJdbc dem, OutboxPublisher outbox, AuditLog audit, CurrentActor actor, Clock clock,
                             DemAccess access) {
        this.access = access;
        this.dem = dem;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 도서 · 리비전

    @Transactional
    public Map<String, Object> createDocument(UUID wpId, String docNo, String title, String discipline, boolean input) {
        dem.one("업무 패키지", "SELECT id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL", wpId);
        access.requirePackage("dem:revision:write", wpId, false);
        Integer dup = dem.jdbc().queryForObject("SELECT count(*) FROM dem.document WHERE work_package_id = ? AND doc_no = ?",
                Integer.class, wpId, docNo);
        if (dup != null && dup > 0) {
            throw new DomainException(DemErrors.INVALID, "같은 도서번호가 이미 있습니다: " + docNo);
        }
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.document(id, work_package_id, doc_no, title, discipline, is_input, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)""", id, wpId, docNo, title, discipline == null ? "STR" : discipline, input,
                actor.actorIdOrSystem());
        return document(id);
    }

    /** GET /documents/{id} — DEM-02 범위 검사 후 조회 */
    @Transactional(readOnly = true)
    public Map<String, Object> readDocument(UUID documentId) {
        access.requireDocument("dem:revision:read", documentId);
        return document(documentId);
    }

    public Map<String, Object> document(UUID documentId) {
        var d = dem.one("도서", "SELECT id, work_package_id, doc_no, title, discipline, is_input FROM dem.document "
                + "WHERE id = ?", documentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.get("id"));
        m.put("workPackageId", d.get("work_package_id"));
        m.put("docNo", d.get("doc_no"));
        m.put("title", d.get("title"));
        m.put("discipline", d.get("discipline"));
        m.put("isInput", d.get("is_input"));
        m.put("revisions", dem.jdbc().queryForList("SELECT id FROM dem.document_revision WHERE document_id = ? "
                + "ORDER BY created_at, id", UUID.class, documentId).stream().map(this::revision).toList());
        m.put("unresolvedComments", unresolvedCount(List.of(documentId)));
        return m;
    }

    @Transactional
    public Map<String, Object> createRevision(UUID documentId, NewRevision r) {
        var doc = dem.one("도서", "SELECT id, work_package_id FROM dem.document WHERE id = ? FOR UPDATE", documentId);
        access.requirePackage("dem:revision:write", (UUID) doc.get("work_package_id"), false);
        if (r.modelVersionId() != null) {
            Integer ok = dem.jdbc().queryForObject("SELECT count(*) FROM dem.analysis_model_version WHERE id = ? "
                    + "AND work_package_id = ?", Integer.class, r.modelVersionId(), doc.get("work_package_id"));
            if (ok == null || ok == 0) {
                throw new DomainException(DemErrors.INVALID, "같은 업무 패키지의 해석모델 버전이 아닙니다: " + r.modelVersionId());
            }
        }
        String last = dem.jdbc().queryForList("SELECT rev_code FROM dem.document_revision WHERE document_id = ?",
                String.class, documentId).stream().max(RevCode::compareInternal).orElse(null);
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.document_revision(id, document_id, rev_code, file_attachment_id, model_version_id,
                                                  change_summary, depends_on, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?::uuid[], ?)""", id, documentId, RevCode.nextInternal(last),
                r.fileAttachmentId(), r.modelVersionId(), r.changeSummary(),
                r.dependsOn() == null ? null : DemJdbc.uuidArray(r.dependsOn()), actor.actorIdOrSystem());
        return revision(id);
    }

    public Map<String, Object> revision(UUID revisionId) {
        var r = dem.one("리비전", """
                SELECT r.id, r.document_id, d.doc_no, r.rev_code, r.issued_rev, r.status, r.file_attachment_id,
                       r.model_version_id, r.change_summary, r.needs_recheck, r.approved_by, r.transmittal_id, r.issued_at,
                       r.created_by, r.review_requested_by, r.file_replaced_by
                  FROM dem.document_revision r JOIN dem.document d ON d.id = r.document_id WHERE r.id = ?""", revisionId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("documentId", r.get("document_id"));
        m.put("docNo", r.get("doc_no"));
        m.put("revCode", r.get("rev_code"));
        m.put("issuedRev", r.get("issued_rev"));
        m.put("status", r.get("status"));
        m.put("fileAttachmentId", r.get("file_attachment_id"));
        m.put("modelVersionId", r.get("model_version_id"));
        m.put("changeSummary", r.get("change_summary"));
        m.put("needsRecheck", r.get("needs_recheck"));
        m.put("approvedBy", r.get("approved_by"));
        m.put("createdBy", r.get("created_by"));
        m.put("reviewRequestedBy", r.get("review_requested_by"));
        m.put("fileReplacedBy", r.get("file_replaced_by"));
        m.put("transmittalId", r.get("transmittal_id"));
        m.put("issuedAt", DemJdbc.inst(r.get("issued_at")));
        return m;
    }

    private Map<String, Object> lockRevision(UUID revisionId) {
        return dem.one("리비전", "SELECT id, document_id, status, model_version_id, created_by, review_requested_by, "
                + "file_replaced_by FROM dem.document_revision WHERE id = ? FOR UPDATE", revisionId);
    }

    /** WIP · 검토 중 · 승인 리비전의 파일 교체(승인본이면 다시 WIP). 발행본은 409 DEM_IMMUTABLE(TC-DEM-008) */
    @Transactional
    public Map<String, Object> replaceFile(UUID revisionId, UUID fileAttachmentId, String changeSummary) {
        access.requireRevision("dem:revision:write", revisionId);
        var r = lockRevision(revisionId);
        RevisionStatus st = RevisionStatus.valueOf((String) r.get("status"));
        if (st.published()) {
            throw immutable(revisionId);
        }
        dem.jdbc().update("""
                UPDATE dem.document_revision SET file_attachment_id = ?, change_summary = coalesce(?, change_summary),
                       status = 'WIP', approved_by = NULL, approved_at = NULL, file_replaced_by = ? WHERE id = ?""",
                fileAttachmentId, changeSummary, actor.actorIdOrSystem(), revisionId);
        return revision(revisionId);
    }

    static DomainException immutable(UUID revisionId) {
        return new DomainException(DemErrors.IMMUTABLE, "발행된 리비전은 수정 · 삭제할 수 없습니다(BR-DEM-008): " + revisionId)
                .with("reason", "DEM_IMMUTABLE");
    }

    /** A6.2 내부 흐름: request-review(WIP→INTERNAL_REVIEW) · approve(→APPROVED, PM) · reject(→WIP) */
    @Transactional
    public Map<String, Object> move(UUID revisionId, String command) {
        access.requireRevision("request-review".equals(command) ? "dem:revision:write" : "dem:revision:approve",
                revisionId);
        var r = lockRevision(revisionId);
        RevisionStatus from = RevisionStatus.valueOf((String) r.get("status"));
        RevisionStatus to = switch (command) {
            case "request-review" -> RevisionStatus.INTERNAL_REVIEW;
            case "approve" -> RevisionStatus.APPROVED;
            case "reject" -> RevisionStatus.WIP;
            default -> throw new DomainException(DemErrors.INVALID, "알 수 없는 명령: " + command);
        };
        if (from.published()) {
            throw immutable(revisionId);
        }
        from.assertCanMoveTo(to);
        boolean approve = to == RevisionStatus.APPROVED;
        UUID by = actor.actorIdOrSystem();
        if (approve && revisionAuthors(r).contains(by)) {   // DEM-03(c)
            throw new DomainException(DemErrors.SOD_VIOLATION,
                    "리비전 작성자 · 검토 요청자 · 파일 교체자는 그 리비전을 승인할 수 없습니다")
                    .with("reason", "DEM_SOD_VIOLATION");
        }
        dem.jdbc().update("UPDATE dem.document_revision SET status = ?, approved_by = ?, approved_at = ? WHERE id = ?",
                to.name(), approve ? by : null, approve ? Timestamp.from(clock.instant()) : null, revisionId);
        if (to == RevisionStatus.INTERNAL_REVIEW) {
            dem.jdbc().update("UPDATE dem.document_revision SET review_requested_by = ?, review_requested_at = ? "
                    + "WHERE id = ?", by, Timestamp.from(clock.instant()), revisionId);
        }
        audit.append("DEM", approve ? AuditLog.Action.APPROVE : AuditLog.Action.UPDATE, "document-revision", revisionId,
                Map.of("status", from.name()), Map.of("status", to.name()));
        return revision(revisionId);
    }

    /** 리비전 작성 측(작성자 · 검토 요청자 · 파일 교체자) — DEM-03(c)(d) */
    private static Set<UUID> revisionAuthors(Map<String, Object> r) {
        Set<UUID> s = new HashSet<>();
        for (String k : List.of("created_by", "review_requested_by", "file_replaced_by")) {
            if (r.get(k) != null) {
                s.add((UUID) r.get(k));
            }
        }
        return s;
    }

    // ------------------------------------------------------------------ 해석모델 버전 (FR-DEM-012)

    @Transactional
    public Map<String, Object> createModelVersion(UUID wpId, ModelVersion v) {
        dem.one("업무 패키지", "SELECT id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL FOR UPDATE", wpId);
        access.requirePackage("dem:revision:write", wpId, false);
        Integer next = dem.jdbc().queryForObject("SELECT coalesce(max(version_no), 0) + 1 FROM dem.analysis_model_version "
                + "WHERE work_package_id = ?", Integer.class, wpId);
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.analysis_model_version(id, work_package_id, version_no, software, file_attachment_id,
                                                       load_combinations, design_code, change_summary, created_by)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""", id, wpId, next, v.software(), v.fileAttachmentId(),
                dem.json(v.loadCombinations()), v.designCode(), v.changeSummary(), actor.actorIdOrSystem());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("workPackageId", wpId);
        m.put("versionNo", next);
        m.put("software", v.software());
        m.put("frozen", false);
        return m;
    }

    // ------------------------------------------------------------------ Transmittal (B5.3)

    @Transactional
    public Map<String, Object> issue(Issue c) {
        if (c.revisionIds() == null || c.revisionIds().isEmpty()) {
            throw new DomainException(DemErrors.INVALID, "발행할 리비전이 없습니다");
        }
        dem.one("업무 패키지", "SELECT id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL", c.workPackageId());
        access.requirePackage("dem:transmittal:issue", c.workPackageId(), false);
        List<UUID> ids = c.revisionIds().stream().distinct().sorted().toList();
        List<Map<String, Object>> revs = dem.jdbc().queryForList("""
                SELECT r.id, r.document_id, r.rev_code, r.status, r.model_version_id, d.doc_no, d.work_package_id
                  FROM dem.document_revision r JOIN dem.document d ON d.id = r.document_id
                 WHERE r.id = ANY(?::uuid[]) ORDER BY r.id FOR UPDATE OF r""", DemJdbc.uuidArray(ids));
        if (revs.size() != ids.size()) {
            throw new DomainException(DemErrors.NOT_FOUND, "리비전 없음(요청 %d, 존재 %d)".formatted(ids.size(), revs.size()));
        }
        Set<UUID> docs = new HashSet<>();
        for (var r : revs) {
            if (!c.workPackageId().equals(r.get("work_package_id"))) {
                throw new DomainException(DemErrors.INVALID, "다른 업무 패키지의 리비전: " + r.get("id"));
            }
            if (!docs.add((UUID) r.get("document_id"))) {
                throw new DomainException(DemErrors.INVALID, "한 Transmittal 에 같은 도서의 리비전은 1개만: " + r.get("doc_no"));
            }
            RevisionStatus st = RevisionStatus.valueOf((String) r.get("status"));
            if (st != RevisionStatus.APPROVED) {
                throw new DomainException(DemErrors.INVALID_TRANSITION,
                        "PM 승인(APPROVED)된 리비전만 발행할 수 있습니다: %s Rev.%s (%s)".formatted(r.get("doc_no"),
                                r.get("rev_code"), st)).with("reason", "DEM_REVISION_NOT_APPROVED");
            }
        }
        if ("FOR_CONSTRUCTION".equals(c.purpose())) {   // BR-DEM-009
            List<Map<String, Object>> open = unresolved(new ArrayList<>(docs));
            if (!open.isEmpty()) {
                Map<String, Long> byStatus = new LinkedHashMap<>();
                open.forEach(o -> byStatus.merge((String) o.get("status"), 1L, Long::sum));
                throw new DomainException(DemErrors.OPEN_COMMENTS_EXIST,
                        "미결 검토의견 %d건(OPEN · RESPONDED · REJECTED)이 있어 최종 발행(FOR_CONSTRUCTION)할 수 없습니다"
                                .formatted(open.size()))
                        .with("reason", "DEM_OPEN_COMMENTS_EXIST").with("openCount", open.size())
                        .with("byStatus", byStatus).with("commentIds", open.stream().map(o -> o.get("id")).toList());
            }
        }
        UUID by = actor.actorIdOrSystem();
        Instant now = clock.instant();
        UUID trId = UuidV7.next();
        String trNo = dem.nextCode("TR", LocalDate.ofInstant(now, DemProjectService.SEOUL).getYear());
        dem.jdbc().update("""
                INSERT INTO dem.transmittal(id, work_package_id, transmittal_no, purpose, recipient_client_id,
                                            recipient_name, issued_at, issued_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", trId, c.workPackageId(), trNo, c.purpose(), c.recipientClientId(),
                c.recipientName(), Timestamp.from(now), by);
        List<DemEvents.IssuedRevision> issued = new ArrayList<>();
        for (var r : revs) {
            UUID revId = (UUID) r.get("id");
            UUID docId = (UUID) r.get("document_id");
            // 이전 발행본 → SUPERSEDED (트리거: status 외 컬럼 변경 금지 — status 만 바꾼다)
            dem.jdbc().update("UPDATE dem.document_revision SET status = 'SUPERSEDED' WHERE document_id = ? "
                    + "AND status = 'ISSUED'", docId);
            Optional<String> lastIssued = dem.jdbc().queryForList("""
                    SELECT issued_rev FROM dem.document_revision WHERE document_id = ? AND issued_rev IS NOT NULL
                     ORDER BY issued_rev::int DESC LIMIT 1""", String.class, docId).stream().findFirst();
            String issuedRev = RevCode.nextIssued(lastIssued);
            dem.jdbc().update("""
                    UPDATE dem.document_revision SET status = 'ISSUED', issued_rev = ?, issued_at = ?, transmittal_id = ?
                     WHERE id = ?""", issuedRev, Timestamp.from(now), trId, revId);
            if (r.get("model_version_id") != null) {
                dem.jdbc().update("UPDATE dem.analysis_model_version SET frozen = true WHERE id = ? AND NOT frozen",
                        r.get("model_version_id"));
            }
            dem.jdbc().update("INSERT INTO dem.transmittal_item(transmittal_id, revision_id) VALUES (?, ?)", trId, revId);
            issued.add(new DemEvents.IssuedRevision(revId, (String) r.get("doc_no"), (String) r.get("rev_code"), issuedRev));
        }
        outbox.publish(new DemEvents.RevisionIssued(trId, trNo, c.workPackageId(), c.purpose(), issued));
        audit.append("DEM", AuditLog.Action.APPROVE, "transmittal", trId, null,
                Map.of("transmittalNo", trNo, "purpose", c.purpose(), "revisions", ids));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", trId);
        m.put("transmittalNo", trNo);
        m.put("purpose", c.purpose());
        m.put("issuedAt", now);
        m.put("revisions", issued);
        return m;
    }

    // ------------------------------------------------------------------ 검토의견 (A6.3, BR-DEM-009)

    /** 도서들(모든 리비전)의 미결 의견 — OPEN · RESPONDED · REJECTED */
    List<Map<String, Object>> unresolved(List<UUID> documentIds) {
        return dem.jdbc().queryForList("""
                SELECT c.id, c.status FROM dem.review_comment c JOIN dem.document_revision r ON r.id = c.revision_id
                 WHERE r.document_id = ANY(?::uuid[]) AND c.status IN ('OPEN','RESPONDED','REJECTED')
                 ORDER BY c.id""", DemJdbc.uuidArray(documentIds));
    }

    int unresolvedCount(List<UUID> documentIds) {
        return unresolved(documentIds).size();
    }

    @Transactional
    public Map<String, Object> addComment(UUID revisionId, NewComment c) {
        access.requireRevision("dem:comment:write", revisionId);
        var r = lockRevision(revisionId);
        if (RevisionStatus.valueOf((String) r.get("status")) == RevisionStatus.SUPERSEDED) {
            throw new DomainException(DemErrors.INVALID, "대체(SUPERSEDED)된 리비전에는 의견을 달 수 없습니다");
        }
        String no = c.commentNo();
        if (no == null || no.isBlank()) {
            Integer n = dem.jdbc().queryForObject("SELECT count(*) FROM dem.review_comment WHERE revision_id = ?",
                    Integer.class, revisionId);
            no = "C-%03d".formatted((n == null ? 0 : n) + 1);
        }
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.review_comment(id, revision_id, comment_no, source, severity, body, markup_json, author_id)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", id, revisionId, no, c.source() == null ? "INTERNAL" : c.source(),
                c.severity() == null ? "MINOR" : c.severity(), c.body(), dem.json(c.markup()), actor.actorIdOrSystem());
        return comment(id);
    }

    /** 회신 · 상태 변경(A6.3). 회신 기록은 append-only, OPEN → CLOSED(철회)는 사유(body) 필수 */
    @Transactional
    public Map<String, Object> reply(UUID commentId, String body, String resultingStatus) {
        access.requireComment("dem:comment:write", commentId);
        var c = dem.one("검토의견", "SELECT id, revision_id, status, author_id FROM dem.review_comment WHERE id = ? FOR UPDATE",
                commentId);
        CommentStatus from = CommentStatus.valueOf((String) c.get("status"));
        CommentStatus to;
        try {
            to = CommentStatus.valueOf(resultingStatus);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new DomainException(DemErrors.INVALID, "알 수 없는 의견 상태: " + resultingStatus);
        }
        from.assertCanMoveTo(to);
        if (body == null || body.isBlank()) {
            throw new DomainException(DemErrors.INVALID, "회신 · 사유 본문 필수");
        }
        UUID by = actor.actorIdOrSystem();
        if (to == CommentStatus.ACCEPTED || to == CommentStatus.CLOSED) {   // DEM-03(d)
            var rev = dem.one("리비전", "SELECT created_by, review_requested_by, file_replaced_by FROM dem.document_revision "
                    + "WHERE id = ?", c.get("revision_id"));
            boolean revisionAuthor = revisionAuthors(rev).contains(by);
            boolean commentAuthor = by.equals(c.get("author_id"));
            if (revisionAuthor || !(commentAuthor || access.hasPermission("dem:revision:approve"))) {
                throw new DomainException(DemErrors.COMMENT_CLOSE_NOT_ALLOWED,
                        "검토의견 수용 · 종결은 의견 작성자 또는 검토자만 할 수 있습니다(리비전 작성자 불가)")
                        .with("reason", "DEM_COMMENT_CLOSE_NOT_ALLOWED");
            }
        }
        dem.jdbc().update("""
                INSERT INTO dem.review_reply(id, comment_id, author_id, body, previous_status, resulting_status)
                VALUES (?, ?, ?, ?, ?, ?)""", UuidV7.next(), commentId, by, body, from.name(), to.name());
        dem.jdbc().update("UPDATE dem.review_comment SET status = ?, updated_at = now(), version = version + 1 WHERE id = ?",
                to.name(), commentId);
        if (to == CommentStatus.CLOSED) {
            outbox.publish(new DemEvents.CommentClosed(commentId, (UUID) c.get("revision_id"), from.name()));
        }
        return comment(commentId);
    }

    public Map<String, Object> comment(UUID commentId) {
        var c = dem.one("검토의견", """
                SELECT id, revision_id, comment_no, source, severity, body, status, author_id, version
                  FROM dem.review_comment WHERE id = ?""", commentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("revisionId", c.get("revision_id"));
        m.put("commentNo", c.get("comment_no"));
        m.put("source", c.get("source"));
        m.put("severity", c.get("severity"));
        m.put("body", c.get("body"));
        m.put("status", c.get("status"));
        m.put("unresolved", CommentStatus.valueOf((String) c.get("status")).unresolved());
        m.put("authorId", c.get("author_id"));
        m.put("replies", dem.jdbc().queryForList("""
                SELECT author_id AS "authorId", body, previous_status AS "previousStatus",
                       resulting_status AS "resultingStatus" FROM dem.review_reply WHERE comment_id = ?
                 ORDER BY created_at, id""", commentId));
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> comments(UUID revisionId) {
        access.requireRevision("dem:revision:read", revisionId);
        return dem.jdbc().queryForList("SELECT id FROM dem.review_comment WHERE revision_id = ? ORDER BY comment_no",
                UUID.class, revisionId).stream().map(this::comment).toList();
    }
}
