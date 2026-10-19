package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.api.QarEvents;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.ReviewStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토자 배정 · SoD (FR-QAR-003, BR-QAR-002, B7.2).
 * <ul>
 *   <li>SoD 하드 필터: 보고서 작성자(검토 생성자 · 라운드 제출자) · <b>섹션 편집 이력자</b>(rpt::api
 *       {@code RptRevisionQuery.sectionEditors}, 자동 생성 실행자 포함) · 같은 라운드 승인 단계 담당자</li>
 *   <li>자동 배정 점수 = 0.45 전문성(유형별 과거 검토 건수 정규화) + 0.35 여유도(1 − 미완료/최대) + 0.10 연속성 + 0.10 지터
 *       (시드 = roundId → 재현 가능), 동점은 최근 배정이 오래된 사람. 근거는 score_detail 저장</li>
 *   <li>후보 없음 → 배정 보류(QA 팀장 수동 배정), 수동 배정도 SoD 재검사 — 위반 시 403 + 감사 로그(TC-QAR-002)</li>
 * </ul>
 */
@Service
public class AssignmentService {

    private static final Logger log = LoggerFactory.getLogger(AssignmentService.class);

    private final ReviewSupport sup;
    private final QarJdbc qar;
    private final OutboxPublisher outbox;

    public AssignmentService(ReviewSupport sup, OutboxPublisher outbox) {
        this.sup = sup;
        this.qar = sup.jdbc();
        this.outbox = outbox;
    }

    /** SoD 로 제외되는 사람과 사유 */
    Map<UUID, String> excluded(Map<String, Object> review, Map<String, Object> round, String step) {
        Map<UUID, String> ex = new LinkedHashMap<>();
        ex.put(QarJdbc.uuid(review, "created_by"), "AUTHOR");
        if (round.get("submitted_by") != null) {
            ex.putIfAbsent(QarJdbc.uuid(round, "submitted_by"), "AUTHOR");
        }
        for (UUID e : sup.sectionEditors(QarJdbc.uuid(round, "revision_id"))) {
            ex.putIfAbsent(e, "SECTION_EDITOR");
        }
        qar.jdbc().queryForList("SELECT DISTINCT assignee_id FROM qar.review_assignment WHERE round_id = ? AND step_code <> ?",
                UUID.class, round.get("id"), step).forEach(a -> ex.putIfAbsent(a, "OTHER_STEP_ASSIGNEE"));
        return ex;
    }

    /** 효과 ASSIGN_QA — 자동 배정(후보 없으면 보류) */
    Optional<UUID> autoAssign(Map<String, Object> review, Map<String, Object> round, String step, Integer slaHours) {
        Map<UUID, String> ex = excluded(review, round, step);
        String type = QarJdbc.str(review, "report_type");
        List<Map<String, Object>> cands = qar.jdbc().queryForList("""
                SELECT p.employee_id, p.max_open, p.expertise::text AS expertise,
                       (SELECT count(*) FROM qar.review_assignment a WHERE a.assignee_id = p.employee_id
                                                                     AND a.completed_at IS NULL) AS open_count,
                       (SELECT max(a.created_at) FROM qar.review_assignment a WHERE a.assignee_id = p.employee_id) AS last_at
                  FROM qar.reviewer_profile p WHERE p.active AND p.step_code = ? ORDER BY p.employee_id""", step);
        UUID previous = qar.jdbc().queryForList("""
                SELECT a.assignee_id FROM qar.review_assignment a JOIN qar.review_round r ON r.id = a.round_id
                 WHERE r.review_id = ? AND a.step_code = ? AND r.round_no < ? ORDER BY r.round_no DESC LIMIT 1""",
                UUID.class, review.get("id"), step, round.get("round_no")).stream().findFirst().orElse(null);
        int maxExpertise = 1;
        for (Map<String, Object> c : cands) {
            maxExpertise = Math.max(maxExpertise, QarJdbc.tree(c.get("expertise")).path(type).asInt(0));
        }
        Random jitter = new Random(QarJdbc.uuid(round, "id").getMostSignificantBits());
        record Scored(UUID id, double score, Map<String, Object> detail, Instant lastAt) {}
        List<Scored> scored = new ArrayList<>();
        for (Map<String, Object> c : cands) {
            UUID id = QarJdbc.uuid(c, "employee_id");
            double j = jitter.nextDouble();   // 후보 순서 고정 → 같은 라운드는 같은 지터
            if (ex.containsKey(id)) {
                continue;
            }
            int open = ((Number) c.get("open_count")).intValue();
            int max = QarJdbc.integer(c, "max_open");
            if (open >= max) {
                continue;
            }
            double expertise = (double) QarJdbc.tree(c.get("expertise")).path(type).asInt(0) / maxExpertise;
            double capacity = 1.0 - (double) open / max;
            double continuity = id.equals(previous) ? 1 : 0;
            double score = 0.45 * expertise + 0.35 * capacity + 0.10 * continuity + 0.10 * j;
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("expertise", round4(expertise));
            d.put("capacity", round4(capacity));
            d.put("continuity", continuity);
            d.put("jitter", round4(j));
            d.put("score", round4(score));
            d.put("excludedBySoD", ex.size());
            scored.add(new Scored(id, score, d, QarJdbc.instant(c.get("last_at"))));
        }
        Optional<Scored> best = scored.stream().max(Comparator.comparingDouble(Scored::score)
                .thenComparing(s -> s.lastAt() == null ? Instant.MIN : s.lastAt(), Comparator.reverseOrder()));
        if (best.isEmpty()) {
            log.info("QA 자동 배정 후보 없음(SoD · 부하) — 수동 배정 필요 round={}", round.get("id"));
            return Optional.empty();
        }
        insert(review, round, step, best.get().id(), "AUTO", best.get().detail(), slaHours);
        return Optional.of(best.get().id());
    }

    private void insert(Map<String, Object> review, Map<String, Object> round, String step, UUID assignee, String by,
                        Map<String, Object> detail, Integer slaHours) {
        Instant due = slaHours == null ? null : sup.now().plus(Duration.ofHours(slaHours));
        qar.jdbc().update("""
                INSERT INTO qar.review_assignment(id, round_id, step_code, assignee_id, assigned_by, score_detail, due_at,
                                                  started_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)""", UuidV7.next(), round.get("id"), step, assignee, by,
                QarJdbc.json(detail), QarJdbc.ts(due), QarJdbc.ts(sup.now()));
        outbox.publish(new QarEvents.ReviewAssigned(QarJdbc.uuid(review, "id"), QarJdbc.uuid(round, "id"), step,
                assignee, by, due));
    }

    /** POST /rounds/{roundId}/assignments — QA 팀장 수동 배정 · 재배정 (SoD 재검사) */
    @Transactional
    public Map<String, Object> manual(UUID roundId, String step, UUID assigneeId) {
        Map<String, Object> round = sup.round(roundId);
        Map<String, Object> review = sup.reviewFor(QarJdbc.uuid(round, "review_id"), "qar:assignment:update", true);
        if (!"QA_REVIEW".equals(step)) {
            throw new DomainException(QarCodes.INVALID, "수동 배정 단계는 QA_REVIEW 만 지원합니다");
        }
        if (QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")
                || ReviewStatus.valueOf(QarJdbc.str(review, "status")) != ReviewStatus.QA_REVIEWING) {
            throw new DomainException(QarCodes.STATE, "QA 검토 중인 현재 라운드만 배정할 수 있습니다");
        }
        Map<UUID, String> ex = excluded(review, round, step);
        if (ex.containsKey(assigneeId)) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("violation", "QAR_SOD_VIOLATION");
            after.put("reason", ex.get(assigneeId));
            after.put("assigneeId", assigneeId);
            after.put("step", step);
            sup.auditIndependently(AuditLog.Action.UPDATE, "ReviewAssignment", roundId, null, after);
            throw new DomainException(QarCodes.SOD_VIOLATION,
                    "직무 분리 위반: 작성자 · 섹션 편집 이력자는 이 라운드 QA 검토자가 될 수 없습니다(" + ex.get(assigneeId) + ")")
                    .with("reason", "QAR_SOD_VIOLATION").with("sodRule", ex.get(assigneeId));
        }
        // SEC-QAR-02: 수동 배정 대상도 그 단계의 활성 검토자 프로필이어야 한다(자동 배정 후보와 같은 기준)
        Integer profiles = qar.jdbc().queryForObject(
                "SELECT count(*) FROM qar.reviewer_profile WHERE employee_id = ? AND step_code = ? AND active",
                Integer.class, assigneeId, step);
        if (profiles == null || profiles == 0) {
            throw new DomainException(QarCodes.INVALID, "활성 QA 검토자 프로필이 없는 직원은 배정할 수 없습니다")
                    .with("reason", "NOT_A_REVIEWER");
        }
        qar.jdbc().update("""
                UPDATE qar.review_assignment SET completed_at = now(), decision = 'REASSIGNED'
                 WHERE round_id = ? AND step_code = ? AND completed_at IS NULL""", roundId, step);
        insert(review, round, step, assigneeId, "MANUAL", Map.of("manualBy", sup.actorId()), null);
        sup.audit().append("QAR", AuditLog.Action.UPDATE, "ReviewAssignment", roundId, null,
                Map.of("step", step, "assigneeId", assigneeId, "assignedBy", "MANUAL"));
        return assignments(roundId).get(assignments(roundId).size() - 1);
    }

    List<Map<String, Object>> assignments(UUID roundId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> a : qar.jdbc().queryForList(
                "SELECT * FROM qar.review_assignment WHERE round_id = ? ORDER BY created_at, id", roundId)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", a.get("id"));
            v.put("step", a.get("step_code"));
            v.put("assigneeId", a.get("assignee_id"));
            v.put("assignedBy", a.get("assigned_by"));
            v.put("scoreDetail", QarJdbc.tree(a.get("score_detail")));
            v.put("dueAt", QarJdbc.instant(a.get("due_at")));
            v.put("completedAt", QarJdbc.instant(a.get("completed_at")));
            v.put("decision", a.get("decision"));
            out.add(v);
        }
        return out;
    }

    /**
     * 이 검토(모든 라운드)에서 QA 단계를 맡았던 모든 사람(재배정 포함) — 승인자 SoD.
     * 보안 검토 2026-10-19 SEC-QAR-01: 이전에는 재배정된 QA 와 이전 라운드 QA 가 빠져 있어, QA 로 검토한 뒤
     * 재배정되거나 다음 라운드로 넘어가면 같은 사람이 최종 승인할 수 있었다.
     */
    Set<UUID> qaAssignees(UUID roundId) {
        return new LinkedHashSet<>(qar.jdbc().queryForList("""
                SELECT DISTINCT a.assignee_id FROM qar.review_assignment a
                  JOIN qar.review_round r ON r.id = a.round_id
                 WHERE a.step_code = 'QA_REVIEW'
                   AND r.review_id = (SELECT review_id FROM qar.review_round WHERE id = ?)""", UUID.class, roundId));
    }

    private static double round4(double v) {
        return Math.round(v * 10_000) / 10_000.0;
    }
}
