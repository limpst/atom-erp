package com.atomeng.common.events;

import java.util.regex.Pattern;

/** 이벤트 이름 규칙 검증: {mod}.{aggregate}.{past-verb} (설계 10.1, v0.3) */
public final class EventNames {

    /** mod = 3~4 소문자(core, fld, sal …), aggregate·동사 = 케밥 케이스 */
    public static final Pattern PATTERN = Pattern.compile("^[a-z]{3,4}\\.[a-z][a-z0-9-]*\\.[a-z][a-z0-9-]*$");

    private EventNames() {}

    public static String requireValid(String eventType) {
        if (eventType == null || !PATTERN.matcher(eventType).matches()) {
            throw new IllegalArgumentException(
                    "이벤트 이름은 {mod}.{aggregate}.{과거형 동사} 형식이어야 합니다: " + eventType);
        }
        String verb = eventType.substring(eventType.lastIndexOf('.') + 1);
        String lastWord = verb.substring(verb.lastIndexOf('-') + 1);
        // 과거형 휴리스틱: -ed 또는 불규칙 과거분사 / 진행 상태 형용사(due-approaching 등 v0.3 정식명) 허용
        // 상태 서술(due-soon — 설계 04 B6 정식명)도 허용
        if (!(lastWord.endsWith("ed") || IRREGULAR.matcher(lastWord).matches()
                || lastWord.endsWith("ing") || STATE.matcher(verb).matches() || PHRASAL.matcher(verb).matches())) {
            throw new IllegalArgumentException("이벤트 동사는 과거형이어야 합니다: " + eventType);
        }
        return eventType;
    }

    /** 구동사 과거형(checked-out 등 — 설계 04 B6 정식명): {동사}ed-{불변화사} */
    private static final Pattern PHRASAL = Pattern.compile("^(?:[a-z0-9]+-)*[a-z]+ed-(out|in|up|off|over|down)$");

    /**
     * 과거형이 아닌 정식 상태 서술 이벤트명(설계서에 고정된 것만):
     * due-soon(설계 04 B6), ready-to-sign(설계 01 v0.3 B6 sal.contract.ready-to-sign),
     * discrepancy(설계 05 v0.2 B6.2 fld.ai-suggestion.discrepancy — 명사형 상태 서술)
     */
    private static final Pattern STATE = Pattern.compile("^(due-soon|ready-to-sign|discrepancy)$");

    private static final Pattern IRREGULAR = Pattern.compile(
            "^(sent|paid|made|done|built|set|run|won|lost|held|written|drawn|taken|given|sold|bought|begun|"
            + "chosen|frozen|withdrawn|overridden|undone|reset|rebuilt|resent|spent|split|shut|put|cut|overdue)$");
}
