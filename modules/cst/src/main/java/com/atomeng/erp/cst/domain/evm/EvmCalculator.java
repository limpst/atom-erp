package com.atomeng.erp.cst.domain.evm;

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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * EVM 엔진 (설계 B7.2, 08-IMPL Step 11) - 순수 도메인, BigDecimal 전용(NFR-CST-02).
 * <ul>
 *   <li>PV = sum(line.amount x F(asOf)), EV = sum(wbs.budget x pct/100), AC = 원장 합(입력)</li>
 *   <li>CPI = EV/AC, SPI = EV/PV (분모 0 이면 1), 표시값은 소수 3자리 HALF_UP</li>
 *   <li>EAC: 진척(EV/BAC) 20% 미만 -> AC + (BAC - EV) / 3주 CPI 기울기 &lt; -0.02 -> AC + (BAC - EV)/(CPI x SPI)
 *       / 그 외 BAC/CPI. EAC 계산에는 반올림 전 CPI·SPI 를 쓴다(중간 반올림 누적 방지)</li>
 *   <li>TCPI = (BAC - EV)/(BAC - AC), BAC = AC 이면 정의되지 않음(null)</li>
 * </ul>
 */
public final class EvmCalculator {

    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_UP);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal EARLY = new BigDecimal("0.20");
    private static final BigDecimal SLOPE_DETERIORATING = new BigDecimal("-0.02");

    public EvmResult compute(EvmInput in) {
        BigDecimal bac = in.bac();
        BigDecimal pv = BigDecimal.ZERO;
        for (PlannedLine l : in.lines()) {
            if (l.plannedStart() == null || l.plannedEnd() == null) {
                continue;
            }
            pv = pv.add(l.amount().multiply(l.distribution().cdf(l.plannedStart(), l.plannedEnd(), in.asOf()), MC));
        }
        BigDecimal ev = BigDecimal.ZERO;
        for (WbsProgress w : in.wbs()) {
            ev = ev.add(w.budget().multiply(w.pctComplete(), MC).divide(HUNDRED, MC));
        }
        BigDecimal ac = in.actualCost();

        BigDecimal cpiRaw = ac.signum() == 0 ? BigDecimal.ONE : ev.divide(ac, MC);
        BigDecimal spiRaw = pv.signum() == 0 ? BigDecimal.ONE : ev.divide(pv, MC);
        BigDecimal pct = bac.signum() == 0 ? BigDecimal.ZERO : ev.divide(bac, MC);

        BigDecimal eac;
        EacMethod method;
        if (pct.compareTo(EARLY) < 0 || cpiRaw.signum() == 0) {
            eac = ac.add(bac.subtract(ev));
            method = EacMethod.EARLY_REMAINING_AS_PLANNED;
        } else if (in.cpiSlope3w().compareTo(SLOPE_DETERIORATING) < 0 && spiRaw.signum() != 0) {
            eac = ac.add(bac.subtract(ev).divide(cpiRaw.multiply(spiRaw, MC), MC));
            method = EacMethod.CPI_SPI_COMPOSITE;
        } else {
            eac = bac.divide(cpiRaw, MC);
            method = EacMethod.BAC_OVER_CPI;
        }
        BigDecimal tcpi = bac.compareTo(ac) == 0 ? null
                : bac.subtract(ev).divide(bac.subtract(ac), 3, RoundingMode.HALF_UP);
        BigDecimal bac0 = won(bac);
        BigDecimal pv0 = won(pv);
        BigDecimal ev0 = won(ev);
        BigDecimal ac0 = won(ac);
        BigDecimal eac0 = won(eac);
        return new EvmResult(bac0, pv0, ev0, ac0, ev0.subtract(ac0), ev0.subtract(pv0),
                index(cpiRaw), index(spiRaw), eac0, eac0.subtract(ac0), bac0.subtract(eac0), tcpi, method);
    }

    /** 경보 판정: 지수·EAC 임계(B7.2) + 소진율-진척률 격차(A3.2-8) + WBS 예산 초과(B4 예시 WBS_OVERRUN). 최고 등급 채택 */
    public Assessment assess(EvmResult r, EvmInput in, AlertPolicy p) {
        List<EvmAlert> alerts = new ArrayList<>();
        BigDecimal contract = in.contractAmount();
        if (r.cpi().compareTo(p.redCpi()) < 0) {
            alerts.add(new EvmAlert("CPI_LOW", AlertLevel.RED, null, "CPI " + r.cpi() + " < " + p.redCpi(), true));
        } else if (r.cpi().compareTo(p.yellowCpi()) < 0) {
            alerts.add(new EvmAlert("CPI_LOW", AlertLevel.YELLOW, null, "CPI " + r.cpi() + " < " + p.yellowCpi(), false));
        }
        if (r.spi().compareTo(p.redSpi()) < 0) {
            alerts.add(new EvmAlert("SPI_LOW", AlertLevel.RED, null, "SPI " + r.spi() + " < " + p.redSpi(), true));
        } else if (r.spi().compareTo(p.yellowSpi()) < 0) {
            alerts.add(new EvmAlert("SPI_LOW", AlertLevel.YELLOW, null, "SPI " + r.spi() + " < " + p.yellowSpi(), false));
        }
        if (contract.signum() > 0) {
            if (r.eac().compareTo(contract) > 0) {
                alerts.add(new EvmAlert("EAC_OVER_CONTRACT", AlertLevel.RED, null, "EAC 가 계약금액 초과", true));
            } else if (r.eac().compareTo(contract.multiply(p.yellowEacRatio(), MC)) > 0) {
                alerts.add(new EvmAlert("EAC_NEAR_CONTRACT", AlertLevel.YELLOW, null,
                        "EAC 가 계약금액의 " + p.yellowEacRatio().multiply(HUNDRED).stripTrailingZeros().toPlainString()
                                + "% 초과", false));
            }
        }
        if (r.bac().signum() > 0) {
            BigDecimal burnPct = r.ac().multiply(HUNDRED).divide(r.bac(), MC);
            BigDecimal progressPct = r.ev().multiply(HUNDRED).divide(r.bac(), MC);
            if (burnPct.subtract(progressPct).compareTo(p.burnGapPctPoint()) > 0) {
                alerts.add(new EvmAlert("BURN_PROGRESS_GAP", AlertLevel.YELLOW, null,
                        "예산 소진율이 진척률보다 " + p.burnGapPctPoint() + "%p 넘게 높음", true));
            }
        }
        for (WbsProgress w : in.wbs()) {
            if (w.budget().signum() > 0
                    && w.actualCost().compareTo(w.budget().multiply(p.wbsOverrunRatio(), MC)) > 0) {
                BigDecimal ratio = w.actualCost().multiply(HUNDRED).divide(w.budget(), 0, RoundingMode.HALF_UP);
                alerts.add(new EvmAlert("WBS_OVERRUN", AlertLevel.YELLOW, w.wbsCode(),
                        w.wbsCode() + " 예산 대비 실적 " + ratio + "% (" + ratio.subtract(HUNDRED) + "% 초과)", true));
            }
        }
        AlertLevel level = AlertLevel.GREEN;
        for (EvmAlert a : alerts) {
            level = level.max(a.level());
        }
        return new Assessment(level, List.copyOf(alerts));
    }

    /** 주당 CPI 기울기(최소제곱). days = 기준점으로부터 경과 일수, 2점 미만이면 0 */
    public static BigDecimal slopePerWeek(List<Long> days, List<BigDecimal> cpis) {
        int n = Math.min(days.size(), cpis.size());
        if (n < 2) {
            return BigDecimal.ZERO;
        }
        BigDecimal sx = BigDecimal.ZERO;
        BigDecimal sy = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            sx = sx.add(BigDecimal.valueOf(days.get(i)));
            sy = sy.add(cpis.get(i));
        }
        BigDecimal nn = BigDecimal.valueOf(n);
        BigDecimal mx = sx.divide(nn, MC);
        BigDecimal my = sy.divide(nn, MC);
        BigDecimal num = BigDecimal.ZERO;
        BigDecimal den = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal dx = BigDecimal.valueOf(days.get(i)).subtract(mx);
            num = num.add(dx.multiply(cpis.get(i).subtract(my), MC));
            den = den.add(dx.multiply(dx, MC));
        }
        if (den.signum() == 0) {
            return BigDecimal.ZERO;
        }
        return num.divide(den, MC).multiply(BigDecimal.valueOf(7)).setScale(4, RoundingMode.HALF_UP);
    }

    private static BigDecimal won(BigDecimal v) {
        return v.setScale(0, RoundingMode.HALF_UP);
    }

    private static BigDecimal index(BigDecimal v) {
        return v.setScale(3, RoundingMode.HALF_UP);
    }
}
