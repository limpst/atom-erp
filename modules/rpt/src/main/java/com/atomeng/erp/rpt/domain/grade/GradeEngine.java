package com.atomeng.erp.rpt.domain.grade;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.domain.RptCodes;
import com.atomeng.erp.rpt.domain.grade.GradeResult.BuildingScore;
import com.atomeng.erp.rpt.domain.grade.GradeResult.Evidence;
import com.atomeng.erp.rpt.domain.grade.GradeResult.FloorScore;
import com.atomeng.erp.rpt.domain.grade.GradeResult.MemberRating;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.Defect;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument.MemberGroup;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 부재 상태평가 → 시설물 안전등급 산정 (설계 06 B7.1, 06-IMPL Step 6). 부작용 없는 순수 함수 — 같은 입력 = 같은 결과.
 * <ul>
 *   <li>지표: crack_width_mm(균열 최대폭) · spall_m2_per_member(Σ박리면적/부재 수, 4자리) ·
 *       rebar_exposure_ratio(철근노출 부재/부재 수, 4자리). 규칙(COND_RATING)에 "{부재}.{지표}" 항목이 있는 지표만 평가</li>
 *   <li>분포 보정: 결함 부재 비율(4자리) &lt; extent.lt 이고 등급이 floor 보다 나쁘면 max(등급+delta, floor)</li>
 *   <li>부재 등급 = 지표 중 최악. 층 = Σ(점수×가중치)/Σ가중치(존재 부재만, 6자리) · 동 = AVG 또는 AVG_WORST_BLEND(6자리)
 *       · 시설물 = 동 연면적 가중(3자리) → GRADE_BAND → 하한 규칙</li>
 * </ul>
 * 기대값은 tools/rpt_golden_gen.py(Python Decimal 독립 구현)와 일치해야 한다(TC-RPT-001 골든).
 */
public final class GradeEngine {

    public static final List<String> INDICATORS = List.of("crack_width_mm", "spall_m2_per_member", "rebar_exposure_ratio");

    private static final Comparator<MemberGroup> GROUP_ORDER = Comparator.comparing(MemberGroup::buildingCode)
            .thenComparing(MemberGroup::floorCode).thenComparing(MemberGroup::memberType);

    public GradeResult compute(SnapshotDocument doc, RuleTables rules) {
        List<MemberGroup> groups = doc.memberGroups().stream().sorted(GROUP_ORDER).toList();
        if (groups.isEmpty()) {
            throw new DomainException(RptCodes.INVALID, "부재 그룹이 없어 상태평가를 할 수 없습니다");
        }
        List<MemberRating> ratings = new ArrayList<>();
        for (MemberGroup g : groups) {
            ratings.add(rate(g, doc.defects().stream().filter(d -> d.inGroup(g)).toList(), rules));
        }
        // 층: 존재 부재 가중치 재정규화
        Map<String, Map<String, List<MemberRating>>> byFloor = new TreeMap<>();
        for (MemberRating r : ratings) {
            byFloor.computeIfAbsent(r.buildingCode(), k -> new TreeMap<>())
                    .computeIfAbsent(r.floorCode(), k -> new ArrayList<>()).add(r);
        }
        List<FloorScore> floorScores = new ArrayList<>();
        List<BuildingScore> buildingScores = new ArrayList<>();
        Map<String, BigDecimal> areas = new HashMap<>();
        doc.buildings().forEach(b -> areas.put(b.code(), b.grossAreaM2()));
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal areaSum = BigDecimal.ZERO;
        for (var be : byFloor.entrySet()) {
            List<BigDecimal> fs = new ArrayList<>();
            for (var fe : be.getValue().entrySet()) {
                BigDecimal wsum = BigDecimal.ZERO;
                BigDecimal sum = BigDecimal.ZERO;
                for (MemberRating r : fe.getValue()) {
                    wsum = wsum.add(r.weight());
                    sum = sum.add(r.score().multiply(r.weight()));
                }
                BigDecimal score = sum.divide(wsum, 6, RoundingMode.HALF_UP);
                fs.add(score);
                floorScores.add(new FloorScore(be.getKey(), fe.getKey(), score));
            }
            BigDecimal avg = fs.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .divide(BigDecimal.valueOf(fs.size()), 6, RoundingMode.HALF_UP);
            BigDecimal bscore = avg;
            if (rules.floorAgg().mode().equals("AVG_WORST_BLEND")) {
                BigDecimal w = rules.floorAgg().worstWeight();
                BigDecimal worst = fs.stream().max(BigDecimal::compareTo).orElseThrow();
                bscore = BigDecimal.ONE.subtract(w).multiply(avg).add(w.multiply(worst)).setScale(6, RoundingMode.HALF_UP);
            }
            buildingScores.add(new BuildingScore(be.getKey(), bscore));
            BigDecimal area = areas.get(be.getKey());
            if (area == null || area.signum() <= 0) {
                throw new DomainException(RptCodes.INVALID, "동 연면적 없음(시설물 점수 가중): " + be.getKey());
            }
            weightedSum = weightedSum.add(bscore.multiply(area));
            areaSum = areaSum.add(area);
        }
        BigDecimal facility = weightedSum.divide(areaSum, 3, RoundingMode.HALF_UP);
        SafetyGrade grade = rules.gradeOf(facility);
        List<String> applied = new ArrayList<>();
        for (RuleTables.FloorRule fr : rules.floorRules()) {
            boolean hit = ratings.stream().anyMatch(r -> fr.memberTypes().contains(r.memberType())
                    && Rating.of(r.rating()).ordinal() >= fr.rating().ordinal());
            if (hit && fr.minGrade().ordinal() > grade.ordinal()) {
                grade = fr.minGrade();
                applied.add(fr.id());
            }
        }
        return new GradeResult(rules.pinned(), List.copyOf(ratings), List.copyOf(floorScores),
                List.copyOf(buildingScores), facility, grade.name(), List.copyOf(applied));
    }

    private MemberRating rate(MemberGroup g, List<Defect> ds, RuleTables rules) {
        if (g.memberCount() <= 0) {
            throw new DomainException(RptCodes.INVALID, "부재 수는 1 이상: " + g);
        }
        BigDecimal n = BigDecimal.valueOf(g.memberCount());
        BigDecimal affected = BigDecimal.valueOf(ds.stream().map(Defect::memberId).filter(Objects::nonNull).distinct()
                .count()).divide(n, 4, RoundingMode.HALF_UP);
        Rating worst = Rating.A;
        List<Evidence> evidence = new ArrayList<>();
        for (String ind : INDICATORS) {
            RuleTables.BandRule item = rules.item(g.memberType() + "." + ind);
            if (item == null) {
                continue;   // 이 부재유형에 적용하지 않는 지표
            }
            List<Defect> src;
            BigDecimal value;
            switch (ind) {
                case "crack_width_mm" -> {
                    src = ofType(ds, "CRACK");
                    value = src.stream().map(Defect::widthMm).filter(Objects::nonNull).max(BigDecimal::compareTo)
                            .orElse(BigDecimal.ZERO);
                }
                case "spall_m2_per_member" -> {
                    src = ofType(ds, "SPALL");
                    value = src.stream().map(Defect::areaM2).filter(Objects::nonNull)
                            .reduce(BigDecimal.ZERO, BigDecimal::add).divide(n, 4, RoundingMode.HALF_UP);
                }
                default -> {
                    src = ofType(ds, "REBAR_EXPOSURE");
                    value = BigDecimal.valueOf(src.stream().map(Defect::memberId).filter(Objects::nonNull).distinct()
                            .count()).divide(n, 4, RoundingMode.HALF_UP);
                }
            }
            RuleTables.Band band = item.rate(value);
            Rating r = band.rating();
            RuleTables.Extent ex = rules.extent();
            if (ex != null && affected.compareTo(ex.lt()) < 0 && r.ordinal() > ex.floor().ordinal()) {
                r = Rating.worse(r.shift(ex.delta()), ex.floor());
            }
            worst = Rating.worse(worst, r);
            evidence.add(new Evidence(ind, value, item.unit(), r.code(), band.lt(),
                    src.stream().map(Defect::id).sorted().toList()));
        }
        return new MemberRating(g.buildingCode(), g.floorCode(), g.memberType(), g.memberCount(), worst.code(),
                rules.scores().get(worst), rules.weightOf(g.memberType()), affected, List.copyOf(evidence));
    }

    private static List<Defect> ofType(List<Defect> ds, String type) {
        return ds.stream().filter(d -> type.equals(d.defectType())).toList();
    }
}
