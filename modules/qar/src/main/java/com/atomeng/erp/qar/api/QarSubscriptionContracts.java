package com.atomeng.erp.qar.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * QAR 구독 계약 — QAR 이 읽는 필드를 QAR 쪽에 고정한다. 생산자 fld::api FldEvents.AiSuggestionDiscrepancy 와 필드가 같아야 한다
 * (FldContractTest JSON 왕복).
 */
public final class QarSubscriptionContracts {

    private QarSubscriptionContracts() {}

    /** fld.ai-suggestion.discrepancy (설계 05 v0.2 B6.2 · A8-8) — AI 균열폭 ↔ 사람 측정 2배 이상 → 검토 시 강조(샘플링) */
    public record FldAiDiscrepancy(UUID suggestionId, UUID defectId, UUID roundId, UUID projectId, String defectCode,
                                   BigDecimal humanWidthMm, BigDecimal aiWidthMm, BigDecimal ratio) {
        public static final String TYPE = "fld.ai-suggestion.discrepancy";
    }
}
