package com.atomeng.erp.fac;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.BuildingSnapshot;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.CycleRule;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.DueCalculation;
import com.atomeng.erp.fac.domain.model.FacTypes.Grade;
import com.atomeng.erp.fac.domain.model.FacTypes.InspectionType;
import com.atomeng.erp.fac.domain.model.FacTypes.SitbeopClass;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * 점검주기 엔진 골든 테스트 (02-IMPL Step 10). 규칙 값은 테스트 전용 합성값 — 실제 법정 주기와 무관.
 */
class InspectionCycleEngineTest {

    static final LocalDate TODAY = LocalDate.of(2027, 1, 1);

    static CycleRule r(String code, String cls, Grade g, InspectionType t, int months, String from, String to,
                       List<InspectionType> subs, String firstBasis, Integer firstMonths) {
        return new CycleRule(UUID.nameUUIDFromBytes(code.getBytes()), code, "SITBEOP", cls, g, t, months, firstBasis,
                firstMonths, subs, LocalDate.parse(from), to == null ? null : LocalDate.parse(to), "합성 규칙 " + code);
    }

    static final List<CycleRule> RULES = List.of(
            // 정밀점검: 등급별 (2018 규칙), 2026-07-01 부터 B 등급 주기가 30개월로 바뀌는 가상 개정
            r("DET-B-v2018", "CLASS1", Grade.B, InspectionType.DETAILED, 36, "2018-01-18", "2026-07-01",
                    List.of(InspectionType.REGULAR), null, null),
            r("DET-B-v2026", "CLASS1", Grade.B, InspectionType.DETAILED, 30, "2026-07-01", null,
                    List.of(InspectionType.REGULAR), null, null),
            r("DET-D-v2018", "CLASS1", Grade.D, InspectionType.DETAILED, 24, "2018-01-18", null,
                    List.of(InspectionType.REGULAR), null, null),
            r("DET-NG-v2018", "CLASS1", null, InspectionType.DETAILED, 36, "2018-01-18", null,
                    List.of(InspectionType.REGULAR), "APPROVAL_DATE", 36),
            r("REG-ALL-v2018", "CLASS1", null, InspectionType.REGULAR, 6, "2018-01-18", null, List.of(), null, null),
            r("REG-D-v2018", "CLASS1", Grade.D, InspectionType.REGULAR, 4, "2018-01-18", null, List.of(), null, null),
            r("REG-ALL2-v2018", "CLASS2", null, InspectionType.REGULAR, 6, "2018-01-18", null, List.of(), null, null));

    static final InspectionCycleEngine ENGINE = new InspectionCycleEngine(InspectionCycleEngine.inMemory(RULES));

    static BuildingSnapshot snap(SitbeopClass cls, Grade g, LocalDate approval, Map<InspectionType, LocalDate> last) {
        return new BuildingSnapshot(UUID.randomUUID(), cls, false, approval, g, last);
    }

    static DueCalculation due(List<DueCalculation> calcs, InspectionType t) {
        return calcs.stream().filter(c -> c.type() == t).findFirst().orElseThrow();
    }

    static Map<InspectionType, LocalDate> last(InspectionType t, String date) {
        Map<InspectionType, LocalDate> m = new EnumMap<>(InspectionType.class);
        if (date != null) {
            m.put(t, LocalDate.parse(date));
        }
        return m;
    }

    @ParameterizedTest(name = "{0}: {1}/{2} {3} last={4} → {5} ({6})")
    @CsvSource({
            // caseId, cls, grade, type, lastPerformed, expectedDue, ruleCode
            "G01, CLASS1, B, DETAILED, 2024-03-14, 2027-03-14, DET-B-v2018",   // 시행일 이전 기준일 → 구 규칙 36개월
            "G02, CLASS1, B, DETAILED, 2026-08-01, 2029-02-01, DET-B-v2026",   // 시행일 이후 기준일 → 신 규칙 30개월
            "G03, CLASS1, D, DETAILED, 2024-03-14, 2026-03-14, DET-D-v2018",   // 등급 일치 규칙 우선
            "G04, CLASS1, C, DETAILED, 2024-03-14, 2027-03-14, DET-NG-v2018",  // C 규칙 없음 → 등급 무관 규칙
            "G05, CLASS2, B, REGULAR,  2026-06-30, 2026-12-30, REG-ALL2-v2018",
            "G06, CLASS1, D, REGULAR,  2026-06-30, 2026-10-30, REG-D-v2018",
            "G07, CLASS1, B, DETAILED, 2026-06-30, 2029-06-30, DET-B-v2018",   // 시행일 전날 → 구 규칙
            "G08, CLASS1, B, DETAILED, 2026-07-01, 2029-01-01, DET-B-v2026",   // 시행일 당일 → 신 규칙
    })
    @DisplayName("TC-FAC-004 골든: 기준일 시점에 유효한 규칙 선택, 등급 일치 우선")
    void golden(String caseId, SitbeopClass cls, Grade grade, InspectionType type, LocalDate lastPerformed,
                LocalDate expectedDue, String ruleCode) {
        var calcs = ENGINE.compute(snap(cls, grade, LocalDate.of(1990, 1, 1), last(type, lastPerformed.toString())),
                TODAY);
        var d = due(calcs, type);
        assertThat(d.dueDate()).as(caseId).isEqualTo(expectedDue);
        assertThat(d.trace().get("ruleCode")).as(caseId).isEqualTo(ruleCode);
    }

    @Test
    @DisplayName("TC-FAC-003(엔진): 같은 수행일에서 등급 B→D 하향 시 도래일 단축 (36→24개월)")
    void downgradeShortensCycle() {
        var lastDet = last(InspectionType.DETAILED, "2024-03-14");
        var b = due(ENGINE.compute(snap(SitbeopClass.CLASS1, Grade.B, null, lastDet), TODAY), InspectionType.DETAILED);
        var d = due(ENGINE.compute(snap(SitbeopClass.CLASS1, Grade.D, null, lastDet), TODAY), InspectionType.DETAILED);
        assertThat(d.dueDate()).isBefore(b.dueDate()).isEqualTo(LocalDate.of(2026, 3, 14));
    }

    @Test
    @DisplayName("BR-FAC-007: 정밀점검 수행이 정기점검을 갈음 — 정기점검 기준일이 정밀점검 수행일로 당겨짐")
    void substitution() {
        Map<InspectionType, LocalDate> m = new EnumMap<>(InspectionType.class);
        m.put(InspectionType.REGULAR, LocalDate.of(2026, 1, 10));
        m.put(InspectionType.DETAILED, LocalDate.of(2026, 5, 20));
        var reg = due(ENGINE.compute(snap(SitbeopClass.CLASS1, Grade.B, null, m), TODAY), InspectionType.REGULAR);
        assertThat(reg.baseDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(reg.dueDate()).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(reg.trace().get("basisFrom")).isEqualTo("DETAILED");
    }

    @Test
    @DisplayName("최초 점검: 이력이 없으면 first_due_basis=APPROVAL_DATE + first_due_months, 없으면 도래 미생성")
    void firstDueFromApprovalDate() {
        var calcs = ENGINE.compute(snap(SitbeopClass.CLASS1, null, LocalDate.of(2020, 4, 1), Map.of()), TODAY);
        var det = due(calcs, InspectionType.DETAILED);
        assertThat(det.baseDate()).isNull();
        assertThat(det.dueDate()).isEqualTo(LocalDate.of(2023, 4, 1));
        assertThat(det.trace().get("dueBasis")).isEqualTo("APPROVAL_DATE");
        assertThat(calcs).noneMatch(c -> c.type() == InspectionType.REGULAR); // 기준일 없음
        assertThat(ENGINE.compute(snap(SitbeopClass.NONE, Grade.B, null, Map.of()), TODAY)).isEmpty();
    }

    @Test
    @DisplayName("BR-FAC-006: 계산 근거(calc_trace)에 규칙 ID·코드·주기·법령 근거가 남는다")
    void traceHasLegalBasis() {
        var d = due(ENGINE.compute(snap(SitbeopClass.CLASS1, Grade.B, null, last(InspectionType.DETAILED,
                "2024-03-14")), TODAY), InspectionType.DETAILED);
        assertThat(d.trace()).containsKeys("ruleId", "ruleCode", "cycleMonths", "legalRef", "basis", "grade");
        assertThat(d.trace().get("cycleMonths")).isEqualTo(36);
    }
}
