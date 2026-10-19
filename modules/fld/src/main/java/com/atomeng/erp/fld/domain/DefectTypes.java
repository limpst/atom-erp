package com.atomeng.erp.fld.domain;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 결함 유형 코드(설계 A1.3 초기안) · 유형별 필수 측정값(FR-FLD-005 "필수값 누락 시 저장은 되나 미완성") · 측정 항목 ·
 * 측정 방법(BR-FLD-003). ⚠️ 현업 확정 서명 전 초기안 — 05-IMPL Step 0 shared/codes/defect_types.json 으로 대체 예정.
 */
public final class DefectTypes {

    private DefectTypes() {}

    /** 유형 → 필수 측정 항목(하나라도 없으면 DRAFT). DMG 는 면적 또는 개수 중 하나 */
    public static final Map<String, List<Set<String>>> REQUIRED = Map.ofEntries(
            Map.entry("CRK", List.of(Set.of("width_mm"), Set.of("length_m"))),
            Map.entry("SPL", List.of(Set.of("area_m2"))),
            Map.entry("SPA", List.of(Set.of("area_m2"))),
            Map.entry("RBX", List.of(Set.of("length_m"))),
            Map.entry("COR", List.of(Set.of("area_m2"))),
            Map.entry("EFF", List.of(Set.of("area_m2"))),
            Map.entry("LEK", List.of(Set.of("area_m2"))),
            Map.entry("HCB", List.of(Set.of("area_m2"))),
            Map.entry("DEF", List.of(Set.of("deflection_mm"))),
            Map.entry("TLT", List.of(Set.of("tilt_permil"))),
            Map.entry("DMG", List.of(Set.of("area_m2", "count"))),
            Map.entry("FIN", List.of(Set.of("area_m2"))));

    /** 측정 항목(B7.2 "측정값" — 자동 병합 금지 · 수동 충돌) */
    public static final Set<String> MEASURE_ITEMS = Set.of("width_mm", "length_m", "area_m2", "depth_mm",
            "deflection_mm", "span_m", "tilt_permil", "count", "exposed_length_m");

    public static final Set<String> METHODS = Set.of("INSTRUMENT", "GAUGE", "VISUAL", "AI", "DRAWING_CALC");

    /** 장비 연결 필수 방법(FR-FLD-010 · BR-FLD-008) */
    public static final Set<String> EQUIPMENT_METHODS = Set.of("INSTRUMENT", "GAUGE");

    /** 일반 속성(LWW-Register) */
    public static final Set<String> ATTRIBUTES = Set.of("direction", "through", "active", "memo", "corrosion",
            "severity", "orientation_deg", "indoor");

    public static boolean known(String type) {
        return REQUIRED.containsKey(type);
    }

    public static boolean complete(String type, java.util.function.Predicate<String> has) {
        List<Set<String>> req = REQUIRED.get(type);
        if (req == null) {
            return false;
        }
        return req.stream().allMatch(anyOf -> anyOf.stream().anyMatch(has));
    }
}
