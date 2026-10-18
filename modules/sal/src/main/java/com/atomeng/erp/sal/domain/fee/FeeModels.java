package com.atomeng.erp.sal.domain.fee;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** 대가 산정 입력 · 출력 모델 (설계 B4.1 FeeCalcRequest / FeeCalcResult) */
public final class FeeModels {
    private FeeModels() {}

    /**
     * 산정 시점 시설물 규모(BR-SAL-002 스냅샷). FAC 조회 API 가 아직 없어 요청에서 받는다(⚠️ fac::api 조회 포트 공개 후 자동 채움).
     * years = 기준일 연도 − 준공연도.
     */
    public record FacilityProfile(String facilityClass, BigDecimal grossAreaM2, String structure, Integer basementFloors,
                                  Integer floors, Integer completionYear, Boolean inUse) {

        public Object variable(String name, int asOfYear) {
            return switch (name) {
                case "years" -> completionYear == null ? null : asOfYear - completionYear;
                case "structure" -> structure;
                case "basementFloors" -> basementFloors;
                case "inUse" -> inUse;
                case "grossAreaM2" -> grossAreaM2;
                case "floors" -> floors;
                default -> null;
            };
        }
    }

    public record Expense(String item, BigDecimal quantity, BigDecimal unitPrice) {}

    public record ManDayOverride(String workItem, BigDecimal adjFactor, String reason) {}

    public record FeeCalcCommand(LocalDate asOfDate, String serviceType, BigDecimal overheadRate, BigDecimal techFeeRate,
                                 List<Expense> expenses, List<ManDayOverride> manDayOverrides) {
        public FeeCalcCommand {
            expenses = expenses == null ? List.of() : List.copyOf(expenses);
            manDayOverrides = manDayOverrides == null ? List.of() : List.copyOf(manDayOverrides);
        }
    }

    /** 산출내역 1줄 — LABOR(공종 × 등급) / EXPENSE(경비 항목) */
    public record FeeLine(String lineType, String workItem, String grade, BigDecimal baseManDays, BigDecimal adjFactor,
                          BigDecimal manDays, BigDecimal unitPrice, BigDecimal quantity, BigDecimal amount) {}

    public record FeeCalcResult(UUID ruleSetId, String ruleVersion, String legalBasis, List<FeeLine> lines,
                                BigDecimal directLabor, BigDecimal directExpense, BigDecimal overheadRate,
                                BigDecimal overhead, BigDecimal techFeeRate, BigDecimal techFee, BigDecimal subtotal,
                                BigDecimal vat, List<String> explanations, List<String> warnings,
                                FeeRuleSet.RateRange overheadRange, FeeRuleSet.RateRange techFeeRange) {}
}
