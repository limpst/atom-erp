package com.atomeng.erp.fcd.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fcd.api.FcdEvents;
import com.atomeng.erp.fcd.application.port.FraudServicePort;
import com.atomeng.erp.fcd.application.port.FraudServicePort.L3Result;
import com.atomeng.erp.fcd.application.port.FraudServicePort.TxnRef;
import com.atomeng.erp.fcd.domain.features.CategoryCatalog;
import com.atomeng.erp.fcd.domain.features.HolidayCalendar;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.CardInfo;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.MerchantInfo;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.PortInput;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.TxnInput;
import com.atomeng.erp.fcd.domain.rules.PyOps;
import com.atomeng.erp.fcd.domain.rules.RuleSet.RuleHit;
import com.atomeng.erp.fcd.domain.rules.RuleSetRegistry;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner.ScoreResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 채점 (12-IMPL Step 5–7): L1 특징(Java, 프로토타입과 패리티) + L2 맥락 · L3 이상점수(Python 서비스 포트) →
 * 같은 룰셋 YAML 평가 → L4 결합 · 등급 → risk_score append(특징 스냅샷 포함) · txn 최신 점수 갱신 →
 * 등급이 바뀌면 {@code fcd.risk.scored}. 열린 케이스 점수가 내려가도 자동 종결하지 않고 score_dropped 표시만.
 * <p>L1 특징은 카드 묶음 · 월 누계 · 쪼개기 · 정기결제 판정을 위해 대상 월 + 직전 5개월 전체 원장을 함께 계산한다.
 */
@Service
public class ScoringService {

    private final JdbcTemplate jdbc;
    private final RuleSetService rulesets;
    private final FraudServicePort fraud;
    private final OutboxPublisher outbox;
    private final CategoryCatalog categories;
    private final HolidayCalendar holidays;
    private final ObjectMapper om;

    public ScoringService(JdbcTemplate jdbc, RuleSetService rulesets, FraudServicePort fraud, OutboxPublisher outbox,
                          CategoryCatalog categories, HolidayCalendar holidays, ObjectMapper om) {
        this.jdbc = jdbc;
        this.rulesets = rulesets;
        this.fraud = fraud;
        this.outbox = outbox;
        this.categories = categories;
        this.holidays = holidays;
        this.om = om;
    }

    public record Scored(UUID txnId, int score, String grade, String gradeCode, List<String> hits) {}

    /** 기간(승인일 [from, to)) 전체 재채점 */
    @Transactional
    public List<Scored> scorePeriod(LocalDate from, LocalDate to, String trigger) {
        List<UUID> ids = jdbc.query("SELECT id FROM fcd.txn WHERE removed_at IS NULL AND approved_at >= ? AND approved_at < ?",
                (rs, i) -> rs.getObject(1, UUID.class), Timestamp.valueOf(from.atStartOfDay()),
                Timestamp.valueOf(to.atStartOfDay()));
        return score(ids, trigger);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public List<Scored> score(Collection<UUID> targetIds, String trigger) {
        if (targetIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> targets = Set.copyOf(targetIds);
        Map<UUID, LocalDateTime> targetAt = new HashMap<>();
        for (UUID id : targets) {
            jdbc.query("SELECT approved_at FROM fcd.txn WHERE id = ? AND removed_at IS NULL",
                    rs -> {
                        targetAt.put(id, rs.getTimestamp(1).toLocalDateTime());
                    }, id);
        }
        if (targetAt.isEmpty()) {
            return List.of();
        }
        TreeSet<YearMonth> months = new TreeSet<>();
        targetAt.values().forEach(t -> months.add(YearMonth.from(t)));
        LocalDate from = months.first().minusMonths(5).atDay(1);
        LocalDate to = months.last().plusMonths(1).atDay(1);

        Map<String, CardInfo> cards = new HashMap<>();
        Map<String, MerchantInfo> merchants = new HashMap<>();
        Map<String, LocalDate> resigned = new HashMap<>();
        List<TxnInput> window = new ArrayList<>();
        Map<UUID, TxnRef> refs = new LinkedHashMap<>();
        jdbc.query("""
                SELECT t.id, t.seq, t.card_id, t.employee_id, t.approved_at, t.approved_at_precision, t.amount_krw,
                       t.cancelled_amount_krw, t.status, t.currency, t.country, t.is_online, t.merchant_id, t.merchant_name,
                       t.category_hint, t.pg_unresolved
                  FROM fcd.txn t WHERE t.removed_at IS NULL AND t.approved_at >= ? AND t.approved_at < ?
                 ORDER BY t.approved_at, t.seq""", rs -> {
            UUID id = rs.getObject("id", UUID.class);
            UUID emp = rs.getObject("employee_id", UUID.class);
            UUID mid = rs.getObject("merchant_id", UUID.class);
            TxnInput in = new TxnInput(id.toString(), rs.getLong("seq"), rs.getObject("card_id", UUID.class).toString(),
                    emp == null ? null : emp.toString(), rs.getTimestamp("approved_at").toLocalDateTime(),
                    rs.getString("approved_at_precision"), rs.getBigDecimal("amount_krw").longValueExact(),
                    rs.getBigDecimal("cancelled_amount_krw").longValueExact(), rs.getString("status"),
                    rs.getString("currency"), rs.getString("country"), (Boolean) rs.getObject("is_online"),
                    mid == null ? null : mid.toString(), rs.getString("merchant_name"), rs.getString("category_hint"),
                    rs.getBoolean("pg_unresolved"));
            window.add(in);
            if (targets.contains(id)) {
                refs.put(id, new TxnRef(id, emp, rs.getObject("card_id", UUID.class), in.approvedAt(), in.merchantName(),
                        mid, Math.max(0, in.amountKrw() - in.cancelledAmountKrw())));
            }
        }, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.atStartOfDay()));
        jdbc.query("""
                SELECT DISTINCT c.id, c.card_type, c.status, c.closed_on, c.limit_per_txn, c.limit_daily, c.limit_monthly
                  FROM fcd.card c JOIN fcd.txn t ON t.card_id = c.id
                 WHERE t.removed_at IS NULL AND t.approved_at >= ? AND t.approved_at < ?""", rs -> {
            java.sql.Date closed = rs.getDate("closed_on");
            cards.put(rs.getObject("id", UUID.class).toString(), new CardInfo(rs.getString("card_type"),
                    rs.getString("status"), closed == null ? null : closed.toLocalDate(),
                    rs.getBigDecimal("limit_per_txn").longValueExact(), rs.getBigDecimal("limit_daily").longValueExact(),
                    rs.getBigDecimal("limit_monthly").longValueExact()));
        }, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.atStartOfDay()));
        jdbc.query("""
                SELECT DISTINCT m.id, m.name_raw, m.internal_category, m.is_online, m.country, m.blacklisted, m.related_party
                  FROM fcd.merchant m JOIN fcd.txn t ON t.merchant_id = m.id
                 WHERE t.removed_at IS NULL AND t.approved_at >= ? AND t.approved_at < ?""", rs -> {
            merchants.put(rs.getObject("id", UUID.class).toString(), new MerchantInfo(rs.getString("name_raw"),
                    rs.getString("internal_category"), rs.getBoolean("is_online"), rs.getString("country"),
                    rs.getBoolean("blacklisted"), rs.getBoolean("related_party")));
        }, Timestamp.valueOf(from.atStartOfDay()), Timestamp.valueOf(to.atStartOfDay()));
        jdbc.query("SELECT employee_id, resigned_on FROM fcd.employee_status WHERE resigned_on IS NOT NULL", rs -> {
            resigned.put(rs.getObject(1, UUID.class).toString(), rs.getDate(2).toLocalDate());
        });

        List<TxnRef> refList = new ArrayList<>(refs.values());
        Map<UUID, Map<String, Object>> ctxPort = fraud.context(refList);
        Map<String, PortInput> port = new HashMap<>();
        ctxPort.forEach((id, c) -> port.put(id.toString(), new PortInput(PyOps.truthy(c.get("preapproved")),
                PyOps.truthy(c.get("preapproved_cat")), PyOps.truthy(c.get("trip_overseas")), c.get("giftcard_recipients"))));
        RuleSetRegistry registry = rulesets.registry();
        Map<String, Object> params = registry.resolve(months.last().atEndOfMonth()).params();
        List<L1FeatureBuilder.Row> rows = L1FeatureBuilder.build(window, cards, merchants, resigned, port, params,
                categories, holidays);
        Map<UUID, L1FeatureBuilder.Row> byId = new HashMap<>();
        Map<UUID, Map<String, Object>> txFeatures = new HashMap<>();
        for (L1FeatureBuilder.Row r : rows) {
            UUID id = UUID.fromString(r.txn().txnId());
            if (refs.containsKey(id)) {
                byId.put(id, r);
                txFeatures.put(id, r.tx());
            }
        }
        Map<UUID, L3Result> l3 = fraud.anomaly(refList, txFeatures);
        List<Scored> out = new ArrayList<>();
        for (UUID id : refs.keySet()) {
            L1FeatureBuilder.Row r = byId.get(id);
            Map<String, Object> ctx = new HashMap<>();
            Map<String, Object> pc = ctxPort.get(id);
            if (pc != null) {
                pc.forEach((k, v) -> ctx.put(k, PyOps.normalize(v)));
            }
            ctx.putAll(r.ctx());
            L3Result l3r = l3.getOrDefault(id, L3Result.DISABLED);
            @SuppressWarnings("unchecked")
            Map<String, Object> l3map = (Map<String, Object>) PyOps.normalize(l3r.l3());
            ScoreResult s = RiskCombiner.score(registry, r.date(), r.tx(), ctx, l3map, RiskCombiner.ALL_LAYERS, null);
            persist(id, s, trigger, r.tx(), ctx, l3map, l3r);
            out.add(new Scored(id, s.score(), s.grade(), RiskCombiner.gradeCode(s.score()),
                    s.hits().stream().map(RuleHit::ruleId).toList()));
        }
        return out;
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<Map<String, Object>> hitsJson(List<RuleHit> hits) {
        return hits.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", h.ruleId());
            m.put("layer", h.layer());
            m.put("kind", h.kind());
            m.put("s", h.strength());
            m.put("w", h.weight());
            m.put("floor", h.floorScore());
            m.put("severity", h.severity());
            m.put("message", h.message());
            m.put("legal_basis", h.legalBasis());
            m.put("evidence", h.evidence());
            return m;
        }).toList();
    }

    private void persist(UUID txnId, ScoreResult s, String trigger, Map<String, Object> tx, Map<String, Object> ctx,
                         Map<String, Object> l3, L3Result l3r) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("tx", tx);
        features.put("ctx", ctx);
        features.put("l3", l3);
        String l3Status = l3r.status().equals("OK") && !l3.isEmpty() ? "OK" : l3r.status();
        jdbc.update("""
                INSERT INTO fcd.risk_score(id, txn_id, trigger, rule_set_version, model_version, score, grade, r, r_mitigated,
                                           floor_score, hits, mitigations, flags, contributions, reasons, features, l3_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?)""",
                UuidV7.next(), txnId, trigger, s.rulesetVersion(), l3r.modelVersion(), s.score(),
                RiskCombiner.gradeCode(s.score()), s.r(), s.rMitigated(), s.floor(), json(hitsJson(s.hits())),
                json(hitsJson(s.mitigations())), json(hitsJson(s.flags())), json(s.contributions()), json(s.reasons()),
                json(features), l3Status);
        String prev = jdbc.queryForObject("SELECT risk_grade FROM fcd.txn WHERE id = ?", String.class, txnId);
        String grade = RiskCombiner.gradeCode(s.score());
        jdbc.update("UPDATE fcd.txn SET risk_score = ?, risk_grade = ?, updated_at = now() WHERE id = ?", s.score(), grade,
                txnId);
        if (!grade.equals(prev)) {
            outbox.publish(new FcdEvents.RiskScored(txnId, s.score(), grade, prev,
                    s.reasons().stream().limit(3).toList(), s.rulesetVersion()));
        }
        // 재채점으로 점수가 내려가도 열린 케이스는 자동 종결하지 않는다 — 표시만
        jdbc.update("""
                UPDATE fcd.fcd_case c SET score_dropped = true
                  FROM fcd.case_txn ct WHERE ct.case_id = c.id AND ct.txn_id = ? AND c.score > ?
                   AND c.status NOT IN ('RESOLVED_LEGIT','RESOLVED_MISUSE','RECOVERY','CLOSED')""", txnId, s.score());
    }
}
