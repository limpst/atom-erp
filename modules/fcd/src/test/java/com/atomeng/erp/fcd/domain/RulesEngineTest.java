package com.atomeng.erp.fcd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.fcd.domain.casework.CaseMachine;
import com.atomeng.erp.fcd.domain.features.CategoryCatalog;
import com.atomeng.erp.fcd.domain.features.HolidayCalendar;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.CardInfo;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.MerchantInfo;
import com.atomeng.erp.fcd.domain.features.L1FeatureBuilder.TxnInput;
import com.atomeng.erp.fcd.domain.review.ReviewBudgetPolicy;
import com.atomeng.erp.fcd.domain.review.ReviewBudgetPolicy.Candidate;
import com.atomeng.erp.fcd.domain.rules.PyExpr;
import com.atomeng.erp.fcd.domain.rules.PyFormat;
import com.atomeng.erp.fcd.domain.rules.PyOps;
import com.atomeng.erp.fcd.domain.rules.RuleSet;
import com.atomeng.erp.fcd.domain.rules.RuleSetRegistry;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner.ScoreResult;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 규칙 엔진 · 안전 평가기 · L4 결합 · 정책 (12-IMPL Step 5·7) — TC-FCD-016 · 017 · 018 · 024 + 평가기 안전성.
 * 모든 값은 합성.
 */
class RulesEngineTest {

    static Object ev(String src, Map<String, Object> env) {
        return PyExpr.evaluate(src, env);
    }

    @Test
    @DisplayName("안전 평가기: eval·임의 호출·람다·컴프리헨션·딕셔너리·슬라이스·비트연산·밑줄 이름/속성/키·대입 전부 거부")
    void forbiddenSyntax() {
        for (String bad : List.of("__import__('os')", "open('x')", "(lambda: 1)()", "[x for x in y]", "{'a': 1}", "x[1:2]",
                "a | b", "a & b", "~a", "tx._secret", "_x", "x['_k']", "a = 1", "getattr(tx, 'a')", "tx.amount.__class__",
                "f(x=1)", "max(*xs)", "a @ b", "{x for x in y}")) {
            assertThatThrownBy(() -> PyExpr.evaluate(bad, Map.of("x", Map.of("_k", 1L), "tx", Map.of())))
                    .as(bad).isInstanceOf(PyExpr.ExprError.class);
        }
    }

    @Test
    @DisplayName("Python 의미론: None 대소비교=거짓 · and/or 피연산자 반환 · 체이닝 · in · 삼항 · int/int=float · float % · round 짝수 · 결측=None")
    void pythonSemantics() {
        Map<String, Object> env = new HashMap<>();
        env.put("tx", new HashMap<>(Map.of("amount", 10_000.0, "n", 3L, "cat", "BAR_PUB", "flag", true)));
        env.put("params", Map.of("cats", List.of("BAR_PUB", "GOLF")));
        assertThat(ev("tx.missing > 1", env)).isEqualTo(false);
        assertThat(ev("tx.missing is None", env)).isEqualTo(true);
        assertThat(ev("tx.missing == None", env)).isEqualTo(true);
        assertThat(ev("tx.missing + 1", env)).isNull();
        assertThat(ev("tx.n and tx.cat", env)).isEqualTo("BAR_PUB");
        assertThat(ev("0 or tx.missing or 'x'", env)).isEqualTo("x");
        assertThat(ev("1 < tx.n <= 3", env)).isEqualTo(true);
        assertThat(ev("tx.cat in params.cats and 'GOLF' in params.cats", env)).isEqualTo(true);
        assertThat(ev("'a' if tx.flag else 'b'", env)).isEqualTo("a");
        assertThat(ev("7 / 2", env)).isEqualTo(3.5);
        assertThat(ev("7 // 2", env)).isEqualTo(3L);
        assertThat(ev("-7 // 2", env)).isEqualTo(-4L);
        assertThat(ev("-7 % 3", env)).isEqualTo(2L);
        assertThat(ev("tx.amount % 10000 == 0", env)).isEqualTo(true);
        assertThat(ev("-5.5 % 2", env)).isEqualTo(0.5);
        assertThat(ev("round(2.5)", env)).isEqualTo(2L);
        assertThat(ev("round(3.5)", env)).isEqualTo(4L);
        assertThat(ev("round(2.675, 2)", env)).isEqualTo(2.67);   // 이진값 그대로 반올림(Python 과 같음)
        assertThat(ev("clip(1.7, 0, 1)", env)).isEqualTo(1L);
        assertThat(ev("clip(0.3, 0, 1)", env)).isEqualTo(0.3);
        assertThat(ev("max(0.8, min(1.0, 2.0 - 0.5))", env)).isEqualTo(1.0);
        assertThat(ev("max(tx.missing, 1)", env)).isNull();          // TypeError → None
        assertThat(ev("coalesce(tx.missing, 5)", env)).isEqualTo(5L);
        assertThat(ev("2 ** 10", env)).isEqualTo(1024L);
        assertThat(ev("-2 ** 2", env)).isEqualTo(-4L);
        assertThat(ev("not tx.missing", env)).isEqualTo(true);
        assertThat(ev("'3' < 4", env)).isEqualTo(false);              // 비교 TypeError → 거짓
        assertThatThrownBy(() -> ev("1 / 0", env)).isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> ev("'a' + 1", env)).isInstanceOf(PyOps.PyTypeError.class);
    }

    @Test
    @DisplayName("메시지 포맷: {x:,.0f} · {x:.1f} · {x:.0%} · {x:.2g} · 결측 '-' · bool 'True' — Python format 과 같은 글자")
    void messageFormat() {
        Map<String, Object> env = Map.of("tx", Map.of("amount", 1234567.5, "r", 0.456, "p", 0.000012345, "b", true,
                "big", 1.5e16, "i", 15L));
        assertThat(RuleSet.formatMessage("{tx.amount:,.0f}원 {tx.r:.1f} {tx.r:.0%} {tx.p:.2g} {tx.none} {tx.b} {tx.i}", env))
                .isEqualTo("1,234,568원 0.5 46% 1.2e-05 - True 15");
        assertThat(PyFormat.repr(1.5e16)).isEqualTo("1.5e+16");
        assertThat(PyFormat.repr(0.1 + 0.2)).isEqualTo("0.30000000000000004");
        assertThat(PyFormat.repr(100000.0)).isEqualTo("100000.0");
        assertThat(PyFormat.format(0.5, ".0f")).isEqualTo("0");     // 짝수 반올림
        assertThat(PyFormat.format(1.5, ".0f")).isEqualTo("2");
    }

    @Test
    @DisplayName("TC-FCD-024 · BR-FCD-009: 4-eyes — 작성자 = 승인자 YAML 거부, 승인 없는 ACTIVE 거부, 작성자 본인 approve 거부 / legal_basis 빈 규칙 거부")
    void fourEyesAndLegalBasis() {
        String base = Fixtures.text("/fcd/rulesets/fcd-2026.5.0.yaml");
        assertThatThrownBy(() -> RuleSet.parse(base.replace("approved_by: AUD-005", "approved_by: FIN-001"), "fcd"))
                .hasMessageContaining("4-eyes");
        assertThatThrownBy(() -> RuleSet.parse(base.replace("approved_by: AUD-005", "approved_by: null"), "fcd"))
                .hasMessageContaining("ACTIVE ruleset requires approved_by");
        RuleSet draft = RuleSet.parse(base.replace("status: ACTIVE", "status: DRAFT")
                .replace("approved_by: AUD-005", "approved_by: null").replace("version: 2026.5.0", "version: 2026.9.0"), "fcd");
        assertThatThrownBy(() -> draft.approve("FIN-001")).isInstanceOf(SecurityException.class);
        draft.approve("AUD-777");
        assertThat(draft.status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> RuleSet.parse("""
                ruleset: fcd
                version: 1.0.0
                effective_from: '2026-01-01'
                status: DRAFT
                rules:
                - rule_id: X
                  condition_expr: 'True'
                  legal_basis: []
                """, "fcd")).hasMessageContaining("legal_basis");
        assertThatThrownBy(() -> RuleSet.parse("""
                ruleset: fcd
                version: 1.0.1
                effective_from: '2026-01-01'
                status: DRAFT
                rules:
                - rule_id: X
                  condition_expr: "__import__('os').system('x')"
                  legal_basis: [테스트]
                """, "fcd")).isInstanceOf(PyExpr.ExprError.class);
    }

    /** B7.6 계산 예 환경: 토요일 01:40 · 일반주점(RESTRICT 1.4배) · 현장 일정 없음 · 본사 62km · L3 백분위 0.97 */
    static Map<String, Object> exampleTx() {
        Map<String, Object> tx = new HashMap<>();
        tx.put("cancelled", false);
        tx.put("precision", "MINUTE");
        tx.put("is_night", true);
        tx.put("is_deep_night", true);
        tx.put("recurring", false);
        tx.put("is_holiday", true);
        tx.put("local_time", "01:40");
        tx.put("date", "2026-09-05");
        tx.put("policy", "RESTRICT");
        tx.put("preapproved_cat", false);
        tx.put("preapproved", false);
        tx.put("amount", 420_000.0);
        tx.put("cat_max", 300_000.0);
        tx.put("category", "BAR_PUB");
        tx.put("category_name", "일반주점");
        tx.put("limit_txn", 1_000_000.0);
        tx.put("limit_day", 1_500_000.0);
        tx.put("limit_month", 6_000_000.0);
        tx.put("day_total", 420_000.0);
        tx.put("month_cum", 420_000.0);
        return tx;
    }

    @Test
    @DisplayName("TC-FCD-018: B7.6 계산 예 — 90점 고위험 / 같은 거래가 야간 현장 일정 3km 이내면 X-SITE-MATCH 완화로 34점 주의")
    void b76Example() {
        RuleSetRegistry reg = Fixtures.registry();
        LocalDate sat = LocalDate.of(2026, 9, 5);
        Map<String, Object> l3 = Map.of("s3", 0.97, "top_text", "합성");
        Map<String, Object> ctx = new HashMap<>(Map.of("d_min_km", 62.0, "trip_domestic", false, "on_schedule", false));
        ScoreResult hi = RiskCombiner.score(reg, sat, exampleTx(), ctx, l3, RiskCombiner.ALL_LAYERS, null);
        assertThat(hi.hits()).extracting(RuleSet.RuleHit::ruleId)
                .containsExactlyInAnyOrder("R-CAT-RESTRICT", "R-NIGHT", "R-HOLIDAY", "X-SITE-DIST", "L3-ANOMALY");
        assertThat(hi.score()).isEqualTo(90);
        assertThat(hi.grade()).isEqualTo("고위험");
        Map<String, Object> site = new HashMap<>(Map.of("d_min_km", 3.0, "site_km", 2.9, "site_match", true,
                "trip_domestic", false, "on_schedule", true));
        ScoreResult mit = RiskCombiner.score(reg, sat, exampleTx(), site, l3, RiskCombiner.ALL_LAYERS, null);
        assertThat(mit.mitigations()).extracting(RuleSet.RuleHit::ruleId).containsExactly("X-SITE-MATCH");
        assertThat(mit.score()).isEqualTo(34);
        assertThat(mit.grade()).isEqualTo("주의");
    }

    static TxnInput txn(String id, long seq, LocalDateTime at, long amount, String merchant) {
        return new TxnInput(id, seq, "CARD-1", "EMP-1", at, "MINUTE", amount, 0, "APPROVED", "KRW", null, null, merchant,
                "(예시)가맹점", null, false);
    }

    @Test
    @DisplayName("TC-FCD-016 · 017: 쪼개기(같은 가맹점 40분 내 280,000+270,000, 1회 한도 300,000) → R-SPLIT · 점수 ≥ 70 · 같은 묶음 / "
            + "금지 업종(BLOCK) → 점수 ≥ 85 고위험")
    void splitAndBlock() {
        RuleSetRegistry reg = Fixtures.registry();
        Map<String, CardInfo> cards = Map.of("CARD-1", new CardInfo("PERSONAL_ASSIGNED", "ACTIVE", null, 300_000, 450_000,
                3_000_000));
        Map<String, MerchantInfo> merchants = Map.of(
                "M-HW", new MerchantInfo("(예시)대성공구상사", "HARDWARE", false, "KR", false, false),
                "M-CLUB", new MerchantInfo("(예시)룸라운지", "ENT_CLUB", false, "KR", false, false));
        List<TxnInput> txns = new ArrayList<>(List.of(
                txn("T1", 1, LocalDateTime.of(2026, 9, 8, 14, 0), 280_000, "M-HW"),
                txn("T2", 2, LocalDateTime.of(2026, 9, 8, 14, 40), 270_000, "M-HW"),
                txn("T3", 3, LocalDateTime.of(2026, 9, 9, 20, 0), 150_000, "M-CLUB")));
        var rows = L1FeatureBuilder.build(txns, cards, merchants, Map.of(), Map.of(), reg.resolve(LocalDate.of(2026, 9, 8))
                .params(), CategoryCatalog.fromClasspath(), HolidayCalendar.fromClasspath());
        Map<String, ScoreResult> res = new HashMap<>();
        for (var r : rows) {
            res.put(r.txn().txnId(), RiskCombiner.score(reg, r.date(), r.tx(), r.ctx(), Map.of(), RiskCombiner.ALL_LAYERS, null));
        }
        for (String id : List.of("T1", "T2")) {
            assertThat(res.get(id).hits()).extracting(RuleSet.RuleHit::ruleId).contains("R-SPLIT");
            assertThat(res.get(id).score()).isGreaterThanOrEqualTo(70);
        }
        assertThat(rows.get(0).tx().get("split_group")).isEqualTo("T1").isEqualTo(rows.get(1).tx().get("split_group"));
        assertThat(res.get("T3").hits()).extracting(RuleSet.RuleHit::ruleId).contains("R-CAT-BLOCK");
        assertThat(res.get("T3").score()).isGreaterThanOrEqualTo(85);
        assertThat(res.get("T3").grade()).isEqualTo("고위험");
    }

    @Test
    @DisplayName("월 예산 검토 큐: kk = round_half_even(n·k/1000), 점수 내림차순 안정 정렬(동점은 원래 순서), minScore 미만 제외")
    void budgetPolicy() {
        List<Candidate> month = new ArrayList<>();
        for (int i = 0; i < 1250; i++) {
            month.add(new Candidate("T" + i, i % 100 == 0 ? 90 : (i % 7 == 0 ? 40 : 5), i));
        }
        assertThat(ReviewBudgetPolicy.budget(1250, 20)).isEqualTo(25);
        assertThat(ReviewBudgetPolicy.budget(1125, 20)).isEqualTo(22);    // 22.5 → 22 (짝수 반올림)
        var sel = ReviewBudgetPolicy.select(month, 20, 0);
        assertThat(sel).hasSize(25);
        assertThat(sel.subList(0, 13)).allMatch(c -> c.score() == 90);
        assertThat(sel.get(0).txnId()).isEqualTo("T0");
        assertThat(sel.get(13).txnId()).isEqualTo("T7");                  // 동점 40점은 원래 순서
        assertThat(ReviewBudgetPolicy.select(month, 20, 50)).hasSize(13);
    }

    @Test
    @DisplayName("케이스 상태 머신(A6.2): 허용 전이만, 판정 라벨 → 정당/부당 이벤트, 소명 대기 상태")
    void caseMachine() {
        assertThat(CaseMachine.next("OPEN", "request")).contains("EXPLANATION_REQUESTED");
        assertThat(CaseMachine.next("OPEN", "resolve_legit")).isEmpty();      // 소명 없이 판정 불가
        assertThat(CaseMachine.next("EXPLANATION_REQUESTED", "overdue")).contains("OVERDUE");
        assertThat(CaseMachine.next("OVERDUE", "submit")).contains("SUBMITTED");
        assertThat(CaseMachine.next("SUBMITTED", CaseMachine.resolveEvent("MISUSE"))).contains("RESOLVED_MISUSE");
        assertThat(CaseMachine.next("SUBMITTED", CaseMachine.resolveEvent("FALSE_POSITIVE"))).contains("RESOLVED_LEGIT");
        assertThat(CaseMachine.next("CLOSED", "close")).isEmpty();
        assertThat(CaseMachine.AWAITING_EXPLANATION).contains("OVERDUE", "SUPPLEMENT_REQUESTED");
    }
}
