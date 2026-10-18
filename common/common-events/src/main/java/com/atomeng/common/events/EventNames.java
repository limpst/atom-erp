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
        if (!(lastWord.endsWith("ed") || IRREGULAR.matcher(lastWord).matches()
                || lastWord.endsWith("ing"))) {
            throw new IllegalArgumentException("이벤트 동사는 과거형이어야 합니다: " + eventType);
        }
        return eventType;
    }

    private static final Pattern IRREGULAR = Pattern.compile(
            "^(sent|paid|made|done|built|set|run|won|lost|held|written|drawn|taken|given|sold|bought|begun|"
            + "chosen|frozen|withdrawn|overridden|undone|reset|rebuilt|resent|spent|split|shut|put|cut|overdue)$");
}
