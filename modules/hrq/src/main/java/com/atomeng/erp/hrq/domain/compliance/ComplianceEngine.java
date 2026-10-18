package com.atomeng.erp.hrq.domain.compliance;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 안전진단전문기관 등록요건 평가 (설계 03 B7.2, 03-IMPL Step 7, BR-HRQ-001·006). 순수 도메인 로직.
 *
 * <p>분야별 규칙을 높은 등급 요건부터 충족시키고, 각 요건에는 <b>가장 낮은 적격 등급부터</b> 배정해 상위 인력을
 * 상위 요건에 남긴다(그리디). count_upward=false 면 정확히 그 등급만 인정. 규칙 값은 DB 규칙 테이블에서 온다.
 */
public final class ComplianceEngine {

    public enum Grade { JUNIOR, INTER, SENIOR, MASTER;
        public boolean atLeast(Grade g) {
            return compareTo(g) >= 0;
        }
    }

    /** 평가일 시점의 인원 1명 */
    public record Staff(UUID id, String empNo, String field, Grade grade, boolean onLeave, Set<String> validCredentials) {}

    public record Rule(UUID id, String field, Grade minGrade, int minCount, String requiredCredential,
                       boolean countLeave, boolean countUpward, String legalReference) {}

    public record RuleOutcome(Rule rule, List<String> assignedEmpNos, int shortage) {}

    public record FieldResult(String field, boolean satisfied, List<RuleOutcome> outcomes) {
        public Map<String, Integer> shortageByGrade() {
            Map<String, Integer> m = new LinkedHashMap<>();
            outcomes.stream().filter(o -> o.shortage() > 0)
                    .forEach(o -> m.merge(o.rule().minGrade().name(), o.shortage(), Integer::sum));
            return m;
        }
    }

    private ComplianceEngine() {}

    public static List<FieldResult> evaluateAll(List<Staff> pool, List<Rule> rules) {
        Map<String, List<Rule>> byField = new LinkedHashMap<>();
        rules.forEach(r -> byField.computeIfAbsent(r.field(), k -> new ArrayList<>()).add(r));
        List<FieldResult> out = new ArrayList<>();
        byField.forEach((field, rs) -> out.add(evaluateField(field,
                pool.stream().filter(s -> s.field().equals(field)).toList(), rs)));
        return out;
    }

    public static FieldResult evaluateField(String field, List<Staff> pool, List<Rule> rules) {
        List<Rule> sorted = rules.stream().sorted(Comparator.comparing(Rule::minGrade).reversed()).toList();
        Set<UUID> used = new HashSet<>();
        List<RuleOutcome> outcomes = new ArrayList<>();
        for (Rule r : sorted) {
            List<Staff> eligible = pool.stream()
                    .filter(s -> !used.contains(s.id()))
                    .filter(s -> r.countLeave() || !s.onLeave())
                    .filter(s -> s.grade() != null)
                    .filter(s -> r.countUpward() ? s.grade().atLeast(r.minGrade()) : s.grade() == r.minGrade())
                    .filter(s -> r.requiredCredential() == null || s.validCredentials().contains(r.requiredCredential()))
                    .sorted(Comparator.comparing(Staff::grade).thenComparing(Staff::empNo)) // 낮은 적격 등급부터 소모
                    .limit(r.minCount())
                    .toList();
            eligible.forEach(s -> used.add(s.id()));
            outcomes.add(new RuleOutcome(r, eligible.stream().map(Staff::empNo).toList(),
                    Math.max(0, r.minCount() - eligible.size())));
        }
        return new FieldResult(field, outcomes.stream().allMatch(o -> o.shortage() == 0), outcomes);
    }
}
