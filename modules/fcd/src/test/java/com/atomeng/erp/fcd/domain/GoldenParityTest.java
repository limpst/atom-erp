package com.atomeng.erp.fcd.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.fcd.domain.features.CategoryCatalog;
import com.atomeng.erp.fcd.domain.features.HolidayCalendar;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.CardInfo;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.MerchantInfo;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.PortInput;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.TxnInput;
import com.atomeng.erp.fcd.domain.rules.PyOps;
import com.atomeng.erp.fcd.domain.rules.RuleSet;
import com.atomeng.erp.fcd.domain.rules.RuleSet.RuleHit;
import com.atomeng.erp.fcd.domain.rules.RuleSetRegistry;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner.ScoreResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 골든 패리티 — Python 프로토타입(atom-fcd)을 읽기 전용으로 실행해 내보낸 기대값(tools/fcd_parity_export.py)과
 * Java 이식의 결과가 <b>완전히 같은지</b> 확인한다. 데이터는 전부 합성.
 * (A) 같은 env(tx·ctx·l3) → 같은 룰셋 선택 · 적중(강도·메시지·근거 포함) · L4 점수
 * (B) 원장 거래(카드 20장 · 4개월) → Java L1 특징 = Python 특징, L1 적중 · 점수 동일
 */
class GoldenParityTest {

    static RuleSetRegistry registry;
    static Map<String, Object> envs;
    static Map<String, Object> l1;

    @BeforeAll
    static void load() {
        registry = Fixtures.registry();
        envs = Fixtures.json("/fcd/parity/fcd-parity-envs.json");
        l1 = Fixtures.json("/fcd/parity/fcd-parity-l1.json");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    @Test
    @DisplayName("패리티 0: 룰셋 YAML 은 프로토타입 원본과 바이트 동일(sha256, 줄끝 LF) · 8개 파일 로드 · 초안 비활성")
    void rulesetsIdentical() {
        Map<String, Object> sha = map(map(envs.get("meta")).get("ruleset_sha256"));
        Map<String, RuleSet> loaded = Fixtures.rulesets();
        assertThat(new TreeMap<>(sha).keySet()).isEqualTo(loaded.keySet());
        loaded.forEach((f, rs) -> assertThat(rs.sha256()).as(f).isEqualTo(sha.get(f)));
        assertThat(registry.resolve(LocalDate.of(2026, 7, 1)).version()).isEqualTo("2026.5.0");
        assertThat(registry.resolve(LocalDate.of(2025, 11, 1)).version()).isEqualTo("2025.2.0");
        assertThat(registry.resolve(LocalDate.of(2026, 2, 1)).version()).isEqualTo("2026.1.0");
        assertThat(registry.all().stream().filter(r -> r.version().contains("-cal.")).allMatch(r -> r.status().equals("DRAFT")))
                .isTrue();
    }

    static void assertHits(String tid, List<RuleHit> actual, List<Object> expected) {
        assertThat(actual.stream().map(RuleHit::ruleId).toList()).as(tid + " rule ids")
                .isEqualTo(expected.stream().map(e -> map(e).get("rule_id")).toList());
        for (int i = 0; i < actual.size(); i++) {
            RuleHit h = actual.get(i);
            Map<String, Object> e = map(expected.get(i));
            String at = tid + "/" + h.ruleId();
            assertThat(h.layer()).as(at).isEqualTo(e.get("layer"));
            assertThat(h.kind()).as(at).isEqualTo(e.get("kind"));
            assertThat(h.strength()).as(at + " strength").isEqualTo(PyOps.toFloat(e.get("strength")));
            assertThat(h.weight()).as(at + " weight").isEqualTo(PyOps.toFloat(e.get("weight")));
            assertThat(h.floorScore() == null ? null : (long) h.floorScore()).as(at).isEqualTo(e.get("floor"));
            assertThat(h.severity()).as(at).isEqualTo(e.get("severity"));
            assertThat(h.message()).as(at + " message").isEqualTo(e.get("message"));
            assertThat(h.reviewRequired()).as(at).isEqualTo(e.get("review_required"));
            assertThat(PyOps.pyEq(PyOps.normalize(h.evidence()), e.get("evidence"))).as(at + " evidence " + h.evidence()
                    + " vs " + e.get("evidence")).isTrue();
        }
    }

    static void assertScore(String tid, ScoreResult s, Map<String, Object> e) {
        assertThat(s.score()).as(tid + " score").isEqualTo(((Long) e.get("score")).intValue());
        assertThat(s.grade()).as(tid).isEqualTo(e.get("grade"));
        assertThat(s.r()).as(tid + " R").isEqualTo(PyOps.toFloat(e.get("R")));
        assertThat(s.rMitigated()).as(tid + " Rm").isEqualTo(PyOps.toFloat(e.get("Rm")));
        assertThat((long) s.floor()).as(tid).isEqualTo(e.get("floor"));
        assertThat(s.rulesetVersion()).as(tid).isEqualTo(e.get("ruleset"));
        assertThat(s.hits().stream().map(RuleHit::ruleId).toList()).as(tid).isEqualTo(e.get("hits"));
        assertThat(s.mitigations().stream().map(RuleHit::ruleId).toList()).as(tid).isEqualTo(e.get("mitigations"));
        assertThat(s.flags().stream().map(RuleHit::ruleId).toList()).as(tid).isEqualTo(e.get("flags"));
        assertThat(s.reasons()).as(tid + " reasons").isEqualTo(e.get("reasons"));
        List<Object> ec = list(e.get("contributions"));
        assertThat(s.contributions()).hasSameSizeAs(ec);
        for (int i = 0; i < ec.size(); i++) {
            assertThat(s.contributions().get(i).ruleId()).as(tid).isEqualTo(map(ec.get(i)).get("rule_id"));
            assertThat(s.contributions().get(i).c()).as(tid).isEqualTo(PyOps.toFloat(map(ec.get(i)).get("c")));
        }
        assertThat(s.hard()).as(tid).isEqualTo(e.get("hard"));
        assertThat(s.reviewRequired()).as(tid).isEqualTo(e.get("review_required"));
        assertThat(s.unverifiedUsed()).as(tid).isEqualTo(e.get("unverified_used"));
    }

    @Test
    @DisplayName("패리티 A: Python env 843건 → 룰셋 선택 · L1/L2/L3 적중(강도·메시지·근거) · L4 점수·등급·사유 완전 일치")
    void evaluatorAndCombinerParity() {
        List<Object> rows = list(envs.get("rows"));
        assertThat(rows).hasSizeGreaterThan(500);
        Set<String> hitRules = new java.util.TreeSet<>();
        for (Object o : rows) {
            Map<String, Object> r = map(o);
            String tid = (String) r.get("txn_id");
            LocalDate d = LocalDate.parse((String) r.get("date"));
            RuleSet rs = registry.resolve(d);
            assertThat(rs.version()).as(tid).isEqualTo(r.get("ruleset"));
            Map<String, Object> env = new HashMap<>();
            env.put("tx", map(r.get("tx")));
            env.put("ctx", map(r.get("ctx")));
            env.put("l3", map(r.get("l3")));
            env.put("params", rs.params());
            List<RuleHit> raw = rs.evaluate(env, d, Set.of("L1", "L2", "L3"), null);
            assertHits(tid, raw, list(r.get("raw_hits")));
            raw.forEach(h -> hitRules.add(h.ruleId()));
            ScoreResult s = RiskCombiner.score(registry, d, map(r.get("tx")), map(r.get("ctx")), map(r.get("l3")),
                    RiskCombiner.ALL_LAYERS, null);
            assertScore(tid, s, map(r.get("score")));
        }
        assertThat(hitRules).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    @DisplayName("패리티 B: 원장 거래 → Java L1 특징(tx.* 48종) = Python, L1 적중 · L1 점수 완전 일치")
    void l1FeaturesAndHitsParity() {
        Map<String, CardInfo> cards = new HashMap<>();
        map(l1.get("cards")).forEach((id, o) -> {
            Map<String, Object> c = map(o);
            cards.put(id, new CardInfo((String) c.get("card_type"), (String) c.get("status"),
                    c.get("closed_on") == null ? null : LocalDate.parse((String) c.get("closed_on")),
                    (Long) c.get("limit_per_txn"), (Long) c.get("limit_daily"), (Long) c.get("limit_monthly")));
        });
        Map<String, MerchantInfo> merchants = new HashMap<>();
        map(l1.get("merchants")).forEach((id, o) -> {
            Map<String, Object> m = map(o);
            merchants.put(id, new MerchantInfo((String) m.get("name"), (String) m.get("category"),
                    (Boolean) m.get("is_online"), (String) m.get("country"), (Boolean) m.get("blacklisted"),
                    (Boolean) m.get("related_party")));
        });
        Map<String, LocalDate> resigned = new HashMap<>();
        map(l1.get("employees")).forEach((id, o) -> {
            Object r = map(o).get("resigned_on");
            if (r != null) {
                resigned.put(id, LocalDate.parse((String) r));
            }
        });
        List<TxnInput> txns = new ArrayList<>();
        Map<String, PortInput> port = new HashMap<>();
        Map<String, Map<String, Object>> expected = new HashMap<>();
        for (Object o : list(l1.get("rows"))) {
            Map<String, Object> r = map(o);
            String tid = (String) r.get("txn_id");
            txns.add(new TxnInput(tid, (Long) r.get("seq"), (String) r.get("card_id"), (String) r.get("emp_id"),
                    LocalDateTime.parse((String) r.get("approved_at")), (String) r.get("precision"),
                    (Long) r.get("amount_krw"), (Long) r.get("cancelled_amount_krw"), (String) r.get("status"),
                    (String) r.get("currency"), (String) r.get("country"), (Boolean) r.get("is_online"),
                    (String) r.get("merchant_id"), (String) r.get("merchant_name"), (String) r.get("category_hint"),
                    (Boolean) r.get("pg_unresolved")));
            Map<String, Object> p = map(r.get("port"));
            port.put(tid, new PortInput((Boolean) p.get("preapproved"), (Boolean) p.get("preapproved_cat"),
                    (Boolean) p.get("trip_overseas"), p.get("giftcard_recipients")));
            expected.put(tid, r);
        }
        RuleSet paramsRs = registry.get((String) l1.get("params_ruleset")).orElseThrow();
        var rows = L1FeatureBuilder.build(txns, cards, merchants, resigned, port, paramsRs.params(),
                CategoryCatalog.fromClasspath(), HolidayCalendar.fromClasspath());
        assertThat(rows).hasSize(txns.size());
        Set<String> hitRules = new java.util.TreeSet<>();
        for (var row : rows) {
            String tid = row.txn().txnId();
            Map<String, Object> e = expected.get(tid);
            Map<String, Object> etx = map(e.get("expected_tx"));
            for (var k : etx.entrySet()) {
                Object actual = row.tx().get(k.getKey());
                assertThat(PyOps.typeName(actual) + ":" + PyOps.pyEq(actual, k.getValue()))
                        .as(tid + " tx." + k.getKey() + " java=" + actual + " python=" + k.getValue())
                        .isEqualTo(PyOps.typeName(k.getValue()) + ":true");
            }
            assertThat(row.ctx().get("shared_unassigned")).as(tid).isEqualTo(e.get("expected_shared_unassigned"));
            RuleSet rs = registry.resolve(row.date());
            Map<String, Object> env = new HashMap<>();
            env.put("tx", row.tx());
            env.put("ctx", row.ctx());
            env.put("l3", Map.of());
            env.put("params", rs.params());
            List<RuleHit> raw = rs.evaluate(env, row.date(), Set.of("L1"), null);
            assertHits(tid, raw, list(e.get("raw_hits")));
            raw.forEach(h -> hitRules.add(h.ruleId()));
            assertScore(tid, RiskCombiner.score(registry, row.date(), row.tx(), row.ctx(), Map.of(), Set.of("L1"), null),
                    map(e.get("score")));
        }
        assertThat(hitRules).contains("R-SPLIT", "R-SPLIT-MULTI", "R-LIMIT", "R-NIGHT", "R-HOLIDAY", "R-CAT-BLOCK",
                "R-CAT-RESTRICT", "R-CARD-INACTIVE", "R-SHARED-UNASSIGNED", "R-OVERSEAS", "R-137", "R-310");
    }
}
