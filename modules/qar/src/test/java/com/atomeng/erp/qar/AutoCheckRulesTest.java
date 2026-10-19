package com.atomeng.erp.qar;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.qar.domain.Severity;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckEngine;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckEngine.Result;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckEngine.RuleDef;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckRule;
import com.atomeng.erp.qar.domain.autocheck.Finding;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.ExternalEvidence;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.Photo;
import com.atomeng.erp.qar.domain.autocheck.ReviewContext.Qualification;
import com.atomeng.erp.qar.domain.autocheck.RuleParams;
import com.atomeng.erp.qar.domain.autocheck.rules.AutoCheckRules;
import com.atomeng.erp.rpt.api.RptRevisionQuery.Defect;
import com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionContent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** AUTO-CHECK 규칙 15종 단위 테스트(정상 · 위반 · 원천 미연동) — 합성 컨텍스트만 */
class AutoCheckRulesTest {

    static final ObjectMapper OM = new ObjectMapper();
    static final String CLEAN = "결함 4건을 조사하였으며 1F 기둥 균열은 2개소(폭 0.25mm, 총 길이 2.4m)로 확인되었다.";
    static final LocalDate AS_OF = LocalDate.of(2026, 10, 19);

    static RuleParams params(String json) {
        try {
            return new RuleParams(OM.readTree(json));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** V0024 2026.1 세트와 같은 규칙 정의 */
    static List<RuleDef> defs2026() {
        Map<String, String[]> m = new java.util.LinkedHashMap<>();
        m.put("AC-NUM-001", new String[] {"BLOCKING", "{\"countTolerance\":0,\"lengthToleranceM\":0.01,\"widthToleranceMm\":0.0}"});
        m.put("AC-NUM-002", new String[] {"BLOCKING", "{\"scale\":2}"});
        m.put("AC-GRD-001", new String[] {"BLOCKING", "{}"});
        m.put("AC-GRD-002", new String[] {"BLOCKING", "{\"minReasonChars\":100}"});
        m.put("AC-GRD-003", new String[] {"WARNING", "{}"});
        m.put("AC-PHO-001", new String[] {"BLOCKING", "{\"requiredDefectTypes\":[\"CRACK\",\"SPALL\",\"REBAR_EXPOSURE\"]}"});
        m.put("AC-PHO-002", new String[] {"WARNING", "{\"maxSurveyDays\":90}"});
        m.put("AC-EQP-001", new String[] {"BLOCKING", "{}"});
        m.put("AC-HRQ-001", new String[] {"BLOCKING", "{}"});
        m.put("AC-SEC-001", new String[] {"BLOCKING", "{\"requiredSections\":[\"ch1.overview\",\"ch4.quantities\","
                + "\"ch6.condition\",\"ch7.opinion\"],\"requiredTables\":[\"ch4.quantities\",\"ch6.condition\"]}"});
        m.put("AC-SEC-002", new String[] {"BLOCKING", "{\"minTests\":{\"REBOUND_HAMMER\":3,\"CARBONATION\":3}}"});
        m.put("AC-TXT-001", new String[] {"BLOCKING", "{}"});
        m.put("AC-TXT-002", new String[] {"WARNING", "{\"phrases\":[{\"pattern\":\"완전히 안전\",\"suggest\":\"대체\"},"
                + "{\"pattern\":\"붕괴\\\\s*위험(이|은)?\\\\s*전혀\",\"suggest\":\"대체2\"}]}"});
        m.put("AC-DRW-001", new String[] {"WARNING", "{}"});
        m.put("AC-DATE-001", new String[] {"WARNING", "{\"maxSurveyDays\":90}"});
        List<RuleDef> out = new ArrayList<>();
        m.forEach((k, v) -> out.add(new RuleDef(k, Severity.valueOf(v[0]), params(v[1]), true, List.of("*"))));
        return out;
    }

    static List<Result> run(RevisionContent c, ExternalEvidence ev) {
        return new AutoCheckEngine(AutoCheckRules.all(), Duration.ofSeconds(10))
                .run(new ReviewContext(c, AS_OF, ev), defs2026());
    }

    static List<Result> of(List<Result> rs, String rule, Severity s) {
        return rs.stream().filter(r -> r.ruleId().equals(rule) && r.severity() == s).toList();
    }

    @Test
    @DisplayName("규칙 15종 등록(AC-HRQ-002 제외) · 정상 보고서는 BLOCKING · WARNING 0, 원천 미연동 규칙은 INFO")
    void cleanReport() {
        assertThat(AutoCheckRules.all()).hasSize(15).extracting(AutoCheckRule::id).doesNotContain("AC-HRQ-002");
        List<Result> rs = run(Fixtures.content(CLEAN), ExternalEvidence.NONE);
        assertThat(rs).filteredOn(r -> r.severity() != Severity.INFO).isEmpty();
        assertThat(rs).filteredOn(r -> r.finding().message().contains("미연동")).extracting(Result::ruleId)
                .containsExactlyInAnyOrder("AC-GRD-003", "AC-PHO-001", "AC-PHO-002", "AC-HRQ-001", "AC-SEC-002");
    }

    @Test
    @DisplayName("TC-QAR-003: 본문 \"1F 기둥 균열이 3개소\" vs 물량표 2개소 → AC-NUM-001 BLOCKING, 앵커(blockId · start/end · quote) · 원천(물량표 셀)")
    void tcQar003() {
        String text = "점검 결과 1F 기둥 균열이 3개소 확인되었다.";
        List<Result> rs = of(run(Fixtures.content(text), ExternalEvidence.NONE), "AC-NUM-001", Severity.BLOCKING);
        assertThat(rs).hasSize(1);
        Finding f = rs.get(0).finding();
        assertThat(f.reportAnchor()).containsEntry("type", "TEXT_RANGE").containsEntry("blockId", Fixtures.OPINION_BLOCK)
                .containsEntry("quote", "3개소");
        int start = (int) f.reportAnchor().get("start");
        assertThat(text.substring(start, (int) f.reportAnchor().get("end"))).isEqualTo("3개소");
        assertThat(f.sourceRef()).containsEntry("kind", "quantityTable").containsEntry("key", "B01/1F/COLUMN/CRACK")
                .containsEntry("field", "count").containsEntry("row", 0);
        assertThat(f.expected()).isEqualTo("2개소");
        // 문맥 없는 수치는 INFO(오탐 피로 방지), 길이 · 폭 불일치는 BLOCKING
        List<Result> all = run(Fixtures.content("균열 길이 3.0m 와 폭 0.40mm 가 1F 기둥에서 관찰되었다. 기타 7개소."),
                ExternalEvidence.NONE);
        assertThat(of(all, "AC-NUM-001", Severity.BLOCKING)).extracting(r -> r.finding().actual())
                .containsExactlyInAnyOrder("3.0m", "0.40mm");
        assertThat(of(all, "AC-NUM-001", Severity.INFO)).extracting(r -> r.finding().message())
                .anyMatch(m -> m.contains("7개소"));
    }

    @Test
    @DisplayName("AC-NUM-001 바인딩 문장 변조 · AC-NUM-002 물량표 재계산 불일치 · 누락 행")
    void numberTables() {
        var defects = Fixtures.defects();
        var rows = List.of(List.of("B01", "1F", "COLUMN", "CRACK", "3", "2.40", "0.00"));
        List<Result> rs = run(Fixtures.content(CLEAN, defects, rows, Fixtures.grade("C", "C", null, List.of())),
                ExternalEvidence.NONE);
        assertThat(of(rs, "AC-NUM-002", Severity.BLOCKING)).extracting(r -> r.finding().message())
                .anyMatch(m -> m.contains("count")).anyMatch(m -> m.contains("빠졌"));
        // 바인딩: 개요 "결함 4건" 인데 스냅샷 5건
        List<Defect> five = new ArrayList<>(defects);
        five.add(new Defect("D-0005", "B01", "1F", "COLUMN", "1F-COL-03", "CRACK", new BigDecimal("0.25"),
                new BigDecimal("1.20"), null, null, null, null, null));
        RevisionContent c = Fixtures.content(CLEAN, defects, Fixtures.quantityRows(), Fixtures.grade("C", "C", null, List.of()));
        RevisionContent tampered = new RevisionContent(c.revision(), c.facilityName(), c.sections(), c.grade(),
                c.memberGroups(), five, c.drawings());
        assertThat(of(run(tampered, ExternalEvidence.NONE), "AC-NUM-001", Severity.BLOCKING))
                .extracting(r -> r.finding().message()).anyMatch(m -> m.contains("바인딩"));
    }

    @Test
    @DisplayName("AC-GRD-001 · 002: 근거 없는 부재 등급 · 확정 전 · override 사유 부족 · 스냅샷에 없는 근거 결함")
    void grades() {
        var noEvidence = new com.atomeng.erp.rpt.api.RptRevisionQuery.Grade("C", "C", null, List.of(), BigDecimal.ONE,
                List.of(new com.atomeng.erp.rpt.api.RptRevisionQuery.MemberRating("B01", "1F", "COLUMN", 10, "c", 0,
                        List.of())));
        List<Result> rs = run(Fixtures.content(CLEAN, Fixtures.defects(), Fixtures.quantityRows(), noEvidence),
                ExternalEvidence.NONE);
        assertThat(of(rs, "AC-GRD-001", Severity.BLOCKING)).hasSize(2);   // 2F 그룹 평가 없음 + 1F 근거 없음
        rs = run(Fixtures.content(CLEAN, Fixtures.defects(), Fixtures.quantityRows(),
                Fixtures.grade("C", null, null, List.of())), ExternalEvidence.NONE);
        assertThat(of(rs, "AC-GRD-002", Severity.BLOCKING)).hasSize(1);
        rs = run(Fixtures.content(CLEAN, Fixtures.defects(), Fixtures.quantityRows(),
                Fixtures.grade("C", "D", "짧은 사유", List.of("D-9999"))), ExternalEvidence.NONE);
        assertThat(of(rs, "AC-GRD-002", Severity.BLOCKING)).hasSize(2);
        rs = run(Fixtures.content(CLEAN, Fixtures.defects(), Fixtures.quantityRows(),
                Fixtures.grade("C", "D", "가".repeat(100), List.of("D-0001"))), ExternalEvidence.NONE);
        assertThat(of(rs, "AC-GRD-002", Severity.BLOCKING)).isEmpty();
    }

    @Test
    @DisplayName("TC-QAR-004: 측정일 기준 검교정 무효 장비 측정값 → AC-EQP-001 BLOCKING(원천 = eqp 장비)")
    void tcQar004() {
        UUID eq = UUID.randomUUID();
        List<Defect> ds = new ArrayList<>(Fixtures.defects());
        ds.set(0, new Defect("D-0001", "B01", "1F", "COLUMN", "1F-COL-01", "CRACK", new BigDecimal("0.25"),
                new BigDecimal("1.20"), null, eq, LocalDate.of(2026, 9, 20), false, "EQP_CAL_EXPIRED"));
        ds.set(1, new Defect("D-0002", "B01", "1F", "COLUMN", "1F-COL-02", "CRACK", new BigDecimal("0.25"),
                new BigDecimal("1.20"), null, eq, LocalDate.of(2026, 9, 20), true, null));
        List<Result> rs = of(run(Fixtures.content(CLEAN, ds, Fixtures.quantityRows(),
                Fixtures.grade("C", "C", null, List.of())), ExternalEvidence.NONE), "AC-EQP-001", Severity.BLOCKING);
        assertThat(rs).hasSize(1);
        assertThat(rs.get(0).finding().sourceRef()).containsEntry("defectId", "D-0001").containsEntry("equipmentId", eq)
                .containsEntry("module", "eqp");
        assertThat(rs.get(0).finding().actual()).contains("EQP_CAL_EXPIRED");
    }

    @Test
    @DisplayName("원천 연결 시 판정: AC-HRQ-001 자격 무효 · AC-PHO-001 사진 누락/번호 불일치 · AC-PHO-002 촬영일 · 중복 해시 · AC-SEC-002 · AC-GRD-003")
    void externalSources() {
        UUID eng = UUID.randomUUID();
        List<Photo> photos = List.of(new Photo("P-1", "D-0001", LocalDate.of(2026, 9, 25), "h1"),
                new Photo("P-2", "D-0002", LocalDate.of(2025, 1, 1), "h1"),
                new Photo("P-3", "D-0003", LocalDate.of(2026, 9, 28), "h3"),
                new Photo("P-9", "D-0099", LocalDate.of(2026, 9, 28), "h9"));
        ExternalEvidence ev = new ExternalEvidence(List.of(new Qualification(eng, "책임기술자", false, "법정교육 미이수")),
                photos, new ReviewContext.GradeHistory("D", LocalDate.of(2023, 9, 1), 0),
                Map.of("REBOUND_HAMMER", 3, "CARBONATION", 1));
        List<Result> rs = run(Fixtures.content(CLEAN), ev);
        assertThat(of(rs, "AC-HRQ-001", Severity.BLOCKING)).hasSize(1);
        assertThat(of(rs, "AC-PHO-001", Severity.BLOCKING)).extracting(r -> r.finding().message())
                .anyMatch(m -> m.contains("D-0004")).anyMatch(m -> m.contains("P-9"));
        assertThat(of(rs, "AC-PHO-002", Severity.WARNING)).hasSize(2);   // 촬영일 밖 1 + 같은 해시 1
        assertThat(of(rs, "AC-SEC-002", Severity.BLOCKING)).extracting(r -> r.finding().sourceRef().get("test"))
                .containsExactly("CARBONATION");
        assertThat(of(rs, "AC-GRD-003", Severity.WARNING)).hasSize(1);   // D → C 개선인데 보수 0건
    }

    @Test
    @DisplayName("AC-SEC-001 필수 섹션 · AC-TXT-001 AI 미수락 · AC-TXT-002 금지 표현(세트별 사전) · AC-DRW-001 망도 결함 수 · AC-DATE-001 날짜")
    void textDrawingDate() {
        RevisionContent base = Fixtures.content("이 건물은 완전히 안전하며 붕괴 위험이 전혀 없다.");
        List<Result> rs = run(base, ExternalEvidence.NONE);
        assertThat(of(rs, "AC-TXT-002", Severity.WARNING)).hasSize(2)
                .allSatisfy(r -> assertThat(r.finding().reportAnchor()).containsEntry("type", "TEXT_RANGE"));
        // 2025.1 사전(붕괴 표현 없음) — 규칙 세트 버전별 파라미터
        List<RuleDef> v2025 = new ArrayList<>(defs2026());
        v2025.replaceAll(d -> d.ruleId().equals("AC-TXT-002") ? new RuleDef(d.ruleId(), d.severity(),
                params("{\"phrases\":[{\"pattern\":\"완전히 안전\",\"suggest\":\"대체\"}]}"), true, List.of("*")) : d);
        List<Result> old = new AutoCheckEngine(AutoCheckRules.all(), Duration.ofSeconds(10))
                .run(new ReviewContext(base, AS_OF, ExternalEvidence.NONE), v2025);
        assertThat(of(old, "AC-TXT-002", Severity.WARNING)).hasSize(1);

        var c = Fixtures.content(CLEAN);
        var secs = new ArrayList<>(c.sections());
        secs.removeIf(s -> s.sectionKey().equals("ch7.opinion"));
        var ai = new com.atomeng.erp.rpt.api.RptRevisionQuery.Section("ch8.ai", "AI 초안", 6, "AI", List.of(
                Fixtures.block(UUID.randomUUID(), "ch8.ai", 1, "PARAGRAPH", "AI 가 쓴 문장", false, "AI", List.of())));
        secs.add(ai);
        var drawingsWrong = List.of(new com.atomeng.erp.rpt.api.RptRevisionQuery.DrawingSheet("B01", "1F", 3));
        var bad = new RevisionContent(new com.atomeng.erp.rpt.api.RptRevisionQuery.RevisionInfo(c.revision().revisionId(),
                c.revision().reportId(), c.revision().projectId(), c.revision().facilityId(), "RT_PRC", 1, "IN_REVIEW", 1,
                "c".repeat(64), "p".repeat(64), true, AS_OF.plusDays(3)), c.facilityName(), secs, c.grade(),
                c.memberGroups(), c.defects(), drawingsWrong);
        rs = run(bad, ExternalEvidence.NONE);
        assertThat(of(rs, "AC-SEC-001", Severity.BLOCKING)).extracting(r -> r.finding().message())
                .anyMatch(m -> m.contains("ch7.opinion"));
        assertThat(of(rs, "AC-TXT-001", Severity.BLOCKING)).hasSize(1);
        assertThat(of(rs, "AC-DRW-001", Severity.WARNING)).extracting(r -> r.finding().message())
                .anyMatch(m -> m.contains("B01 2F"));
        assertThat(of(rs, "AC-DATE-001", Severity.WARNING)).extracting(r -> r.finding().message())
                .anyMatch(m -> m.contains("제출일"));
    }

    @Test
    @DisplayName("RuleRunner: 규칙 예외 · 미구현 규칙은 INFO(RULE_ERROR)로 남기고 나머지는 계속, 적용 유형 밖 규칙은 건너뜀")
    void engineResilience() {
        AutoCheckRule boom = new AutoCheckRule() {
            @Override
            public String id() {
                return "AC-XXX-001";
            }

            @Override
            public List<Finding> evaluate(ReviewContext ctx, RuleParams p) {
                throw new IllegalStateException("합성 오류");
            }
        };
        var engine = new AutoCheckEngine(List.of(boom), Duration.ofSeconds(1));
        List<Result> rs = engine.run(new ReviewContext(Fixtures.content(CLEAN), AS_OF, ExternalEvidence.NONE), List.of(
                new RuleDef("AC-XXX-001", Severity.BLOCKING, RuleParams.EMPTY, true, List.of("*")),
                new RuleDef("AC-YYY-001", Severity.BLOCKING, RuleParams.EMPTY, true, List.of("*")),
                new RuleDef("AC-ZZZ-001", Severity.BLOCKING, RuleParams.EMPTY, true, List.of("RT_REG"))));
        assertThat(rs).hasSize(2).allSatisfy(r -> {
            assertThat(r.severity()).isEqualTo(Severity.INFO);
            assertThat(r.finding().message()).startsWith("RULE_ERROR");
        });
    }
}
