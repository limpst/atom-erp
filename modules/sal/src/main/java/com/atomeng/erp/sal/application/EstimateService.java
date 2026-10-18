package com.atomeng.erp.sal.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.sal.domain.DiscountPolicy;
import com.atomeng.erp.sal.domain.EstimateStatus;
import com.atomeng.erp.sal.domain.OpportunityStage;
import com.atomeng.erp.sal.domain.SalCodes;
import com.atomeng.erp.sal.domain.fee.FeeEngine;
import com.atomeng.erp.sal.domain.fee.FeeModels.FacilityProfile;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcCommand;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcResult;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeLine;
import com.atomeng.erp.sal.domain.fee.FeeRuleSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 견적 (FR-SAL-011·012, BR-SAL-002·006, NFR-SAL-05, 01-IMPL Step 6).
 * <ul>
 *   <li>버전: 기회당 견적번호 1개(EST-yyyy-nnnn), 새 버전마다 version_no+1, 이전 열린 버전은 SUPERSEDED</li>
 *   <li>스냅샷: 규칙 본문 · 시설물 규모 · 산정 근거를 저장 — 규칙이 바뀌어도 재계산 안 함(BR-SAL-002)</li>
 *   <li>할인: :issue 때 발행자의 역할 · 그룹 한도(atom.sal.discount-limits) 초과면 PENDING_APPROVAL(FR-SAL-012),
 *       EXEC :approve-discount(If-Match) 로 approved_by 기록 뒤 :issue → ISSUED, 반려면 DRAFT. EXEC 한도 49%, DB CHECK &lt; 50%</li>
 *   <li>불변: ISSUED 이후(및 승인 이후) 금액 · 요율 · 스냅샷 · 라인 변경은 DB 트리거가 SAL-409-010 으로 거부</li>
 * </ul>
 */
@Service
public class EstimateService {

    public record CreateVersion(FeeCalcCommand calc, FacilityProfile facility, BigDecimal discountRate,
                                Integer validDays) {}

    private final SalJdbc sal;
    private final FeeService fees;
    private final FeeEngine engine;
    private final DiscountPolicy policy;
    private final OpportunityService opportunities;
    private final SalProperties props;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final ObjectMapper om;
    private final Clock clock;

    public EstimateService(SalJdbc sal, FeeService fees, FeeEngine engine, DiscountPolicy policy,
                           OpportunityService opportunities, SalProperties props, OutboxPublisher outbox,
                           AuditLog audit, CurrentActor actor, ObjectMapper om, Clock clock) {
        this.sal = sal;
        this.fees = fees;
        this.engine = engine;
        this.policy = policy;
        this.opportunities = opportunities;
        this.props = props;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.om = om;
        this.clock = clock;
    }

    /** 제안가 = 합계 × (1 − 할인율), 천 원 미만 절사 (01-IMPL Step 2 Estimate.applyDiscount) */
    public static BigDecimal offerOf(BigDecimal subtotal, BigDecimal discountRate) {
        return FeeRuleSet.Rounding.FLOOR_1000.apply(subtotal.multiply(BigDecimal.ONE.subtract(discountRate)))
                .max(BigDecimal.ZERO);
    }

    @Transactional
    public Map<String, Object> createVersion(UUID opportunityId, CreateVersion c) {
        BigDecimal rate = c.discountRate() == null ? BigDecimal.ZERO : c.discountRate();
        DiscountPolicy.requireValidRate(rate);
        Map<String, Object> opp = sal.lock("opportunity", opportunityId);
        OpportunityStage stage = OpportunityStage.valueOf(SalJdbc.str(opp, "stage"));
        if (!stage.allowsEstimate()) {
            throw new DomainException(SalCodes.STAGE_NOT_ALLOWED,
                    "견적은 QUALIFYING · PROPOSING · NEGOTIATING 단계에서만 작성합니다: " + stage);
        }
        FeeCalcCommand calc = c.calc().serviceType() == null
                ? new FeeCalcCommand(c.calc().asOfDate(), SalJdbc.str(opp, "service_type"), c.calc().overheadRate(),
                        c.calc().techFeeRate(), c.calc().expenses(), c.calc().manDayOverrides())
                : c.calc();
        FeeService.ResolvedRule rr = fees.resolve(calc.asOfDate());
        FeeCalcResult r = engine.calculate(calc, rr.rule(), c.facility());

        UUID by = actor.actorIdOrSystem();
        List<Map<String, Object>> prev = sal.jdbc().queryForList("""
                SELECT id, estimate_no, version_no, status FROM sal.estimate
                 WHERE opportunity_id = ? AND deleted_at IS NULL ORDER BY version_no DESC LIMIT 1 FOR UPDATE""",
                opportunityId);
        String estimateNo;
        int versionNo;
        if (prev.isEmpty()) {
            estimateNo = sal.nextDocNo("EST", opportunities.today().getYear());
            versionNo = 1;
        } else {
            estimateNo = SalJdbc.str(prev.get(0), "estimate_no");
            versionNo = ((Number) prev.get(0).get("version_no")).intValue() + 1;
            // 이전 열린 버전(DRAFT · PENDING · ISSUED · SENT) → SUPERSEDED (금액은 트리거로 불변 유지)
            sal.jdbc().update("""
                    UPDATE sal.estimate SET status = 'SUPERSEDED', version = version + 1, updated_at = now(), updated_by = ?
                     WHERE opportunity_id = ? AND status IN ('DRAFT','PENDING_APPROVAL','ISSUED','SENT')""",
                    by, opportunityId);
        }
        BigDecimal offer = offerOf(r.subtotal(), rate);
        BigDecimal vat = FeeEngine.vatOf(offer);
        int validDays = c.validDays() == null ? props.estimateValidDays() : c.validDays();
        UUID id = UuidV7.next();
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("explanations", r.explanations());
        trace.put("warnings", r.warnings());
        trace.put("command", calc);
        sal.jdbc().update("""
                INSERT INTO sal.estimate(id, estimate_no, version_no, opportunity_id, service_type, as_of_date,
                                         fee_rule_set_id, rule_version, rule_snapshot, facility_snapshot, calc_trace,
                                         direct_labor, direct_expense, overhead_rate, overhead, tech_fee_rate, tech_fee,
                                         subtotal, discount_rate, offer_amount, vat, valid_until, status,
                                         created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?, ?)""",
                id, estimateNo, versionNo, opportunityId, calc.serviceType(), SalJdbc.d(calc.asOfDate()), rr.id(),
                rr.ruleVersion(), rr.body().toString(), json(c.facility()), json(trace), r.directLabor(),
                r.directExpense(), r.overheadRate(), r.overhead(), r.techFeeRate(), r.techFee(), r.subtotal(), rate,
                offer, vat, SalJdbc.d(opportunities.today().plusDays(validDays)), by, by);
        int seq = 1;
        for (FeeLine l : r.lines()) {
            sal.jdbc().update("""
                    INSERT INTO sal.estimate_line(id, estimate_id, seq, line_type, work_item, grade, base_man_days, adj_factor,
                                                  man_days, unit_price, quantity, amount)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                    UuidV7.next(), id, seq++, l.lineType(), l.workItem(), l.grade(), l.baseManDays(), l.adjFactor(),
                    l.manDays(), l.unitPrice(), l.quantity(), l.amount());
        }
        Map<String, Object> v = get(id);
        audit.append("SAL", AuditLog.Action.CREATE, "estimate", id, null, amounts(v));
        return v;
    }

    /**
     * POST /estimates/{id}:issue — 발행자 역할 · 그룹 한도 초과 + 미승인이면 PENDING_APPROVAL(발송 차단, TC-SAL-013),
     * 아니면 ISSUED + sal.estimate.issued. EXEC 승인(approved_by) 이 있으면 바로 발행(TC-SAL-016).
     */
    @Transactional
    public Map<String, Object> issue(UUID id, Set<String> roles) {
        Map<String, Object> e = sal.lock("estimate", id);
        EstimateStatus st = EstimateStatus.valueOf(SalJdbc.str(e, "status"));
        if (st != EstimateStatus.DRAFT && st != EstimateStatus.PENDING_APPROVAL) {
            throw new DomainException(SalCodes.ESTIMATE_STATE, "발행할 수 없는 상태: " + st);
        }
        UUID by = actor.actorIdOrSystem();
        Set<String> rolesAndGroups = new HashSet<>(roles);
        rolesAndGroups.addAll(sal.groupsOf(by));
        BigDecimal rate = SalJdbc.dec(e, "discount_rate");
        BigDecimal limit = policy.limitFor(rolesAndGroups);
        boolean approved = e.get("approved_by") != null;
        if (rate.compareTo(limit) > 0 && !approved) {
            sal.jdbc().update("""
                    UPDATE sal.estimate SET status = 'PENDING_APPROVAL', discount_limit = ?, version = version + 1,
                           updated_at = now(), updated_by = ? WHERE id = ?""", limit, by, id);
            Map<String, Object> v = get(id);
            audit.append("SAL", AuditLog.Action.UPDATE, "estimate", id, Map.of("status", st.name()),
                    Map.of("status", "PENDING_APPROVAL", "discountRate", rate, "limit", limit));
            return v;
        }
        sal.jdbc().update("""
                UPDATE sal.estimate SET status = 'ISSUED', discount_limit = ?, issued_at = ?, version = version + 1,
                       updated_at = now(), updated_by = ? WHERE id = ?""", limit, Timestamp.from(clock.instant()), by, id);
        outbox.publish(new SalEvents.EstimateIssued(id, SalJdbc.uuid(e, "opportunity_id"), SalJdbc.str(e, "estimate_no"),
                ((Number) e.get("version_no")).intValue(), SalJdbc.dec(e, "offer_amount"), SalJdbc.dec(e, "vat"),
                SalJdbc.uuid(e, "document_id")));
        Map<String, Object> v = get(id);
        audit.append("SAL", AuditLog.Action.UPDATE, "estimate", id, Map.of("status", st.name()),
                Map.of("status", "ISSUED", "offerAmount", v.get("offerAmount")));
        return v;
    }

    /**
     * POST /estimates/{id}:approve-discount (v0.2, EXEC 전용 — 권한 sal:discount:approve, If-Match).
     * APPROVE: approved_by 기록(상태 PENDING_APPROVAL 유지 → 다음 :issue 에서 ISSUED). EXEC 한도(49%) 초과는 422.
     * REJECT: DRAFT 복귀(approved_by 없음).
     */
    @Transactional
    public Map<String, Object> approveDiscount(UUID id, long expectedVersion, String decision, String comment) {
        Map<String, Object> e = sal.lock("estimate", id);
        IfMatch.check(expectedVersion, SalJdbc.lng(e, "version"));
        EstimateStatus st = EstimateStatus.valueOf(SalJdbc.str(e, "status"));
        if (st != EstimateStatus.PENDING_APPROVAL) {
            throw new DomainException(SalCodes.ESTIMATE_STATE, "할인 승인 대기 상태가 아닙니다: " + st);
        }
        UUID by = actor.actorIdOrSystem();
        BigDecimal rate = SalJdbc.dec(e, "discount_rate");
        switch (decision) {
            case "APPROVE" -> {
                if (rate.compareTo(policy.execLimit()) > 0) {
                    throw new DomainException(SalCodes.DISCOUNT_RANGE, "EXEC 승인 한도 %s 초과: %s"
                            .formatted(policy.execLimit().toPlainString(), rate.toPlainString()));
                }
                sal.jdbc().update("""
                        UPDATE sal.estimate SET approved_by = ?, approved_at = ?, approval_comment = ?, version = version + 1,
                               updated_at = now(), updated_by = ? WHERE id = ?""",
                        by, Timestamp.from(clock.instant()), comment, by, id);
                audit.append("SAL", AuditLog.Action.APPROVE, "estimate-discount", id, Map.of("status", st.name()),
                        Map.of("decision", "APPROVE", "discountRate", rate, "approvedBy", by));
            }
            case "REJECT" -> {
                sal.jdbc().update("""
                        UPDATE sal.estimate SET status = 'DRAFT', approval_comment = ?, version = version + 1,
                               updated_at = now(), updated_by = ? WHERE id = ?""", comment, by, id);
                audit.append("SAL", AuditLog.Action.APPROVE, "estimate-discount", id, Map.of("status", st.name()),
                        Map.of("decision", "REJECT", "status", "DRAFT"));
            }
            default -> throw new DomainException(SalCodes.INVALID, "decision 은 APPROVE | REJECT: " + decision);
        }
        return get(id);
    }

    /** POST /estimates/{id}:send — ISSUED 만 발송(미발행 · 승인 대기는 SAL-422-007). 기회 PROPOSING → NEGOTIATING */
    @Transactional
    public Map<String, Object> send(UUID id, List<String> contacts) {
        Map<String, Object> e = sal.lock("estimate", id);
        EstimateStatus st = EstimateStatus.valueOf(SalJdbc.str(e, "status"));
        if (st != EstimateStatus.ISSUED) {
            throw new DomainException(SalCodes.NOT_ISSUED, "발행(ISSUED)된 견적만 발송할 수 있습니다: " + st);
        }
        UUID by = actor.actorIdOrSystem();
        sal.jdbc().update("UPDATE sal.estimate SET status = 'SENT', version = version + 1, updated_at = now(), "
                + "updated_by = ? WHERE id = ?", by, id);
        opportunities.moveIfAllowed(SalJdbc.uuid(e, "opportunity_id"), OpportunityStage.NEGOTIATING,
                "견적 발송 " + SalJdbc.str(e, "estimate_no") + " v" + e.get("version_no"));
        audit.append("SAL", AuditLog.Action.UPDATE, "estimate", id, Map.of("status", st.name()),
                Map.of("status", "SENT", "contacts", contacts == null ? List.of() : contacts));
        return get(id);
    }

    /** 고객 응답 기록: ACCEPT → ACCEPTED(기회 NEGOTIATING → WON) / REJECT → REJECTED */
    @Transactional
    public Map<String, Object> respond(UUID id, boolean accepted, String note) {
        Map<String, Object> e = sal.lock("estimate", id);
        EstimateStatus st = EstimateStatus.valueOf(SalJdbc.str(e, "status"));
        EstimateStatus to = accepted ? EstimateStatus.ACCEPTED : EstimateStatus.REJECTED;
        if (!st.canMoveTo(to)) {
            throw new DomainException(SalCodes.ESTIMATE_STATE, "견적 상태 전이 불가: %s → %s".formatted(st, to));
        }
        UUID by = actor.actorIdOrSystem();
        sal.jdbc().update("UPDATE sal.estimate SET status = ?, version = version + 1, updated_at = now(), updated_by = ? "
                + "WHERE id = ?", to.name(), by, id);
        if (accepted) {
            opportunities.moveIfAllowed(SalJdbc.uuid(e, "opportunity_id"), OpportunityStage.WON,
                    "고객 수락 " + SalJdbc.str(e, "estimate_no"));
        }
        audit.append("SAL", AuditLog.Action.UPDATE, "estimate", id, Map.of("status", st.name()),
                Map.of("status", to.name(), "note", note == null ? "" : note));
        return get(id);
    }

    public Map<String, Object> get(UUID id) {
        Map<String, Object> e = sal.get("estimate", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.get("id"));
        m.put("estimateNo", e.get("estimate_no"));
        m.put("versionNo", e.get("version_no"));
        m.put("opportunityId", e.get("opportunity_id"));
        m.put("serviceType", e.get("service_type"));
        m.put("asOfDate", SalJdbc.localDate(e.get("as_of_date")));
        m.put("feeRuleSetId", e.get("fee_rule_set_id"));
        m.put("ruleVersion", e.get("rule_version"));
        m.put("directLabor", e.get("direct_labor"));
        m.put("directExpense", e.get("direct_expense"));
        m.put("overheadRate", e.get("overhead_rate"));
        m.put("overhead", e.get("overhead"));
        m.put("techFeeRate", e.get("tech_fee_rate"));
        m.put("techFee", e.get("tech_fee"));
        m.put("subtotal", e.get("subtotal"));
        m.put("discountRate", e.get("discount_rate"));
        m.put("offerAmount", e.get("offer_amount"));
        m.put("vat", e.get("vat"));
        m.put("validUntil", SalJdbc.localDate(e.get("valid_until")));
        m.put("status", e.get("status"));
        m.put("discountLimit", e.get("discount_limit"));
        m.put("approvedBy", e.get("approved_by"));
        m.put("documentId", e.get("document_id"));
        m.put("version", e.get("version"));
        m.put("lines", sal.jdbc().queryForList("""
                SELECT seq, line_type AS "lineType", work_item AS "workItem", grade, base_man_days AS "baseManDays",
                       adj_factor AS "adjFactor", man_days AS "manDays", unit_price AS "unitPrice", quantity, amount
                  FROM sal.estimate_line WHERE estimate_id = ? ORDER BY seq""", id));
        return m;
    }

    public List<Map<String, Object>> versionsOf(UUID opportunityId) {
        return sal.jdbc().queryForList("""
                SELECT id, estimate_no AS "estimateNo", version_no AS "versionNo", status, subtotal,
                       discount_rate AS "discountRate", offer_amount AS "offerAmount", as_of_date AS "asOfDate"
                  FROM sal.estimate WHERE opportunity_id = ? AND deleted_at IS NULL ORDER BY version_no""", opportunityId);
    }

    /** 계약 → 실행예산 원천(sal.contract.signed estimateLines) */
    public List<SalEvents.EstimateLine> linesForEvent(UUID estimateId) {
        if (estimateId == null) {
            return List.of();
        }
        return sal.jdbc().query("""
                SELECT line_type, work_item, grade, man_days, unit_price, quantity, amount
                  FROM sal.estimate_line WHERE estimate_id = ? ORDER BY seq""",
                (rs, i) -> new SalEvents.EstimateLine(rs.getString("line_type"), rs.getString("work_item"),
                        rs.getString("grade"), rs.getBigDecimal("man_days"), rs.getBigDecimal("unit_price"),
                        rs.getBigDecimal("quantity"), rs.getBigDecimal("amount")), estimateId);
    }

    private static Map<String, Object> amounts(Map<String, Object> v) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("estimateNo", "versionNo", "ruleVersion", "subtotal", "discountRate", "offerAmount",
                "vat", "status")) {
            m.put(k, v.get(k));
        }
        return m;
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new DomainException(SalCodes.INVALID, "JSON 직렬화 오류");
        }
    }

    /** 오늘(KST) 기준 유효기간 경과 SENT 견적 → EXPIRED (일 배치 후보 — 스케줄러는 후속) */
    @Transactional
    public int expire(LocalDate today) {
        return sal.jdbc().update("""
                UPDATE sal.estimate SET status = 'EXPIRED', version = version + 1, updated_at = now(),
                       updated_by = ? WHERE status = 'SENT' AND valid_until < ?""", CurrentActor.SYSTEM, SalJdbc.d(today));
    }
}
