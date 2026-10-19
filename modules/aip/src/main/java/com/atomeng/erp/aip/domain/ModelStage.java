package com.atomeng.erp.aip.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 모델 버전 단계 (설계 A6.3). 배포 별칭(shadow · canary · production)과 연결된다.
 * <pre>
 * TRAINED → EVALUATED(골든셋 평가) → REJECTED(게이트 실패) | SHADOW(게이트 통과 · QA 승인)
 * SHADOW → CANARY(3일 비교 OK) → PRODUCTION(7일 OK · EXEC 승인) | ROLLED_BACK(자동/수동 중단)
 * PRODUCTION → ARCHIVED(후속 버전 승격)
 * </pre>
 */
public enum ModelStage {
    TRAINED, EVALUATED, REJECTED, SHADOW, CANARY, PRODUCTION, ROLLED_BACK, ARCHIVED;

    private static final Map<ModelStage, Set<ModelStage>> NEXT = Map.of(
            TRAINED, EnumSet.of(EVALUATED),
            EVALUATED, EnumSet.of(REJECTED, SHADOW),
            SHADOW, EnumSet.of(CANARY, ROLLED_BACK),
            CANARY, EnumSet.of(PRODUCTION, ROLLED_BACK),
            PRODUCTION, EnumSet.of(ARCHIVED),
            REJECTED, EnumSet.noneOf(ModelStage.class),
            ROLLED_BACK, EnumSet.noneOf(ModelStage.class),
            ARCHIVED, EnumSet.noneOf(ModelStage.class));

    public boolean canMove(ModelStage to) {
        return NEXT.get(this).contains(to);
    }

    /** 단계 → 배포 별칭(SHADOW · CANARY · PRODUCTION 만), 그 밖은 null */
    public String alias() {
        return switch (this) {
            case SHADOW -> "shadow";
            case CANARY -> "canary";
            case PRODUCTION -> "production";
            default -> null;
        };
    }

    /** 승격 승인 요건: SHADOW 진입 = QA 품질 승인, PRODUCTION 진입 = EXEC 최종 승인(설계 A2) */
    public String requiredApprover() {
        return switch (this) {
            case SHADOW -> "QA";
            case PRODUCTION -> "EXEC";
            default -> null;
        };
    }
}
