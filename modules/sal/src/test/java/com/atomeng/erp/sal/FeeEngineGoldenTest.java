package com.atomeng.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.domain.fee.FeeEngine;
import com.atomeng.erp.sal.domain.fee.FeeModels.Expense;
import com.atomeng.erp.sal.domain.fee.FeeModels.FacilityProfile;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcCommand;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcResult;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeLine;
import com.atomeng.erp.sal.domain.fee.FeeModels.ManDayOverride;
import com.atomeng.erp.sal.domain.fee.FeeRuleSet;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * FR-SAL-009 골든 마스터 (01-IMPL Step 5 FeeEngineGoldenTest). 기대값은 tools/sal_golden_gen.py 가 Python Decimal 로
 * <b>독립 구현</b>해 만든 합성 사례 22건(실제 수기 견적 20건이 오면 교체) — 원 단위 · 라인 단위 완전 일치.
 * 규칙 값은 ⚠️ 예시(법령 최신본 확인 필요).
 */
class FeeEngineGoldenTest {

    static final ObjectMapper OM = new ObjectMapper();
    static final FeeEngine ENGINE = new FeeEngine();

    static JsonNode resource(String path) {
        try (InputStream in = FeeEngineGoldenTest.class.getResourceAsStream("/golden/" + path)) {
            if (in == null) {
                throw new IllegalStateException("픽스처 없음: " + path);
            }
            return OM.readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static FeeRuleSet rule(String file) {
        return FeeRuleSet.parse(UUID.nameUUIDFromBytes(file.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                resource("rules/" + file));
    }

    static FacilityProfile facility(JsonNode f) {
        return new FacilityProfile(f.get("facilityClass").asText(), f.get("grossAreaM2").decimalValue(),
                f.get("structure").asText(), f.get("basementFloors").asInt(), f.get("floors").asInt(),
                f.get("completionYear").asInt(), f.get("inUse").asBoolean());
    }

    static FeeCalcCommand command(LocalDate asOf, JsonNode c) {
        List<Expense> ex = new ArrayList<>();
        c.path("expenses").forEach(e -> ex.add(new Expense(e.get("item").asText(), e.get("quantity").decimalValue(),
                e.get("unitPrice").decimalValue())));
        List<ManDayOverride> ov = new ArrayList<>();
        c.path("manDayOverrides").forEach(o -> ov.add(new ManDayOverride(o.get("workItem").asText(),
                o.get("adjFactor").decimalValue(), o.path("reason").asText(null))));
        return new FeeCalcCommand(asOf, c.get("serviceType").asText(),
                c.has("overheadRate") ? c.get("overheadRate").decimalValue() : null,
                c.has("techFeeRate") ? c.get("techFeeRate").decimalValue() : null, ex, ov);
    }

    static Stream<Arguments> cases() throws IOException {
        Path dir;
        try {
            dir = Path.of(FeeEngineGoldenTest.class.getResource("/golden/estimates").toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IOException(e);
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(p -> p.getFileName().toString().matches("G-\\d+\\.json")).sorted().toList();
        }
        List<Arguments> out = new ArrayList<>();
        for (Path p : files) {
            JsonNode n = OM.readTree(p.toFile());
            out.add(Arguments.of(n.get("caseId").asText(), n));
        }
        return out.stream();
    }

    @Test
    @DisplayName("골든 픽스처는 20건 이상(01-IMPL Step 0 DoD: 20건)")
    void enoughGoldenCases() throws IOException {
        assertThat(cases().count()).isGreaterThanOrEqualTo(20);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("FR-SAL-009 골든 마스터: 직접인건비 · 직접경비 · 제경비 · 기술료 · 합계 · VAT · 라인별 인·일/금액 원 단위 일치")
    void matchesGoldenToTheWon(String caseId, JsonNode c) {
        LocalDate asOf = LocalDate.parse(c.get("asOfDate").asText());
        FeeCalcResult r = ENGINE.calculate(command(asOf, c.get("command")), rule(c.get("ruleFixture").asText()),
                facility(c.get("facility")));
        JsonNode e = c.get("expected");
        SoftAssertions s = new SoftAssertions();
        s.assertThat(r.directLabor()).as("직접인건비").isEqualByComparingTo(e.get("directLabor").decimalValue());
        s.assertThat(r.directExpense()).as("직접경비").isEqualByComparingTo(e.get("directExpense").decimalValue());
        s.assertThat(r.overhead()).as("제경비").isEqualByComparingTo(e.get("overhead").decimalValue());
        s.assertThat(r.techFee()).as("기술료").isEqualByComparingTo(e.get("techFee").decimalValue());
        s.assertThat(r.subtotal()).as("합계").isEqualByComparingTo(e.get("subtotal").decimalValue());
        s.assertThat(r.vat()).as("VAT").isEqualByComparingTo(e.get("vat").decimalValue());
        List<FeeLine> labor = r.lines().stream().filter(l -> "LABOR".equals(l.lineType())).toList();
        s.assertThat(labor).as("라인 수").hasSize(e.get("lines").size());
        for (int i = 0; i < Math.min(labor.size(), e.get("lines").size()); i++) {
            JsonNode el = e.get("lines").get(i);
            s.assertThat(labor.get(i).workItem() + "/" + labor.get(i).grade()).as("라인 %d", i)
                    .isEqualTo(el.get("workItem").asText() + "/" + el.get("grade").asText());
            s.assertThat(labor.get(i).manDays()).as("라인 %d 인·일", i).isEqualByComparingTo(el.get("manDays").asText());
            s.assertThat(labor.get(i).amount()).as("라인 %d 금액", i).isEqualByComparingTo(el.get("amount").decimalValue());
        }
        s.assertAll();
        // 속성(설계 B10): 합계 ≥ 직접인건비, 합계 절사 단위 준수
        assertThat(r.subtotal()).isGreaterThanOrEqualTo(r.directLabor());
    }

    static FacilityProfile small() {
        return new FacilityProfile("BUILDING", new BigDecimal("8500"), "RC", 1, 8, 2005, false);
    }

    static FeeCalcCommand cmd(BigDecimal overhead, BigDecimal techFee) {
        return new FeeCalcCommand(LocalDate.of(2026, 3, 2), "PRECISE_DIAG", overhead, techFee, List.of(), List.of());
    }

    @Test
    @DisplayName("TC-SAL-011: 제경비율 1.30(범위 1.10–1.20 밖) → SAL-422-002, 기술료율 0.19 도 거부")
    void rateOutOfRange() {
        FeeRuleSet r = rule("fee-rule-2026.json");
        assertThatThrownBy(() -> ENGINE.calculate(cmd(new BigDecimal("1.30"), null), r, small()))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code())
                .isEqualTo("SAL-422-002");
        assertThatThrownBy(() -> ENGINE.calculate(cmd(null, new BigDecimal("0.19")), r, small()))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-002");
        // 경계값(상 · 하한)은 허용
        assertThat(ENGINE.calculate(cmd(new BigDecimal("1.20"), new BigDecimal("0.40")), r, small()).subtotal())
                .isPositive();
    }

    @ParameterizedTest
    @ValueSource(strings = {"50000", "250000", "-1"})
    @DisplayName("TC-SAL-012: 연면적이 모든 구간 밖 → SAL-422-005 수동 산정 필요(보간 · 외삽 금지)")
    void outsideBands(String area) {
        var fac = new FacilityProfile("BUILDING", new BigDecimal(area), "RC", 1, 8, 2005, false);
        assertThatThrownBy(() -> ENGINE.calculate(cmd(null, null), rule("fee-rule-2026.json"), fac))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-005");
        // 규칙에 없는 시설 분류도 수동 산정
        var bridge = new FacilityProfile("BRIDGE", new BigDecimal("1000"), "RC", 0, 1, 2005, false);
        assertThatThrownBy(() -> ENGINE.calculate(cmd(null, null), rule("fee-rule-2026.json"), bridge))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-005");
    }

    @Test
    @DisplayName("01-IMPL Step 4 보안 경계: 보정계수 조건은 화이트리스트 변수 한 단 비교만 — 코드 실행 문자열 거부(SAL-422-008)")
    void rejectsArbitraryExpressions() {
        ObjectNode body = resource("rules/fee-rule-2026.json").deepCopy();
        ((ObjectNode) body.get("adjustmentFactors").get(0)).put("when", "Runtime.exec('x')");
        assertThatThrownBy(() -> FeeRuleSet.parse(UUID.randomUUID(), body))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-008");
        ObjectNode body2 = resource("rules/fee-rule-2026.json").deepCopy();
        ((ObjectNode) body2.get("adjustmentFactors").get(0)).put("when", "salary >= 30");
        assertThatThrownBy(() -> FeeRuleSet.parse(UUID.randomUUID(), body2))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-008");
    }

    @Test
    @DisplayName("규칙 의미 검증(FeeRuleSemanticValidator): 예시 규칙 4종 오류 0, gradeMix 합 0.95 · 구간 불연속은 오류")
    void semanticValidation() {
        for (String f : List.of("fee-rule-2025.json", "fee-rule-2026.json", "fee-rule-2026-rounding.json",
                "fee-rule-2026-floor10.json")) {
            assertThat(rule(f).semanticErrors()).as(f).isEmpty();
        }
        ObjectNode body = resource("rules/fee-rule-2026.json").deepCopy();
        ObjectNode svc = (ObjectNode) body.get("baseManDays").get(0);
        ((ObjectNode) svc.get("gradeMix")).put("BEGINNER", 0.25);
        ((ObjectNode) svc.get("areaBands").get(1)).put("fromM2", 12000);
        assertThat(FeeRuleSet.parse(UUID.randomUUID(), body).semanticErrors())
                .anyMatch(s -> s.contains("gradeMix 합계=0.95")).anyMatch(s -> s.contains("불연속"));
    }

    @Test
    @DisplayName("산정 근거: 보정계수 적용 · 미적용 모두 설명(설계 B4.1 explanations), override 사유 기록")
    void explanations() {
        var fac = new FacilityProfile("BUILDING", new BigDecimal("42350"), "RC", 4, 18, 1998, true);
        var c = new FeeCalcCommand(LocalDate.of(2026, 10, 15), "PRECISE_DIAG", null, null, List.of(),
                List.of(new ManDayOverride("FIELD", new BigDecimal("1.25"), "합성 야간작업")));
        var r = ENGINE.calculate(c, rule("fee-rule-2026.json"), fac);
        assertThat(r.explanations()).anyMatch(s -> s.startsWith("AGE 보정 1.1 미적용 (years=28"))
                .anyMatch(s -> s.startsWith("UNDERGROUND 보정 1.05 적용")).anyMatch(s -> s.startsWith("OCCUPIED 보정 1.1 적용"))
                .anyMatch(s -> s.contains("FIELD 공종 보정계수 1.25 수동 적용 (합성 야간작업)"));
        assertThat(r.warnings()).anyMatch(s -> s.contains("30000–50000"));
        assertThat(r.overheadRate()).isEqualByComparingTo("1.10");
    }
}
