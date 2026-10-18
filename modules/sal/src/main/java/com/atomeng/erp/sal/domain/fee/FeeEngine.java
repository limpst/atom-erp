package com.atomeng.erp.sal.domain.fee;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.domain.SalCodes;
import com.atomeng.erp.sal.domain.fee.FeeModels.FacilityProfile;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcCommand;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcResult;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeLine;
import com.atomeng.erp.sal.domain.fee.FeeModels.ManDayOverride;
import com.atomeng.erp.sal.domain.fee.FeeRuleSet.AdjustmentFactor;
import com.atomeng.erp.sal.domain.fee.FeeRuleSet.AreaBand;
import com.atomeng.erp.sal.domain.fee.FeeRuleSet.ServiceBand;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 대가 산정 엔진 — 실비정액가산식 (설계 B7.1, 01-IMPL Step 5). 순수 도메인(프레임워크 의존 0), 금액은 BigDecimal 만.
 * <ol>
 *   <li>기준 인·일 구간: [fromM2, toM2) — 구간 밖은 보간 · 외삽 금지 → SAL-422-005 수동 산정 필요</li>
 *   <li>보정계수: 조건이 맞는 계수의 곱. 공종 override 가 있으면 그 공종은 override 값만</li>
 *   <li>등급 인·일 = (기준 인·일 × 보정계수 × 등급 배분) 소수 2자리 HALF_UP, 라인 금액 = 노임단가 × 인·일 → 라인 절사</li>
 *   <li>제경비 = 직접인건비 × 제경비율, 기술료 = (직접인건비 + 제경비) × 기술료율 (요율은 규칙 min–max 이내, BR-SAL-004)</li>
 *   <li>합계 = 합계 절사(기본 천 원 미만, BR-SAL-005), VAT = 합계 × 10% 원 미만 절사</li>
 * </ol>
 * 할인은 엔진 밖(견적)에서 적용한다 — 엔진은 "법정 기준가"만 낸다(01-IMPL Step 5 팁).
 */
public final class FeeEngine {

    private static final BigDecimal VAT_RATE = new BigDecimal("0.1");

    public FeeCalcResult calculate(FeeCalcCommand cmd, FeeRuleSet rule, FacilityProfile fac) {
        if (fac == null || fac.grossAreaM2() == null || fac.facilityClass() == null) {
            throw new DomainException(SalCodes.FACILITY_PROFILE, "시설물 프로필(시설 분류 · 연면적)이 필요합니다");
        }
        int asOfYear = cmd.asOfDate().getYear();
        ServiceBand svc = rule.service(cmd.serviceType(), fac.facilityClass())
                .orElseThrow(() -> new DomainException(SalCodes.MANUAL_ESTIMATION_REQUIRED,
                        "규칙에 없는 용역 · 시설 분류: %s / %s — 수동 산정 필요".formatted(cmd.serviceType(),
                                fac.facilityClass())));
        AreaBand band = svc.findBand(fac.grossAreaM2())
                .orElseThrow(() -> new DomainException(SalCodes.MANUAL_ESTIMATION_REQUIRED,
                        "연면적 %s㎡ 는 규칙 구간 밖입니다 — 수동 산정 필요(보간 · 외삽 금지)"
                                .formatted(fac.grossAreaM2().toPlainString()))
                        .with("grossAreaM2", fac.grossAreaM2()));

        // 요율 범위 검증을 금액 계산보다 먼저(BR-SAL-004)
        BigDecimal overheadRate = rule.overheadRate().requireWithin(cmd.overheadRate(), "제경비율");
        BigDecimal techFeeRate = rule.techFeeRate().requireWithin(cmd.techFeeRate(), "기술료율");

        List<String> explanations = new ArrayList<>();
        BigDecimal adj = BigDecimal.ONE;
        for (AdjustmentFactor f : rule.adjustmentFactors()) {
            boolean on = f.matches(fac, asOfYear);
            if (on) {
                adj = adj.multiply(f.factor());
            }
            explanations.add("%s 보정 %s %s (%s)".formatted(f.key(), f.factor().toPlainString(), on ? "적용" : "미적용",
                    describe(f, fac, asOfYear)));
        }
        List<String> warnings = new ArrayList<>();
        warnings.add("연면적 %s㎡ → 규칙 구간 %s–%s㎡ 적용".formatted(fac.grossAreaM2().toPlainString(),
                band.fromM2().toPlainString(), band.toM2().toPlainString()));

        FeeRuleSet.Rounding lineR = rule.lineRounding();
        List<FeeLine> lines = new ArrayList<>();
        BigDecimal directLabor = BigDecimal.ZERO;
        for (Map.Entry<String, BigDecimal> wi : band.workItems().entrySet()) {
            BigDecimal itemAdj = adj;
            for (ManDayOverride o : cmd.manDayOverrides()) {
                if (o.workItem().equals(wi.getKey())) {
                    itemAdj = o.adjFactor();
                    explanations.add("%s 공종 보정계수 %s 수동 적용 (%s)".formatted(wi.getKey(),
                            o.adjFactor().toPlainString(), o.reason() == null ? "사유 없음" : o.reason()));
                }
            }
            BigDecimal md = wi.getValue().multiply(itemAdj);
            for (Map.Entry<String, BigDecimal> g : svc.gradeMix().entrySet()) {
                BigDecimal gradeMd = md.multiply(g.getValue()).setScale(2, RoundingMode.HALF_UP);
                BigDecimal unit = rule.laborUnitPrice(g.getKey());
                BigDecimal amount = lineR.apply(unit.multiply(gradeMd));
                directLabor = directLabor.add(amount);
                lines.add(new FeeLine("LABOR", wi.getKey(), g.getKey(), wi.getValue(), itemAdj, gradeMd, unit, null,
                        amount));
            }
        }
        BigDecimal directExpense = BigDecimal.ZERO;
        for (FeeModels.Expense e : cmd.expenses()) {
            if (e.quantity() == null || e.unitPrice() == null || e.quantity().signum() < 0 || e.unitPrice().signum() < 0) {
                throw new DomainException(SalCodes.INVALID, "경비 수량 · 단가는 0 이상이어야 합니다: " + e.item());
            }
            BigDecimal amount = lineR.apply(e.unitPrice().multiply(e.quantity()));
            directExpense = directExpense.add(amount);
            lines.add(new FeeLine("EXPENSE", e.item(), null, null, null, null, e.unitPrice(), e.quantity(), amount));
        }
        BigDecimal overhead = lineR.apply(directLabor.multiply(overheadRate));
        BigDecimal techFee = lineR.apply(directLabor.add(overhead).multiply(techFeeRate));
        BigDecimal subtotal = rule.totalRounding().apply(directLabor.add(directExpense).add(overhead).add(techFee));
        BigDecimal vat = vatOf(subtotal);
        return new FeeCalcResult(rule.id(), rule.ruleVersion(), rule.legalBasis(), List.copyOf(lines), directLabor,
                directExpense, overheadRate, overhead, techFeeRate, techFee, subtotal, vat, List.copyOf(explanations),
                List.copyOf(warnings), rule.overheadRate(), rule.techFeeRate());
    }

    /** VAT 10%, 원 미만 절사 */
    public static BigDecimal vatOf(BigDecimal amount) {
        return amount.multiply(VAT_RATE).setScale(0, RoundingMode.FLOOR);
    }

    private static String describe(AdjustmentFactor f, FacilityProfile fac, int asOfYear) {
        String var = f.when().split(" ")[0];
        Object v = fac.variable(var, asOfYear);
        return "%s=%s, 조건 %s".formatted(var, v == null ? "미입력" : v, f.when());
    }
}
