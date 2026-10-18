package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.rpt.api.RptRevisionQuery;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** QAR 공통 — 검토 · 라운드 조회/잠금, RPT 공개 조회, 실행자 · 시계, 독립 트랜잭션 감사(거부 기록) */
@Component
public class ReviewSupport {

    public static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final QarJdbc qar;
    private final RptRevisionQuery rpt;
    private final CurrentActor actor;
    private final Clock clock;
    private final QarAccess access;
    private final AuditLog audit;
    private final TransactionTemplate requiresNew;

    public ReviewSupport(QarJdbc qar, RptRevisionQuery rpt, CurrentActor actor, Clock clock, QarAccess access,
                         AuditLog audit, PlatformTransactionManager tm) {
        this.qar = qar;
        this.rpt = rpt;
        this.actor = actor;
        this.clock = clock;
        this.access = access;
        this.audit = audit;
        this.requiresNew = new TransactionTemplate(tm);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public QarJdbc jdbc() {
        return qar;
    }

    public RptRevisionQuery rpt() {
        return rpt;
    }

    public QarAccess access() {
        return access;
    }

    public AuditLog audit() {
        return audit;
    }

    public UUID actorId() {
        return actor.actorIdOrSystem();
    }

    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    public Clock clock() {
        return clock;
    }

    public Map<String, Object> review(UUID reviewId, boolean forUpdate) {
        return forUpdate ? qar.lock("review", reviewId) : qar.get("review", reviewId);
    }

    /** 검토 + 프로젝트 범위 확인 */
    public Map<String, Object> reviewFor(UUID reviewId, String permission, boolean forUpdate) {
        Map<String, Object> r = review(reviewId, forUpdate);
        access.requireProject(permission, QarJdbc.uuid(r, "project_id"));
        return r;
    }

    public Map<String, Object> round(UUID roundId) {
        return qar.get("review_round", roundId);
    }

    public Map<String, Object> currentRound(Map<String, Object> review) {
        return qar.jdbc().queryForList("SELECT * FROM qar.review_round WHERE review_id = ? AND round_no = ?",
                review.get("id"), review.get("current_round")).stream().findFirst()
                .orElseThrow(() -> QarJdbc.notFound("review_round", review.get("id")));
    }

    /** 라운드 → 검토(프로젝트 범위 확인) */
    public Map<String, Object> reviewOfRound(Map<String, Object> round, String permission) {
        return reviewFor(QarJdbc.uuid(round, "review_id"), permission, false);
    }

    public RevisionInfo revision(UUID revisionId) {
        return rpt.revision(revisionId).orElseThrow(() -> new DomainException(QarCodes.NOT_FOUND,
                "RPT 리비전 없음: " + revisionId));
    }

    public RevisionContent content(UUID revisionId) {
        return rpt.content(revisionId).orElseThrow(() -> new DomainException(QarCodes.NOT_FOUND,
                "RPT 리비전 없음: " + revisionId));
    }

    /** 이 라운드 대상 리비전의 섹션 편집 이력자(자동 생성 실행자 포함) — BR-QAR-002 SoD */
    public List<UUID> sectionEditors(UUID revisionId) {
        return rpt.sectionEditors(revisionId).stream().flatMap(s -> s.editors().stream())
                .map(RptRevisionQuery.Editor::editorId).distinct().toList();
    }

    /** 요청이 실패(롤백)해도 남겨야 하는 감사 기록 — 별도 트랜잭션(TC-QAR-002 SoD 위반 기록) */
    public void auditIndependently(AuditLog.Action action, String targetType, UUID targetId, Object before,
                                   Object after) {
        requiresNew.executeWithoutResult(s -> audit.append("QAR", action, targetType, targetId, before, after));
    }
}
