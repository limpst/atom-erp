package com.atomeng.erp.qar.domain.autocheck;

import com.atomeng.erp.qar.domain.Severity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * RuleRunner 의 순수 부분 (07-IMPL Step 4): 규칙 세트의 활성 규칙을 가상 스레드로 병렬 실행, 규칙별 타임아웃,
 * 예외 · 타임아웃은 해당 규칙 INFO(RULE_ERROR)로 남기고 계속한다. 결과 순서 = 규칙 세트 순서(재현성).
 */
public final class AutoCheckEngine {

    /** 규칙 세트에 고정된 규칙 정의 */
    public record RuleDef(String ruleId, Severity severity, RuleParams params, boolean enabled, List<String> appliesTo) {
        public boolean applies(String reportType) {
            return enabled && (appliesTo.contains("*") || appliesTo.contains(reportType));
        }
    }

    public record Result(String ruleId, Severity severity, Finding finding) {}

    private final Map<String, AutoCheckRule> rules = new LinkedHashMap<>();
    private final Duration perRuleTimeout;

    public AutoCheckEngine(List<AutoCheckRule> rules, Duration perRuleTimeout) {
        rules.forEach(r -> this.rules.put(r.id(), r));
        this.perRuleTimeout = perRuleTimeout;
    }

    public List<String> ruleIds() {
        return List.copyOf(rules.keySet());
    }

    public List<Result> run(ReviewContext ctx, List<RuleDef> defs) {
        List<RuleDef> active = defs.stream().filter(d -> d.applies(ctx.reportType())).toList();
        List<Result> out = new ArrayList<>();
        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<Finding>>> futures = new ArrayList<>();
            for (RuleDef d : active) {
                AutoCheckRule rule = rules.get(d.ruleId());
                futures.add(rule == null ? null : exec.submit(() -> rule.evaluate(ctx, d.params())));
            }
            for (int i = 0; i < active.size(); i++) {
                RuleDef d = active.get(i);
                Future<List<Finding>> f = futures.get(i);
                if (f == null) {
                    out.add(new Result(d.ruleId(), Severity.INFO, Finding.info(null, "RULE_ERROR: 구현 없는 규칙 " + d.ruleId())));
                    continue;
                }
                try {
                    for (Finding x : f.get(perRuleTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                        out.add(new Result(d.ruleId(), x.effective(d.severity()), x));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                } catch (Exception e) {
                    f.cancel(true);
                    out.add(new Result(d.ruleId(), Severity.INFO, Finding.info(null, "RULE_ERROR: " + rootMessage(e))));
                }
            }
        }
        return out;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) {
            c = c.getCause();
        }
        return c.getClass().getSimpleName() + (c.getMessage() == null ? "" : " " + c.getMessage());
    }

    /** 심각도별 개수 */
    public static Map<Severity, Integer> counts(List<Result> results) {
        Map<Severity, Integer> m = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            m.put(s, 0);
        }
        results.forEach(r -> m.merge(r.severity(), 1, Integer::sum));
        return m;
    }
}
