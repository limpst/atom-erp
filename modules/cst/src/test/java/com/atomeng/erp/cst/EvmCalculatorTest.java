package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.cst.domain.evm.Distribution;
import com.atomeng.erp.cst.domain.evm.EvmCalculator;
import com.atomeng.erp.cst.domain.evm.EvmModels.AlertLevel;
import com.atomeng.erp.cst.domain.evm.EvmModels.AlertPolicy;
import com.atomeng.erp.cst.domain.evm.EvmModels.Assessment;
import com.atomeng.erp.cst.domain.evm.EvmModels.EacMethod;
import com.atomeng.erp.cst.domain.evm.EvmModels.EvmAlert;
import com.atomeng.erp.cst.domain.evm.EvmModels.EvmInput;
import com.atomeng.erp.cst.domain.evm.EvmModels.EvmResult;
import com.atomeng.erp.cst.domain.evm.EvmModels.PlannedLine;
import com.atomeng.erp.cst.domain.evm.EvmModels.WbsProgress;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * EVM 엔진 골든 테스트 (설계 B7.2·B10, 08-IMPL Step 11). 금액은 합성값.
 * 골든 기준: BAC 148,000,000 / PV 70,100,000 / EV 69,200,000 / AC 71,300,000 / 계약 185,000,000 (설계 B4 예시)
 */
class EvmCalculatorTest {

    static final EvmCalculator CALC = new EvmCalculator();
    static final LocalDate START = LocalDate.of(2026, 7, 1);
    static final LocalDate AS_OF = START.plusDays(50);

    static BigDecimal bd(long v) {
        return BigDecimal.valueOf(v);
    }

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    /** PV: 140.2M 선형(100일, 50일 경과 → 70.1M) + 7.8M(asOf 이후 시작 → 0) / EV: 50M + 16M + 3.2M = 69.2M */
    static EvmInput golden(long acC, BigDecimal slope) {
        List<PlannedLine> lines = List.of(
                new PlannedLine(bd(140_200_000), START, START.plusDays(100), Distribution.LINEAR),
                new PlannedLine(bd(7_800_000), AS_OF.plusDays(1), AS_OF.plusDays(30), Distribution.LINEAR));
        long acA = 52_000_000;
        long acB = 71_300_000 - acA - acC;
        List<WbsProgress> wbs = List.of(
                new WbsProgress("10-FIELD", bd(100_000_000), bd(50), bd(acA)),
                new WbsProgress("20-MTEST", bd(40_000_000), bd(40), bd(acB)),
                new WbsProgress("30-ANALYSIS", bd(8_000_000), bd(40), bd(acC)));
        return new EvmInput(AS_OF, bd(148_000_000), bd(185_000_000), lines, wbs, bd(71_300_000), slope);
    }

    @Test
    @DisplayName("TC-CST-10: EV 69.2M·AC 71.3M·PV 70.1M → CPI 0.971, SPI 0.987, 구조해석 WBS 118% → YELLOW")
    void tcCst10_golden() {
        EvmInput in = golden(9_440_000, BigDecimal.ZERO);   // 30-ANALYSIS 실적 9.44M / 예산 8M = 118%
        EvmResult r = CALC.compute(in);
        assertThat(r.bac()).isEqualByComparingTo("148000000");
        assertThat(r.pv()).isEqualByComparingTo("70100000");
        assertThat(r.ev()).isEqualByComparingTo("69200000");
        assertThat(r.ac()).isEqualByComparingTo("71300000");
        assertThat(r.cv()).isEqualByComparingTo("-2100000");
        assertThat(r.sv()).isEqualByComparingTo("-900000");
        assertThat(r.cpi()).isEqualByComparingTo("0.971");
        assertThat(r.spi()).isEqualByComparingTo("0.987");
        assertThat(r.eacMethod()).isEqualTo(EacMethod.BAC_OVER_CPI);
        // EAC = BAC × AC / EV (반올림 전 CPI) = 152,491,329.479… → 152,491,329
        assertThat(r.eac()).isEqualByComparingTo("152491329");
        assertThat(r.etc()).isEqualByComparingTo("81191329");
        assertThat(r.vac()).isEqualByComparingTo("-4491329");
        assertThat(r.tcpi()).isEqualByComparingTo("1.027");   // 설계 B4 예시 응답과 동일

        Assessment a = CALC.assess(r, in, AlertPolicy.defaults());
        assertThat(a.level()).isEqualTo(AlertLevel.YELLOW);
        assertThat(a.alerts()).extracting(EvmAlert::code).containsExactly("WBS_OVERRUN");
        assertThat(a.alerts().get(0).wbs()).isEqualTo("30-ANALYSIS");
        assertThat(a.alerts().get(0).message()).contains("118%").contains("18% 초과");
        assertThat(a.alerts().get(0).requiresComment()).isTrue();
    }

    @Test
    @DisplayName("골든: 같은 지수라도 WBS 초과가 없으면 GREEN (CPI 0.971 ≥ 0.95, SPI 0.987 ≥ 0.90, EAC ≤ 계약 90%)")
    void goldenGreenWithoutWbsOverrun() {
        EvmInput in = golden(3_000_000, BigDecimal.ZERO);
        Assessment a = CALC.assess(CALC.compute(in), in, AlertPolicy.defaults());
        assertThat(a.level()).isEqualTo(AlertLevel.GREEN);
        assertThat(a.alerts()).isEmpty();
    }

    @Test
    @DisplayName("골든: 3주 CPI 기울기 < -0.02 → EAC = AC + (BAC − EV)/(CPI×SPI)")
    void eacCompositeWhenTrendDeteriorates() {
        EvmResult r = CALC.compute(golden(3_000_000, bd("-0.03")));
        assertThat(r.eacMethod()).isEqualTo(EacMethod.CPI_SPI_COMPOSITE);
        // 71.3M + 78.8M / (69.2/71.3 × 69.2/70.1) = 153,547,286.077… → 153,547,286
        assertThat(r.eac()).isEqualByComparingTo("153547286");
    }

    @Test
    @DisplayName("골든: 진척 20% 미만 → EAC = AC + (BAC − EV), 잔여는 계획대로")
    void eacEarlyStage() {
        var in = new EvmInput(AS_OF, bd(100_000_000), bd(120_000_000),
                List.of(new PlannedLine(bd(100_000_000), START, START.plusDays(100), Distribution.LINEAR)),
                List.of(new WbsProgress("10-FIELD", bd(100_000_000), bd(10), bd(15_000_000))), bd(15_000_000),
                BigDecimal.ZERO);
        EvmResult r = CALC.compute(in);
        assertThat(r.eacMethod()).isEqualTo(EacMethod.EARLY_REMAINING_AS_PLANNED);
        assertThat(r.eac()).isEqualByComparingTo("105000000");
        assertThat(r.cpi()).isEqualByComparingTo("0.667");
        assertThat(r.spi()).isEqualByComparingTo("0.200");   // EV 10M / PV 50M
        Assessment a = CALC.assess(r, in, AlertPolicy.defaults());
        assertThat(a.level()).isEqualTo(AlertLevel.RED);
        assertThat(a.alerts()).extracting(EvmAlert::code).contains("CPI_LOW", "SPI_LOW");
    }

    @Test
    @DisplayName("경계: CPI 정확히 0.950 은 YELLOW 아님(엄격 미만), 0.949 는 YELLOW / EAC 계약 초과 RED")
    void boundaries() {
        // EV 95M, AC 100M → CPI 0.950
        var in = new EvmInput(AS_OF, bd(200_000_000), bd(500_000_000), List.of(),
                List.of(new WbsProgress("A", bd(200_000_000), bd("47.5"), BigDecimal.ZERO)), bd(100_000_000),
                BigDecimal.ZERO);
        EvmResult r = CALC.compute(in);
        assertThat(r.cpi()).isEqualByComparingTo("0.950");
        assertThat(r.spi()).isEqualByComparingTo("1.000");   // PV 0 → SPI 1
        assertThat(CALC.assess(r, in, AlertPolicy.defaults()).level()).isEqualTo(AlertLevel.GREEN);

        var in2 = new EvmInput(AS_OF, bd(200_000_000), bd(500_000_000), List.of(),
                List.of(new WbsProgress("A", bd(200_000_000), bd("47.45"), BigDecimal.ZERO)), bd(100_000_000),
                BigDecimal.ZERO);
        EvmResult r2 = CALC.compute(in2);
        assertThat(r2.cpi()).isEqualByComparingTo("0.949");
        assertThat(CALC.assess(r2, in2, AlertPolicy.defaults()).alerts()).extracting(EvmAlert::code)
                .containsExactly("CPI_LOW");

        var in3 = new EvmInput(AS_OF, bd(200_000_000), bd(205_000_000), List.of(),
                List.of(new WbsProgress("A", bd(200_000_000), bd(50), BigDecimal.ZERO)), bd(100_000_000),
                BigDecimal.ZERO);
        EvmResult r3 = CALC.compute(in3);   // CPI 1.0, EAC 200M > 205M×0.9
        assertThat(CALC.assess(r3, in3, AlertPolicy.defaults()).alerts()).extracting(EvmAlert::code)
                .containsExactly("EAC_NEAR_CONTRACT");
    }

    @Test
    @DisplayName("TCPI: BAC = AC 이면 null, AC = 0 이면 CPI 1 / 소진율−진척률 20%p 초과 경보")
    void tcpiUndefinedAndBurnGap() {
        var in = new EvmInput(AS_OF, bd(10_000_000), BigDecimal.ZERO, List.of(),
                List.of(new WbsProgress("A", bd(10_000_000), bd(30), BigDecimal.ZERO)), bd(10_000_000), BigDecimal.ZERO);
        EvmResult r = CALC.compute(in);
        assertThat(r.tcpi()).isNull();
        Assessment a = CALC.assess(r, in, AlertPolicy.defaults());
        assertThat(a.alerts()).extracting(EvmAlert::code).contains("BURN_PROGRESS_GAP", "CPI_LOW");

        var zero = new EvmInput(AS_OF, bd(10_000_000), BigDecimal.ZERO, List.of(), List.of(), BigDecimal.ZERO,
                BigDecimal.ZERO);
        assertThat(CALC.compute(zero).cpi()).isEqualByComparingTo("1.000");
    }

    @ParameterizedTest(name = "{0} t={1}/{2}일 → {3}")
    @CsvSource({
            "LINEAR, 0, 100, 0",
            "LINEAR, 25, 100, 0.25",
            "LINEAR, 100, 100, 1",
            "LINEAR, 150, 100, 1",
            "FRONT_LOADED, 50, 100, 0.75",
            "FRONT_LOADED, 25, 100, 0.4375",
            "FRONT_LOADED, 100, 100, 1"})
    @DisplayName("B7.1 PV 분포 누적함수 골든")
    void distributionGolden(Distribution d, int elapsed, int span, String expected) {
        assertThat(d.cdf(START, START.plusDays(span), START.plusDays(elapsed))).isEqualByComparingTo(expected);
        assertThat(d.cdf(START, START.plusDays(span), START.minusDays(1))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("3주 CPI 기울기(주당): 1.00 → 0.97 → 0.94 (7일 간격) = −0.03/주, 1점이면 0")
    void slope() {
        assertThat(EvmCalculator.slopePerWeek(List.of(0L, 7L, 14L), List.of(bd("1.00"), bd("0.97"), bd("0.94"))))
                .isEqualByComparingTo("-0.03");
        assertThat(EvmCalculator.slopePerWeek(List.of(0L), List.of(bd("1.00")))).isEqualByComparingTo("0");
    }
}
