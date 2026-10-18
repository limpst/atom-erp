package com.atomeng.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.grade.GradeEngine;
import com.atomeng.erp.rpt.domain.grade.GradeResult;
import com.atomeng.erp.rpt.domain.grade.Rating;
import com.atomeng.erp.rpt.domain.grade.RuleTables;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * FR-RPT-006/007 골든 마스터 (06-IMPL Step 6 GradeGoldenTest). 기대값은 tools/rpt_golden_gen.py 가 Python Decimal 로
 * <b>독립 구현</b>해 만든 합성 사례 12건(과거 실제 보고서 10건이 오면 교체) — 부재 등급 · 근거 · 층/동/시설물 점수 · 등급 완전 일치.
 * 규칙 값은 ⚠️ 예시(법령 최신본 확인 필요).
 */
class GradeEngineGoldenTest {

    static final GradeEngine ENGINE = new GradeEngine();
    static final List<String> CASES = IntStream.rangeClosed(1, 12).mapToObj(i -> "GOLD-%02d".formatted(i)).toList();
    static final Set<String> MATCHED = Collections.synchronizedSet(new HashSet<>());

    static JsonNode resource(String path) {
        try (InputStream in = GradeEngineGoldenTest.class.getResourceAsStream("/golden/" + path)) {
            if (in == null) {
                throw new IllegalStateException("픽스처 없음: " + path);
            }
            return RptJson.MAPPER.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static List<JsonNode> ruleFixtures() {
        return List.of(resource("rules/BLD_RC-2025.1.json"), resource("rules/BLD_RC-2026.1.json"));
    }

    /** 유효기간 해석(RuleTableService.resolve 와 같은 규칙): from ≤ asOf &lt; to */
    static RuleTables ruleAt(LocalDate asOf) {
        List<JsonNode> hit = ruleFixtures().stream().filter(r -> !LocalDate.parse(r.get("effectiveFrom").asText()).isAfter(asOf)
                && (r.get("effectiveTo").isNull() || asOf.isBefore(LocalDate.parse(r.get("effectiveTo").asText())))).toList();
        assertThat(hit).as("기준일 %s 유효 규칙 1개", asOf).hasSize(1);
        return RuleTables.parse(hit.get(0));
    }

    static SnapshotDocument snapshot(JsonNode c) {
        try {
            return RptJson.MAPPER.treeToValue(c.get("snapshot"), SnapshotDocument.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Stream<String> cases() {
        return CASES.stream();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("TC-RPT-001: 골든 사례 — 부재 등급 · 근거 · 층/동/시설물 점수 · 산정 등급이 독립 구현과 일치")
    void goldenCase(String id) {
        JsonNode c = resource("cases/" + id + ".json");
        JsonNode exp = c.get("expected");
        RuleTables rules = ruleAt(LocalDate.parse(c.get("asOf").asText()));
        assertThat(rules.versions().get(RuleTables.COND_RATING)).isEqualTo(exp.get("ruleVersion").asText());
        GradeResult r = ENGINE.compute(snapshot(c), rules);

        SoftAssertions s = new SoftAssertions();
        s.assertThat(r.memberRatings()).hasSize(exp.get("memberRatings").size());
        for (int i = 0; i < Math.min(r.memberRatings().size(), exp.get("memberRatings").size()); i++) {
            GradeResult.MemberRating m = r.memberRatings().get(i);
            JsonNode e = exp.get("memberRatings").get(i);
            String where = id + " " + m.buildingCode() + "/" + m.floorCode() + "/" + m.memberType();
            s.assertThat(m.buildingCode() + m.floorCode() + m.memberType()).as(where)
                    .isEqualTo(e.get("buildingCode").asText() + e.get("floorCode").asText() + e.get("memberType").asText());
            s.assertThat(m.rating()).as(where + " rating").isEqualTo(e.get("rating").asText());
            s.assertThat(m.score()).as(where + " score").isEqualByComparingTo(e.get("score").asText());
            s.assertThat(m.weight()).as(where + " weight").isEqualByComparingTo(e.get("weight").asText());
            s.assertThat(m.affectedRatio()).as(where + " affected").isEqualByComparingTo(e.get("affectedRatio").asText());
            s.assertThat(m.evidence()).as(where + " evidence").hasSize(e.get("evidence").size());
            for (int k = 0; k < Math.min(m.evidence().size(), e.get("evidence").size()); k++) {
                GradeResult.Evidence ev = m.evidence().get(k);
                JsonNode ee = e.get("evidence").get(k);
                s.assertThat(ev.indicator()).as(where).isEqualTo(ee.get("indicator").asText());
                s.assertThat(ev.value()).as(where + " " + ev.indicator()).isEqualByComparingTo(ee.get("value").asText());
                s.assertThat(ev.rating()).as(where + " " + ev.indicator()).isEqualTo(ee.get("rating").asText());
                if (ee.get("threshold").isNull()) {
                    s.assertThat(ev.threshold()).as(where).isNull();
                } else {
                    s.assertThat(ev.threshold()).as(where).isEqualByComparingTo(ee.get("threshold").asText());
                }
                List<String> ids = new ArrayList<>();
                ee.get("defectIds").forEach(x -> ids.add(x.asText()));
                s.assertThat(ev.defectIds()).as(where + " defects").isEqualTo(ids);
            }
        }
        for (int i = 0; i < exp.get("floorScores").size(); i++) {
            s.assertThat(r.floorScores().get(i).score()).as(id + " floor " + i)
                    .isEqualByComparingTo(exp.get("floorScores").get(i).get("score").asText());
        }
        for (int i = 0; i < exp.get("buildingScores").size(); i++) {
            s.assertThat(r.buildingScores().get(i).score()).as(id + " building " + i)
                    .isEqualByComparingTo(exp.get("buildingScores").get(i).get("score").asText());
        }
        s.assertThat(r.facilityScore()).as(id + " facility").isEqualByComparingTo(exp.get("facilityScore").asText());
        s.assertThat(r.computedGrade()).as(id + " grade").isEqualTo(exp.get("computedGrade").asText());
        List<String> applied = new ArrayList<>();
        exp.get("appliedFloorRules").forEach(x -> applied.add(x.asText()));
        s.assertThat(r.appliedFloorRules()).as(id + " floor rules").isEqualTo(applied);
        s.assertAll();
        if (r.computedGrade().equals(exp.get("computedGrade").asText())) {
            MATCHED.add(id);
        }
    }

    @AfterAll
    static void matchRate() {
        // TC-RPT-001 수용 기준: 발행 등급 10건 중 9건 이상 일치 — 합성 골든은 독립 구현이라 전 건 일치가 기대값
        assertThat(MATCHED.size()).as("TC-RPT-001 등급 일치 건수").isGreaterThanOrEqualTo(CASES.size() - 1);
    }

    @Test
    @DisplayName("TC-RPT-001 보조: 골든 12건이 A~E 등급 · 분포 보정 · 하한 규칙 · 2025.1/2026.1 규칙을 모두 덮는다")
    void goldenCoverage() {
        Set<String> grades = new HashSet<>();
        Set<String> versions = new HashSet<>();
        boolean floorRule = false;
        boolean extentMitigated = false;
        for (String id : CASES) {
            JsonNode c = resource("cases/" + id + ".json");
            JsonNode exp = c.get("expected");
            RuleTables rules = ruleAt(LocalDate.parse(c.get("asOf").asText()));
            grades.add(exp.get("computedGrade").asText());
            versions.add(exp.get("ruleVersion").asText());
            floorRule |= exp.get("appliedFloorRules").size() > 0;
            for (JsonNode m : exp.get("memberRatings")) {
                for (JsonNode e : m.get("evidence")) {
                    Rating raw = rules.item(m.get("memberType").asText() + "." + e.get("indicator").asText())
                            .rate(new BigDecimal(e.get("value").asText())).rating();
                    extentMitigated |= raw != Rating.of(e.get("rating").asText());   // 분포 보정으로 완화된 지표
                }
            }
        }
        assertThat(grades).containsExactlyInAnyOrder("A", "B", "C", "D", "E");
        assertThat(versions).containsExactlyInAnyOrder("2025.1", "2026.1");
        assertThat(floorRule).isTrue();
        assertThat(extentMitigated).isTrue();
    }

    @Test
    @DisplayName("TC-RPT-002: 같은 스냅샷 100회 재계산 → 결과 해시 동일, 입력 배열 순서를 섞어도 동일")
    void deterministic() {
        JsonNode c = resource("cases/GOLD-03.json");
        RuleTables rules = ruleAt(LocalDate.parse(c.get("asOf").asText()));
        SnapshotDocument doc = snapshot(c);
        String first = ENGINE.compute(doc, rules).sha256();
        for (int i = 0; i < 100; i++) {
            assertThat(ENGINE.compute(doc, rules).sha256()).isEqualTo(first);
        }
        List<SnapshotDocument.Defect> defects = new ArrayList<>(doc.defects());
        List<SnapshotDocument.MemberGroup> groups = new ArrayList<>(doc.memberGroups());
        Collections.shuffle(defects, new Random(7));
        Collections.shuffle(groups, new Random(11));
        SnapshotDocument shuffled = new SnapshotDocument("1", null, doc.buildings(), groups, defects);
        assertThat(ENGINE.compute(shuffled, rules).sha256()).isEqualTo(first);
        // 정규 JSON: 0.30 과 0.3 은 같은 해시
        assertThat(RptJson.sha256(new BigDecimal("0.30"))).isEqualTo(RptJson.sha256(new BigDecimal("0.3")));
    }

    @Test
    @DisplayName("BR-RPT-002: 유효기간 — 2025-12-31 은 2025.1, 2026-01-01 은 2026.1 (같은 결함도 규칙에 따라 등급이 달라질 수 있음)")
    void effectiveDating() {
        assertThat(ruleAt(LocalDate.of(2025, 12, 31)).versions().get(RuleTables.GRADE_BAND)).isEqualTo("2025.1");
        assertThat(ruleAt(LocalDate.of(2026, 1, 1)).versions().get(RuleTables.GRADE_BAND)).isEqualTo("2026.1");
        RuleTables r25 = ruleAt(LocalDate.of(2025, 6, 1));
        RuleTables r26 = ruleAt(LocalDate.of(2026, 6, 1));
        BigDecimal w = new BigDecimal("0.35");
        assertThat(r25.item("COLUMN.crack_width_mm").rate(w).rating()).isEqualTo(Rating.C);
        assertThat(r26.item("COLUMN.crack_width_mm").rate(w).rating()).isEqualTo(Rating.D);
        assertThat(r26.pinned()).containsEntry("COND_RATING", "BLD_RC@2026.1");
    }

    @Test
    @DisplayName("06-IMPL Step 2: rule 의미 검증 — 단위 누락 · 구간 역순 · else 누락 · 단조성 위반 → RPT-422-005")
    void ruleValidation() {
        JsonNode base = resource("rules/BLD_RC-2026.1.json");
        ObjectNode noUnit = base.deepCopy();
        ((ObjectNode) noUnit.at("/condRating/items/COLUMN.crack_width_mm")).remove("unit");
        assertThatThrownBy(() -> RuleTables.parse(noUnit)).isInstanceOf(DomainException.class)
                .hasMessageContaining("unit");
        ObjectNode desc = base.deepCopy();
        ((ObjectNode) desc.at("/condRating/items/COLUMN.crack_width_mm/bands/1")).put("lt", "0.05");
        assertThatThrownBy(() -> RuleTables.parse(desc)).hasMessageContaining("오름차순");
        ObjectNode noElse = base.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) noElse.at("/condRating/items/COLUMN.crack_width_mm/bands")).remove(4);
        assertThatThrownBy(() -> RuleTables.parse(noElse)).hasMessageContaining("else");
        ObjectNode mono = base.deepCopy();
        ((ObjectNode) mono.at("/condRating/items/COLUMN.crack_width_mm/bands/0")).put("rating", "c");
        assertThatThrownBy(() -> RuleTables.parse(mono)).hasMessageContaining("단조성");
        assertThat(RuleTables.parse(base).items()).hasSize(12);
    }

    @Test
    @DisplayName("속성(단조성): 균열폭이 커지면 기둥 부재 등급은 나빠지거나 같다 — 1,000 케이스")
    void monotonic() {
        RuleTables rules = ruleAt(LocalDate.of(2026, 6, 1));
        Random rnd = new Random(42);
        for (int i = 0; i < 1000; i++) {
            BigDecimal a = BigDecimal.valueOf(rnd.nextInt(2000), 3);
            BigDecimal b = a.add(BigDecimal.valueOf(rnd.nextInt(500), 3));
            Rating ra = rules.item("COLUMN.crack_width_mm").rate(a).rating();
            Rating rb = rules.item("COLUMN.crack_width_mm").rate(b).rating();
            assertThat(rb.ordinal()).isGreaterThanOrEqualTo(ra.ordinal());
        }
    }
}
