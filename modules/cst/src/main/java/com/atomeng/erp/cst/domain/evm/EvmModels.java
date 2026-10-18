package com.atomeng.erp.cst.domain.evm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** EVM 입력·출력·경보 모델 (설계 B7.2, FR-CST-010·011). 모든 금액은 KRW BigDecimal */
public final class EvmModels {

    private EvmModels() {}

    /** 실행예산 라인의 PV 분포. start/end 가 없으면 PV 에 기여하지 않는다(일정 미정 라인) */
    public record PlannedLine(BigDecimal amount, LocalDate plannedStart, LocalDate plannedEnd, Distribution distribution) {
        public PlannedLine {
            Objects.requireNonNull(amount, "amount");
            distribution = distribution == null ? Distribution.LINEAR : distribution;
        }
    }

    /** WBS 단위: 예산(라인 합) · 최신 진척률(%) · 실적원가(WBS 지정 원장 합, 미지정은 0) */
    public record WbsProgress(String wbsCode, BigDecimal budget, BigDecimal pctComplete, BigDecimal actualCost) {
        public WbsProgress {
            Objects.requireNonNull(budget, "budget");
            pctComplete = pctComplete == null ? BigDecimal.ZERO : pctComplete;
            actualCost = actualCost == null ? BigDecimal.ZERO : actualCost;
        }
    }

    /**
     * @param cpiSlope3w 최근 3주 CPI 기울기(주당). 정보가 없으면 0
     */
    public record EvmInput(LocalDate asOf, BigDecimal bac, BigDecimal contractAmount, List<PlannedLine> lines,
                           List<WbsProgress> wbs, BigDecimal actualCost, BigDecimal cpiSlope3w) {
        public EvmInput {
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(bac, "bac");
            Objects.requireNonNull(actualCost, "actualCost");
            contractAmount = contractAmount == null ? BigDecimal.ZERO : contractAmount;
            lines = List.copyOf(lines);
            wbs = List.copyOf(wbs);
            cpiSlope3w = cpiSlope3w == null ? BigDecimal.ZERO : cpiSlope3w;
        }
    }

    public enum EacMethod { EARLY_REMAINING_AS_PLANNED, CPI_SPI_COMPOSITE, BAC_OVER_CPI }

    /** tcpi 는 BAC = AC 일 때 정의되지 않아 null */
    public record EvmResult(BigDecimal bac, BigDecimal pv, BigDecimal ev, BigDecimal ac, BigDecimal cv, BigDecimal sv,
                            BigDecimal cpi, BigDecimal spi, BigDecimal eac, BigDecimal etc, BigDecimal vac,
                            BigDecimal tcpi, EacMethod eacMethod) {}

    public enum AlertLevel {
        GREEN, YELLOW, RED;

        public AlertLevel max(AlertLevel o) {
            return o.ordinal() > ordinal() ? o : this;
        }
    }

    public record EvmAlert(String code, AlertLevel level, String wbs, String message, boolean requiresComment) {}

    public record Assessment(AlertLevel level, List<EvmAlert> alerts) {}

    /**
     * 경보 정책 (설계 B7.2 · 08-IMPL Step 11 기본값). burnGapPctPoint = 예산 소진율 - 진척률 허용 차(설계 A3.2-8, 20%p).
     * wbsOverrunRatio = WBS 실적/예산 초과 비율(1.0 = 예산 초과 즉시 경보).
     */
    public record AlertPolicy(BigDecimal redCpi, BigDecimal redSpi, BigDecimal yellowCpi, BigDecimal yellowSpi,
                              BigDecimal yellowEacRatio, BigDecimal burnGapPctPoint, BigDecimal wbsOverrunRatio) {
        public static AlertPolicy defaults() {
            return new AlertPolicy(new BigDecimal("0.85"), new BigDecimal("0.80"), new BigDecimal("0.95"),
                    new BigDecimal("0.90"), new BigDecimal("0.90"), new BigDecimal("20"), BigDecimal.ONE);
        }
    }
}
