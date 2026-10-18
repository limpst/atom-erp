package com.atomeng.erp.fac.domain.cycle;

import com.atomeng.erp.fac.domain.model.FacTypes.Grade;
import com.atomeng.erp.fac.domain.model.FacTypes.InspectionType;
import com.atomeng.erp.fac.domain.model.FacTypes.SitbeopClass;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 법정 점검주기 계산 엔진 (설계 02 B7.1, 02-IMPL Step 10, BR-FAC-006·007).
 *
 * <p>규칙은 전부 {@link RuleSource}(DB 의 시행일 기반 규칙 테이블)에서 온다 — 주기 숫자 하드코딩 금지.
 * <ol>
 *   <li>기준일 = 해당 유형 마지막 수행일과, 그 유형을 갈음(substitutes)하는 상위 점검 수행일 중 늦은 날</li>
 *   <li>규칙 선택 시점 = 기준일(없으면 today) — 그 시점에 유효한 규칙. 등급 일치 규칙 우선, 없으면 등급 무관 규칙</li>
 *   <li>도래일 = 기준일 + 주기(개월). 이력이 없으면 first_due_basis(사용승인일) + first_due_months</li>
 *   <li>최신 등급이 바뀌면 같은 기준일에 새 등급 규칙이 적용되어 즉시 단축·연장된다</li>
 * </ol>
 * 순수 도메인 로직(Spring 의존 없음).
 */
public final class InspectionCycleEngine {

    /** 규칙 조회 포트 */
    public interface RuleSource {
        /** law·종별·유형 일치, grade 일치(또는 grade=null 이면 등급 무관 규칙), asOf 에 유효한 규칙 */
        Optional<CycleRule> findEffective(String lawCode, String facilityClass, Grade grade, InspectionType type,
                                          LocalDate asOf);
    }

    public record CycleRule(UUID id, String ruleCode, String lawCode, String facilityClass, Grade grade,
                            InspectionType type, int cycleMonths, String firstDueBasis, Integer firstDueMonths,
                            List<InspectionType> substitutes, LocalDate effectiveFrom, LocalDate effectiveTo,
                            String legalReference) {}

    /** 계산 입력 — 분류·최신 등급·유형별 마지막 수행일 */
    public record BuildingSnapshot(UUID buildingId, SitbeopClass sitbeopClass, boolean bmaTarget,
                                   LocalDate approvalDate, Grade latestGrade,
                                   Map<InspectionType, LocalDate> lastPerformed) {}

    public record DueCalculation(InspectionType type, LocalDate baseDate, LocalDate dueDate, CycleRule rule,
                                 Map<String, Object> trace) {}

    private final RuleSource rules;

    public InspectionCycleEngine(RuleSource rules) {
        this.rules = rules;
    }

    public List<DueCalculation> compute(BuildingSnapshot b, LocalDate today) {
        List<DueCalculation> out = new ArrayList<>();
        for (InspectionType type : List.of(InspectionType.REGULAR, InspectionType.DETAILED,
                InspectionType.PRECISION_DIAG, InspectionType.BMA_CHECK)) {
            String law;
            String cls;
            if (type == InspectionType.BMA_CHECK) {
                if (!b.bmaTarget()) {
                    continue;
                }
                law = "BMA";
                cls = "BMA";
            } else {
                if (b.sitbeopClass() == null || b.sitbeopClass() == SitbeopClass.NONE) {
                    continue;
                }
                law = "SITBEOP";
                cls = b.sitbeopClass().name();
            }
            calc(b, today, law, cls, type).ifPresent(out::add);
        }
        return out;
    }

    private Optional<DueCalculation> calc(BuildingSnapshot b, LocalDate today, String law, String cls,
                                          InspectionType type) {
        // 1) 기준일: 자기 유형 + 갈음하는 상위 유형 수행일 중 최신
        LocalDate own = b.lastPerformed().get(type);
        LocalDate basis = own;
        InspectionType basisFrom = own == null ? null : type;
        for (var e : b.lastPerformed().entrySet()) {
            if (e.getKey() == type || e.getValue() == null) {
                continue;
            }
            Optional<CycleRule> upper = select(law, cls, b.latestGrade(), e.getKey(), e.getValue());
            if (upper.isPresent() && upper.get().substitutes().contains(type)
                    && (basis == null || e.getValue().isAfter(basis))) {
                basis = e.getValue();
                basisFrom = e.getKey();
            }
        }
        // 2) 규칙: 기준일 시점 유효 규칙
        LocalDate ruleDate = basis != null ? basis : today;
        Optional<CycleRule> found = select(law, cls, b.latestGrade(), type, ruleDate);
        if (found.isEmpty()) {
            return Optional.empty(); // 해당 유형 비대상
        }
        CycleRule rule = found.get();
        // 3) 도래일
        LocalDate due;
        String dueBasis;
        if (basis != null) {
            due = basis.plusMonths(rule.cycleMonths());
            dueBasis = "LAST_PERFORMED";
        } else if ("APPROVAL_DATE".equals(rule.firstDueBasis()) && b.approvalDate() != null) {
            int months = rule.firstDueMonths() != null ? rule.firstDueMonths() : rule.cycleMonths();
            due = b.approvalDate().plusMonths(months);
            dueBasis = "APPROVAL_DATE";
        } else {
            return Optional.empty(); // 기준일 없음 → 수기 확인 필요(도래 미생성)
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("lawCode", law);
        trace.put("facilityClass", cls);
        trace.put("grade", b.latestGrade() == null ? null : b.latestGrade().name());
        trace.put("ruleId", rule.id().toString());
        trace.put("ruleCode", rule.ruleCode());
        trace.put("cycleMonths", rule.cycleMonths());
        trace.put("ruleEffectiveFrom", rule.effectiveFrom().toString());
        trace.put("dueBasis", dueBasis);
        trace.put("basis", basis == null ? (b.approvalDate() == null ? null : b.approvalDate().toString())
                : basis.toString());
        trace.put("basisFrom", basisFrom == null ? null : basisFrom.name());
        trace.put("legalRef", rule.legalReference());
        return Optional.of(new DueCalculation(type, basis, due, rule, trace));
    }

    private Optional<CycleRule> select(String law, String cls, Grade grade, InspectionType type, LocalDate asOf) {
        if (grade != null) {
            Optional<CycleRule> exact = rules.findEffective(law, cls, grade, type, asOf);
            if (exact.isPresent()) {
                return exact;
            }
        }
        return rules.findEffective(law, cls, null, type, asOf);
    }

    /** 인메모리 RuleSource (단위 테스트·시뮬레이션용) */
    public static RuleSource inMemory(List<CycleRule> all) {
        return (law, cls, grade, type, asOf) -> all.stream()
                .filter(r -> r.lawCode().equals(law) && r.facilityClass().equals(cls) && r.type() == type)
                .filter(r -> grade == null ? r.grade() == null : grade == r.grade())
                .filter(r -> !r.effectiveFrom().isAfter(asOf)
                        && (r.effectiveTo() == null || r.effectiveTo().isAfter(asOf)))
                .max(Comparator.comparing(CycleRule::effectiveFrom));
    }
}
