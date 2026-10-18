package com.atomeng.erp.qar.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.Role;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.QarCodes;
import com.atomeng.erp.qar.domain.ReviewStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 체크리스트 (FR-QAR-006 · 007): 라운드 고정 템플릿 문항 + 단계별 응답. AUTO-CHECK 실행 결과로 연계 문항을 미리 채운다
 * (결과 0건 → PASS, 1건 이상 → FAIL, INFO 제외 — 07-IMPL Step 3 Prefiller). 사람이 고친 응답은 덮어쓰지 않는다.
 * 화면 문구는 "자동 PASS(사람 확인 권장)".
 */
@Service
public class ChecklistService {

    public enum Stage { SELF, QA }

    private final ReviewSupport sup;
    private final QarJdbc qar;
    private final QarRuleSetService rules;

    public ChecklistService(ReviewSupport sup, QarRuleSetService rules) {
        this.sup = sup;
        this.qar = sup.jdbc();
        this.rules = rules;
    }

    QarRuleSetService.Template template(Map<String, Object> round, String reportType) {
        return rules.templateByLabel(rules.versionsFor(round, reportType, sup.today()).get("CHECKLIST"));
    }

    List<Map<String, Object>> items(UUID templateId) {
        return qar.jdbc().queryForList("SELECT * FROM qar.checklist_item WHERE template_id = ? ORDER BY item_no",
                templateId);
    }

    static boolean inStage(String itemStage, Stage s) {
        return "BOTH".equals(itemStage) || itemStage.equals(s.name());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> view(UUID roundId) {
        Map<String, Object> round = sup.round(roundId);
        Map<String, Object> review = sup.reviewOfRound(round, "qar:review:read");
        QarRuleSetService.Template t = template(round, QarJdbc.str(review, "report_type"));
        Map<String, Map<String, Object>> answers = new LinkedHashMap<>();
        qar.jdbc().queryForList("SELECT * FROM qar.checklist_response WHERE round_id = ?", roundId)
                .forEach(a -> answers.put(a.get("item_id") + ":" + a.get("stage"), a));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> i : items(t.id())) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("itemId", i.get("id"));
            v.put("itemNo", i.get("item_no"));
            v.put("category", i.get("category"));
            v.put("question", i.get("question"));
            v.put("legalBasis", i.get("legal_basis"));
            v.put("criticality", i.get("criticality"));
            v.put("autocheckRuleId", i.get("autocheck_rule_id"));
            v.put("stage", i.get("stage"));
            for (Stage s : Stage.values()) {
                Map<String, Object> a = answers.get(i.get("id") + ":" + s.name());
                if (a != null) {
                    Map<String, Object> ans = new LinkedHashMap<>();
                    ans.put("answer", a.get("answer"));
                    ans.put("autoPrefilled", a.get("auto_prefilled"));
                    ans.put("label", Boolean.TRUE.equals(a.get("auto_prefilled")) ? "자동 " + a.get("answer")
                            + "(사람 확인 권장)" : String.valueOf(a.get("answer")));
                    ans.put("note", a.get("note"));
                    ans.put("answeredBy", a.get("answered_by"));
                    v.put(s.name().toLowerCase(), ans);
                }
            }
            out.add(v);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("roundId", roundId);
        r.put("template", t.label());
        r.put("items", out);
        r.put("missingSelf", missing(roundId, t.id(), Stage.SELF));
        r.put("missingQa", missing(roundId, t.id(), Stage.QA));
        return r;
    }

    /**
     * 응답. SELF = 보고서 작성 측(PM · ENG, 자체검토 단계), QA = 이 라운드 QA 담당자(QA 검토 단계).
     */
    @Transactional
    public Map<String, Object> answer(UUID roundId, UUID itemId, Stage stage, String answer, String note) {
        Map<String, Object> round = sup.round(roundId);
        Map<String, Object> review = sup.reviewFor(QarJdbc.uuid(round, "review_id"), "qar:answer:update", true);
        if (QarJdbc.integer(review, "current_round") != QarJdbc.integer(round, "round_no")) {
            throw new DomainException(QarCodes.STATE, "지난 라운드의 체크리스트는 고칠 수 없습니다");
        }
        ReviewStatus st = ReviewStatus.valueOf(QarJdbc.str(review, "status"));
        if (!Set.of("PASS", "FAIL", "NA").contains(answer)) {
            throw new DomainException(QarCodes.INVALID, "answer 는 PASS · FAIL · NA");
        }
        UUID actor = sup.actorId();
        if (stage == Stage.SELF) {
            if (st != ReviewStatus.SELF_CHECK) {
                throw new DomainException(QarCodes.STATE, "자체검토 체크리스트는 SELF_CHECK 단계에서만: " + st);
            }
            if (!sup.access().has(Role.PM) && !sup.access().has(Role.ENG) && !sup.access().roles().isEmpty()) {
                throw new DomainException(QarCodes.NOT_AUTHOR_SIDE, "자체검토는 보고서 작성 측(PM · ENG)만");
            }
        } else {
            if (st != ReviewStatus.QA_REVIEWING) {
                throw new DomainException(QarCodes.STATE, "QA 체크리스트는 QA_REVIEWING 단계에서만: " + st);
            }
            UUID assignee = activeAssignee(roundId, "QA_REVIEW");
            if (!actor.equals(assignee)) {
                throw new DomainException(QarCodes.NOT_ASSIGNEE, "이 라운드 QA 담당자만 판정할 수 있습니다");
            }
        }
        Map<String, Object> item = qar.get("checklist_item", itemId);
        QarRuleSetService.Template t = template(round, QarJdbc.str(review, "report_type"));
        if (!QarJdbc.uuid(item, "template_id").equals(t.id()) || !inStage(QarJdbc.str(item, "stage"), stage)) {
            throw new DomainException(QarCodes.INVALID, "이 라운드 · 단계의 문항이 아닙니다: " + item.get("item_no"));
        }
        qar.jdbc().update("""
                INSERT INTO qar.checklist_response(id, round_id, item_id, stage, answer, auto_prefilled, note, answered_by)
                VALUES (?, ?, ?, ?, ?, false, ?, ?)
                ON CONFLICT (round_id, item_id, stage) DO UPDATE SET answer = EXCLUDED.answer, auto_prefilled = false,
                       note = EXCLUDED.note, answered_by = EXCLUDED.answered_by, answered_at = now()""",
                UuidV7.next(), roundId, itemId, stage.name(), answer, note, actor);
        return view(roundId);
    }

    UUID activeAssignee(UUID roundId, String step) {
        return qar.jdbc().queryForList("SELECT assignee_id FROM qar.review_assignment WHERE round_id = ? "
                + "AND step_code = ? AND completed_at IS NULL", UUID.class, roundId, step).stream().findFirst().orElse(null);
    }

    /** AUTO-CHECK 결과로 연계 문항 미리 채움(두 단계 모두, 사람 응답은 유지) */
    void prefill(UUID roundId, UUID templateId, Map<String, Integer> nonInfoCountByRule, UUID by) {
        for (Map<String, Object> i : items(templateId)) {
            String rule = QarJdbc.str(i, "autocheck_rule_id");
            if (rule == null) {
                continue;
            }
            int n = nonInfoCountByRule.getOrDefault(rule, 0);
            for (Stage s : Stage.values()) {
                if (!inStage(QarJdbc.str(i, "stage"), s)) {
                    continue;
                }
                qar.jdbc().update("""
                        INSERT INTO qar.checklist_response(id, round_id, item_id, stage, answer, auto_prefilled, note, answered_by)
                        VALUES (?, ?, ?, ?, ?, true, ?, ?)
                        ON CONFLICT (round_id, item_id, stage) DO UPDATE SET answer = EXCLUDED.answer,
                               note = EXCLUDED.note, answered_by = EXCLUDED.answered_by, answered_at = now()
                         WHERE qar.checklist_response.auto_prefilled""",
                        UuidV7.next(), roundId, i.get("id"), s.name(), n == 0 ? "PASS" : "FAIL",
                        "AUTO-CHECK %s 결과 %d건".formatted(rule, n), by);
            }
        }
    }

    /** 미응답 문항 번호 */
    List<String> missing(UUID roundId, UUID templateId, Stage stage) {
        return qar.jdbc().queryForList("""
                SELECT i.item_no FROM qar.checklist_item i
                 WHERE i.template_id = ? AND (i.stage = 'BOTH' OR i.stage = ?)
                   AND NOT EXISTS (SELECT 1 FROM qar.checklist_response r WHERE r.round_id = ? AND r.item_id = i.id
                                                                          AND r.stage = ?)
                 ORDER BY i.item_no""", String.class, templateId, stage.name(), roundId, stage.name());
    }
}
