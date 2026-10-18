package com.atomeng.erp.fcd.domain.casework;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 케이스(소명) 상태 머신 (설계 A6.2, Python {@code casework.CASE_MACHINE} 이식).
 * 원칙(F12): 시스템은 판정하지 않는다 · 판정 전 "부정" 표현 금지 · 재채점으로 자동 종결 금지.
 */
public final class CaseMachine {

    private CaseMachine() {}

    public static final Set<String> LABELS = Set.of("LEGIT", "FALSE_POSITIVE", "MINOR_VIOLATION", "MISUSE");

    /** 상태 → (이벤트 → 다음 상태) */
    public static final Map<String, Map<String, String>> TRANSITIONS = Map.of(
            "OPEN", Map.of("request", "EXPLANATION_REQUESTED"),
            "EXPLANATION_REQUESTED", Map.of("submit", "SUBMITTED", "overdue", "OVERDUE"),
            "OVERDUE", Map.of("submit", "SUBMITTED"),
            "SUBMITTED", Map.of("request_supplement", "SUPPLEMENT_REQUESTED", "resolve_legit", "RESOLVED_LEGIT",
                    "resolve_misuse", "RESOLVED_MISUSE", "escalate", "ESCALATED"),
            "SUPPLEMENT_REQUESTED", Map.of("submit", "SUBMITTED"),
            "ESCALATED", Map.of("resolve_legit", "RESOLVED_LEGIT", "resolve_misuse", "RESOLVED_MISUSE"),
            "RESOLVED_MISUSE", Map.of("start_recovery", "RECOVERY"),
            "RECOVERY", Map.of("close", "CLOSED"),
            "RESOLVED_LEGIT", Map.of("close", "CLOSED"));

    /** 소명 대기(정산 상신 차단 — BR-FCD-011) */
    public static final Set<String> AWAITING_EXPLANATION = Set.of("EXPLANATION_REQUESTED", "OVERDUE", "SUPPLEMENT_REQUESTED");
    /** 미판정(전표 생성 차단 — BR-FCD-016) */
    public static final Set<String> UNRESOLVED = Set.of("OPEN", "EXPLANATION_REQUESTED", "OVERDUE", "SUBMITTED",
            "SUPPLEMENT_REQUESTED", "ESCALATED");

    public static Optional<String> next(String status, String event) {
        return Optional.ofNullable(TRANSITIONS.getOrDefault(status, Map.of()).get(event));
    }

    /** 판정 라벨 → 전이 이벤트 */
    public static String resolveEvent(String label) {
        return label.equals("MISUSE") || label.equals("MINOR_VIOLATION") ? "resolve_misuse" : "resolve_legit";
    }
}
