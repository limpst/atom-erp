package com.atomeng.erp.qar.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.application.port.QarEvidencePort;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.ReviewStatus;
import com.atomeng.erp.qar.domain.Severity;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckEngine;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AUTO-CHECK 실행 (FR-QAR-008, 07-IMPL Step 4 · 5): 기준일(오늘) 유효 규칙 세트로 RPT 리비전 컨텍스트에 규칙 15종을 돌려
 * 실행 묶음(autocheck_run)과 결과(append-only)를 남기고 체크리스트를 미리 채운다. 화면 · 가드는 항상 최신 실행만 본다.
 */
@Service
public class AutoCheckService {

    private final ReviewSupport sup;
    private final QarJdbc qar;
    private final QarRuleSetService rules;
    private final AutoCheckEngine engine;
    private final QarEvidencePort evidence;
    private final ChecklistService checklist;

    public AutoCheckService(ReviewSupport sup, QarRuleSetService rules, AutoCheckEngine engine, QarEvidencePort evidence,
                            ChecklistService checklist) {
        this.sup = sup;
        this.qar = sup.jdbc();
        this.rules = rules;
        this.engine = engine;
        this.evidence = evidence;
        this.checklist = checklist;
    }

    /** POST /reviews/{id}/autocheck-runs — 자체검토(SELF_CHECK) 단계 */
    @Transactional
    public Map<String, Object> run(UUID reviewId) {
        Map<String, Object> review = sup.reviewFor(reviewId, "qar:author:update", true);
        ReviewStatus st = ReviewStatus.valueOf(QarJdbc.str(review, "status"));
        if (st != ReviewStatus.SELF_CHECK) {
            throw new DomainException(QarCodes.STATE, "AUTO-CHECK 는 자체검토(SELF_CHECK) 단계에서 실행합니다: " + st);
        }
        Map<String, Object> round = sup.currentRound(review);
        UUID runId = execute(review, round, sup.today());
        return runView(runId);
    }

    UUID execute(Map<String, Object> review, Map<String, Object> round, LocalDate asOf) {
        long t0 = System.nanoTime();
        QarRuleSetService.RuleSet set = rules.ruleSetAsOf(asOf);
        UUID revisionId = QarJdbc.uuid(round, "revision_id");
        RevisionContent content = sup.content(revisionId);
        ReviewContext ctx = context(review, content, asOf);
        List<AutoCheckEngine.Result> results = engine.run(ctx, set.defs());
        Map<Severity, Integer> counts = AutoCheckEngine.counts(results);
        UUID runId = UuidV7.next();
        UUID by = sup.actorId();
        qar.jdbc().update("""
                INSERT INTO qar.autocheck_run(id, round_id, rule_set_id, rule_version, as_of, content_hash, blocking_count,
                                              warning_count, info_count, duration_ms, ran_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", runId, round.get("id"), set.id(), set.version(),
                QarJdbc.d(asOf), content.revision().contentHash(), counts.get(Severity.BLOCKING),
                counts.get(Severity.WARNING), counts.get(Severity.INFO),
                (int) ((System.nanoTime() - t0) / 1_000_000), by);
        Map<String, Integer> nonInfo = new HashMap<>();
        for (AutoCheckEngine.Result r : results) {
            qar.jdbc().update("""
                    INSERT INTO qar.autocheck_result(id, round_id, run_id, rule_id, severity, report_anchor, source_ref,
                                                     expected, actual, message)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""", UuidV7.next(), round.get("id"), runId,
                    r.ruleId(), r.severity().name(), QarJdbc.json(r.finding().reportAnchor()),
                    QarJdbc.json(r.finding().sourceRef()), r.finding().expected(), r.finding().actual(),
                    r.finding().message());
            if (r.severity() != Severity.INFO) {
                nonInfo.merge(r.ruleId(), 1, Integer::sum);
            }
        }
        checklist.prefill(QarJdbc.uuid(round, "id"), checklist.template(round, QarJdbc.str(review, "report_type")).id(),
                nonInfo, by);
        return runId;
    }

    ReviewContext context(Map<String, Object> review, RevisionContent content, LocalDate asOf) {
        List<UUID> participants = sup.sectionEditors(content.revision().revisionId());
        var ev = evidence.evidence(new QarEvidencePort.Query(QarJdbc.uuid(review, "project_id"),
                content.revision().revisionId(), content.revision().facilityId(), participants, asOf));
        return new ReviewContext(content, asOf, ev == null ? ReviewContext.ExternalEvidence.NONE : ev);
    }

    public Optional<Map<String, Object>> latestRun(UUID roundId) {
        return qar.jdbc().queryForList("SELECT * FROM qar.autocheck_run WHERE round_id = ? ORDER BY ran_at DESC, id DESC "
                + "LIMIT 1", roundId).stream().findFirst();
    }

    /** 최신 실행의 BLOCKING 결과 id (waiver 불가) */
    List<UUID> blockingOfLatest(UUID roundId) {
        return latestRun(roundId).map(r -> qar.jdbc().queryForList("SELECT id FROM qar.autocheck_result WHERE run_id = ? "
                + "AND severity = 'BLOCKING' ORDER BY rule_id, id", UUID.class, r.get("id"))).orElse(List.of());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> latestResults(UUID roundId) {
        Map<String, Object> round = sup.round(roundId);
        sup.reviewOfRound(round, "qar:review:read");
        return latestRun(roundId).map(r -> runView(QarJdbc.uuid(r, "id")))
                .orElseThrow(() -> new DomainException(QarCodes.NOT_FOUND, "AUTO-CHECK 실행 기록이 없습니다"));
    }

    Map<String, Object> runView(UUID runId) {
        Map<String, Object> r = qar.jdbc().queryForMap("SELECT * FROM qar.autocheck_run WHERE id = ?", runId);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("runId", runId);
        v.put("roundId", r.get("round_id"));
        v.put("ruleVersion", r.get("rule_version"));
        v.put("asOf", r.get("as_of").toString());
        v.put("contentHash", QarJdbc.str(r, "content_hash"));
        v.put("blocking", r.get("blocking_count"));
        v.put("warning", r.get("warning_count"));
        v.put("info", r.get("info_count"));
        v.put("durationMs", r.get("duration_ms"));
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> x : qar.jdbc().queryForList("""
                SELECT r.*, w.status AS waiver_status FROM qar.autocheck_result r
                  LEFT JOIN qar.waiver w ON w.autocheck_result_id = r.id
                 WHERE r.run_id = ? ORDER BY CASE r.severity WHEN 'BLOCKING' THEN 0 WHEN 'WARNING' THEN 1 ELSE 2 END,
                       r.rule_id, r.created_at, r.id""", runId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", x.get("id"));
            m.put("ruleId", x.get("rule_id"));
            m.put("severity", x.get("severity"));
            m.put("message", x.get("message"));
            m.put("expected", x.get("expected"));
            m.put("actual", x.get("actual"));
            m.put("reportAnchor", QarJdbc.tree(x.get("report_anchor")));
            m.put("sourceRef", QarJdbc.tree(x.get("source_ref")));
            m.put("waiver", x.get("waiver_status"));
            results.add(m);
        }
        v.put("results", results);
        return v;
    }
}
