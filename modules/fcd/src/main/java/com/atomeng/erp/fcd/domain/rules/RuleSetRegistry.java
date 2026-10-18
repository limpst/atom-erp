package com.atomeng.erp.fcd.domain.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 여러 버전의 룰셋을 보관하고 기준일에 유효한 ACTIVE 룰셋을 고른다 (Python RuleSetRegistry 이식, BR-FCD-009).
 * 같은 시행일이면 상위 버전 우선. 초안(DRAFT·PENDING_APPROVAL)은 어떤 경우에도 선택되지 않는다.
 */
public final class RuleSetRegistry {

    private final List<RuleSet> sets = new ArrayList<>();

    public RuleSetRegistry add(RuleSet rs) {
        if (sets.stream().anyMatch(x -> x.name().equals(rs.name()) && x.version().equals(rs.version()))) {
            throw new RuleSet.RuleSetError("duplicate version " + rs.name() + ":" + rs.version());
        }
        sets.add(rs);
        return this;
    }

    public List<RuleSet> all() {
        return List.copyOf(sets);
    }

    public Optional<RuleSet> get(String version) {
        return sets.stream().filter(r -> r.version().equals(version)).findFirst();
    }

    public RuleSet resolve(LocalDate on) {
        return sets.stream().filter(rs -> rs.status().equals("ACTIVE") && rs.covers(on))
                .max(Comparator.comparing(RuleSet::effectiveFrom).thenComparing(RuleSet::version, RuleSetRegistry::cmpVersion))
                .orElseThrow(() -> new RuleSet.RuleSetError("no ACTIVE ruleset covers " + on));
    }

    /** Python vkey: '2026.5.0-cal.x' → (2026, 5, 0), 숫자 아닌 조각은 0 */
    public static int cmpVersion(String a, String b) {
        long[] x = vkey(a);
        long[] y = vkey(b);
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            if (x[i] != y[i]) {
                return Long.compare(x[i], y[i]);
            }
        }
        return Integer.compare(x.length, y.length);
    }

    static long[] vkey(String v) {
        String[] parts = v.split("-")[0].split("\\.", -1);
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].matches("\\d+") ? Long.parseLong(parts[i]) : 0;
        }
        return out;
    }
}
