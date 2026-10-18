package com.atomeng.erp.fcd.domain.scoring;

import com.atomeng.erp.fcd.domain.rules.PyOps;
import com.atomeng.erp.fcd.domain.rules.RuleSet;
import com.atomeng.erp.fcd.domain.rules.RuleSet.RuleHit;
import com.atomeng.erp.fcd.domain.rules.RuleSetRegistry;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L4 결합 · 등급 · 설명 (설계 B7.6) — Python {@code detect/engine.py score_frame} 의 거래 1건 이식.
 *
 * <pre>
 * R  = 1 − Π(1 − w_i s_i)            (noisy-OR)
 * R' = 1 − Π_out(1−ws) · (1 − (1 − Π_in(1−ws)) · Π(1 − m_j))   완화는 effect.mitigates 범위의 비하한 규칙에만
 * score = max(round_half_even(100 R'), max floor)
 * </pre>
 * 전액 취소 거래는 applies_to_cancelled 규칙만, L3 게이팅(params.l3_gate): 지정 L3 규칙은 L1/L2 위험 신호가 함께
 * 있을 때만 기여(단독이면 s3 ≥ τ 일 때만).
 */
public final class RiskCombiner {

    private RiskCombiner() {}

    public static final Set<String> ALL_LAYERS = Set.of("L1", "L2", "L3");

    public record Contribution(String ruleId, String layer, double c, String message, List<String> legalBasis) {}

    public record ScoreResult(int score, String grade, double r, double rMitigated, int floor, List<RuleHit> hits,
                              List<RuleHit> mitigations, List<RuleHit> flags, List<Contribution> contributions,
                              List<String> reasons, String rulesetVersion, String l3Status, boolean unverifiedUsed,
                              boolean reviewRequired, boolean hard, Map<String, Double> strengths) {}

    /** 위험 등급 (BR-FCD-010) */
    public static String gradeOf(int score) {
        if (score >= 80) {
            return "고위험";
        }
        if (score >= 60) {
            return "소명요청";
        }
        if (score >= 30) {
            return "주의";
        }
        return "정상";
    }

    /** 등급 코드(DB·API 용 영문) */
    public static String gradeCode(int score) {
        if (score >= 80) {
            return "HIGH";
        }
        if (score >= 60) {
            return "EXPLAIN";
        }
        if (score >= 30) {
            return "WATCH";
        }
        return "NORMAL";
    }

    /**
     * @param tx  tx 네임스페이스(특히 cancelled)
     * @param l3  l3 네임스페이스(없으면 빈 맵)
     */
    public static ScoreResult score(RuleSetRegistry registry, LocalDate date, Map<String, Object> tx,
                                    Map<String, Object> ctx, Map<String, Object> l3, Set<String> layers,
                                    Map<String, Double> weightOverrides) {
        RuleSet rs = registry.resolve(date);
        Map<String, Object> env = new HashMap<>();
        env.put("tx", tx);
        env.put("ctx", ctx);
        env.put("l3", l3 == null ? Map.of() : l3);
        env.put("params", rs.params());
        List<RuleHit> hits = rs.evaluate(env, date, layers, weightOverrides);
        if (PyOps.truthy(tx.get("cancelled"))) {   // 전액 취소 거래는 취소 후 규칙(R-320 등)만
            Set<String> ok = new HashSet<>();
            rs.rules().stream().filter(x -> PyOps.truthy(x.effect().get("applies_to_cancelled")))
                    .forEach(x -> ok.add(x.ruleId()));
            hits = hits.stream().filter(h -> ok.contains(h.ruleId())).toList();
        }
        List<RuleHit> risk = new ArrayList<>(hits.stream().filter(h -> h.kind().equals("risk")).toList());
        Object gateObj = rs.params().get("l3_gate");
        if (gateObj instanceof Map<?, ?> gate && !gate.isEmpty()) {
            Set<String> gated = new HashSet<>();
            if (gate.get("rules") instanceof List<?> l) {
                l.forEach(x -> gated.add(String.valueOf(x)));
            }
            boolean others = risk.stream().anyMatch(h -> !gated.contains(h.ruleId())
                    && (h.layer().equals("L1") || h.layer().equals("L2")));
            Object s3o = l3 == null ? null : l3.get("s3");
            double s3 = PyOps.truthy(s3o) ? PyOps.toFloat(s3o) : 0.0;
            Object tau = gate.get("solo_s3_min");
            double t = tau == null ? 1.01 : PyOps.toFloat(tau);
            if (!others && s3 < t) {
                risk.removeIf(h -> gated.contains(h.ruleId()));
            }
        }
        List<RuleHit> mits = hits.stream().filter(h -> h.kind().equals("mitigation")).toList();
        List<RuleHit> flags = hits.stream().filter(h -> h.kind().equals("flag") || h.reviewRequired()).toList();
        Set<String> scope = new HashSet<>();
        boolean global = false;
        for (RuleHit m : mits) {
            Object lst = rs.rule(m.ruleId()).effect().get("mitigates");
            if (lst == null) {
                global = true;
                break;
            }
            ((List<?>) lst).forEach(x -> scope.add(String.valueOf(x)));
        }
        double mProd = 1.0;
        for (RuleHit m : mits) {
            mProd *= (1 - m.strength());
        }
        double survIn = 1.0;
        double survOut = 1.0;
        int floor = 0;
        List<Contribution> parts = new ArrayList<>();
        for (RuleHit h : risk) {
            double ws = Math.min(0.999999, h.weight() * h.strength());
            boolean hasFloor = h.floorScore() != null && h.floorScore() != 0;
            if ((global || scope.contains(h.ruleId())) && !hasFloor) {
                survIn *= (1 - ws);
            } else {
                survOut *= (1 - ws);
            }
            floor = Math.max(floor, h.floorScore() == null ? 0 : h.floorScore());
            parts.add(new Contribution(h.ruleId(), h.layer(), PyOps.round(-Math.log(1 - ws), 4), h.message(),
                    h.legalBasis()));
        }
        double r = 1 - survIn * survOut;
        double rm = !mits.isEmpty() ? 1 - survOut * (1 - (1 - survIn) * mProd) : r;
        int score = Math.max((int) PyOps.roundHalfEven(100 * rm), floor);
        parts.sort(Comparator.comparingDouble(Contribution::c).reversed());   // 안정 정렬(동률은 적중 순서)
        List<String> reasons = new ArrayList<>();
        parts.stream().limit(3).forEach(p -> reasons.add(p.message()));
        if (layers.contains("L3") && l3 != null && PyOps.truthy(l3.get("top"))) {
            Object s3 = l3.getOrDefault("s3", 0L);
            if (PyOps.gt(s3, 0.9) && l3.get("top") instanceof List<?> top) {
                top.stream().limit(2).forEach(t -> reasons.add("이상탐지 기여: "
                        + (t instanceof Map<?, ?> tm ? String.valueOf(tm.get("label")) : String.valueOf(t))));
            }
        }
        if (!mits.isEmpty()) {
            reasons.add(mits.get(0).message());
        }
        Map<String, Double> sVec = new LinkedHashMap<>();
        for (RuleHit h : risk) {
            boolean hasFloor = h.floorScore() != null && h.floorScore() != 0;
            boolean scoped = (global || scope.contains(h.ruleId())) && !hasFloor;
            sVec.put(h.ruleId(), h.strength() * (scoped ? mProd : 1.0));
        }
        String l3Status = layers.contains("L3") && l3 != null && !l3.isEmpty() ? "OK" : "DISABLED";
        return new ScoreResult(score, gradeOf(score), r, rm, floor, List.copyOf(risk), mits, flags, parts, reasons,
                rs.version(), l3Status, rs.usesUnverified(hits), hits.stream().anyMatch(RuleHit::reviewRequired),
                risk.stream().anyMatch(h -> h.severity().equals("hard")), sVec);
    }
}
