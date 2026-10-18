package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.cst.domain.ledger.LaborCost;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler.CstRow;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler.HrqRow;
import com.atomeng.erp.cst.domain.reconcile.MandayReconciler.Key;
import com.atomeng.erp.cst.domain.shared.Money;
import com.atomeng.erp.cst.domain.shared.PeriodYm;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CST 순수 도메인 골든 테스트 — Money(HALF_UP), 인건비(B7.4), 인·일 대사(TC-HRQ-010 단위). 합성 단가 */
class CstDomainTest {

    static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }

    @ParameterizedTest(name = "{0} 시간 × 일단가 {1} → 원가 {2}, 인·일 {3}")
    @CsvSource({
            "8, 300000, 300000, 1.0000",
            "6, 300000, 225000, 0.7500",
            "2.5, 333333, 104167, 0.3125",     // 104166.5625 → HALF_UP
            "0.25, 250001, 7813, 0.0313",      // 7812.53125 → 7813, 0.03125 → 0.0313
            "7.75, 287654, 278665, 0.9688"})   // 278664.8125 → 278665
    @DisplayName("B7.4: 인건비 = 시간 ÷ 8 × 일 단가, 원 단위 HALF_UP 1회 반올림")
    void laborCostGolden(String hours, String daily, String amount, String mandays) {
        LaborCost.Result r = LaborCost.compute(bd(hours), bd(daily), null);
        assertThat(r.amountStd().amount()).isEqualByComparingTo(amount);
        assertThat(r.amountReal().amount()).isEqualByComparingTo(amount);   // 실단가 없음 → 표준단가 대체
        assertThat(r.realRateFallback()).isTrue();
        assertThat(r.mandays()).isEqualByComparingTo(mandays);
        assertThat(r.amountStd().amount().scale()).isZero();
    }

    @Test
    @DisplayName("BR-CST-004: 표준·실단가 이중 계상, 실단가 있으면 플래그 없음 / 0 시간·음수 단가 거부")
    void laborDualRate() {
        LaborCost.Result r = LaborCost.compute(bd("8"), bd("300000"), bd("412345"));
        assertThat(r.amountStd().amount()).isEqualByComparingTo("300000");
        assertThat(r.amountReal().amount()).isEqualByComparingTo("412345");
        assertThat(r.realRateFallback()).isFalse();
        assertThatThrownBy(() -> LaborCost.compute(BigDecimal.ZERO, bd("1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LaborCost.compute(bd("1"), bd("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("NFR-CST-02: Money KRW scale 0 HALF_UP, 외화 scale 2, 통화 불일치 거부, 환산")
    void money() {
        assertThat(Money.krw(bd("1234.5")).amount()).isEqualByComparingTo("1235");
        assertThat(Money.krw(bd("-1234.5")).amount()).isEqualByComparingTo("-1235");
        Money usd = new Money(bd("10.005"), Currency.getInstance("USD"));
        assertThat(usd.amount()).isEqualByComparingTo("10.01");
        assertThat(usd.toKrw(bd("1388.45")).amount()).isEqualByComparingTo("13898");   // 13898.3845
        assertThatThrownBy(() -> usd.plus(Money.krw(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(Money.krw(100).minus(Money.krw(250)).isNegative()).isTrue();
        // 0.1 + 0.2 문제 없음(BigDecimal)
        assertThat(Money.krw(bd("0.1")).plus(Money.krw(bd("0.2"))).amount()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("PeriodYm: YYYYMM 파싱·다음 월·연말 넘김, 잘못된 형식 거부")
    void periodYm() {
        assertThat(PeriodYm.parse("202612").next().value()).isEqualTo("202701");
        assertThat(PeriodYm.of(LocalDate.of(2026, 9, 30)).value()).isEqualTo("202609");
        assertThatThrownBy(() -> PeriodYm.parse("202613")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PeriodYm.parse("2026-09")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TC-HRQ-010(단위): 같은 그룹 단위 round(sum(h)/8, 2) 비교 → 100% 일치 / 차이 탐지")
    void reconcile() {
        UUID p = UUID.randomUUID();
        UUID e = UUID.randomUUID();
        Key field = new Key(p, e, "SENIOR", "FIELD");
        Key office = new Key(p, e, "SENIOR", "OFFICE");
        // HRQ: FIELD 8+6h=1.75, OFFICE 3h=0.38(0.375 HALF_UP)
        List<HrqRow> hrq = List.of(new HrqRow(field, bd("1.75")), new HrqRow(office, bd("0.38")));
        // CST: FIELD +8 +8 −8(정정 역분개) +6, OFFICE +3
        List<CstRow> cst = List.of(new CstRow(field, bd("8")), new CstRow(field, bd("8")), new CstRow(field, bd("-8")),
                new CstRow(field, bd("6")), new CstRow(office, bd("3")));
        var ok = MandayReconciler.reconcile(hrq, cst);
        assertThat(ok.matched()).isTrue();
        assertThat(ok.hrqTotal()).isEqualByComparingTo("2.13");
        assertThat(ok.cstTotal()).isEqualByComparingTo("2.13");

        var bad = MandayReconciler.reconcile(hrq, List.of(new CstRow(field, bd("14"))));
        assertThat(bad.matched()).isFalse();
        assertThat(bad.diffs()).hasSize(1);
        assertThat(bad.diffs().get(0).key()).isEqualTo(office);
        assertThat(bad.diffTotal()).isEqualByComparingTo("-0.38");
        // 순증감 0 그룹(업무유형 정정으로 원 그룹이 0이 됨)은 HRQ 에 행이 없어도 일치
        var zero = MandayReconciler.reconcile(List.of(), List.of(new CstRow(office, bd("4")), new CstRow(office, bd("-4"))));
        assertThat(zero.matched()).isTrue();
    }
}
