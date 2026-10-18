package com.atomeng.erp.fac.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** FAC 값 타입 (설계 02 B3 CHECK 제약과 동일) */
public final class FacTypes {
    private FacTypes() {}

    public enum BuildingStatus { DRAFT, VERIFIED, CLASSIFIED, ACTIVE, RECLASSIFY_REQUIRED, MERGED, DEMOLISHED, INACTIVE }

    public enum DataSource { REGISTER, MANUAL, MIGRATION }

    public enum Grade {
        A, B, C, D, E;

        /** BR-FAC-011: D 이하 즉시 알림·사용제한 권고 */
        public boolean isCritical() {
            return this == D || this == E;
        }
    }

    public enum InspectionType {
        REGULAR, DETAILED, PRECISION_DIAG, EMERGENCY, BMA_CHECK;

        /** 주기 계산 대상 (긴급점검 제외) */
        public static Set<InspectionType> periodic() {
            return EnumSet.of(REGULAR, DETAILED, PRECISION_DIAG, BMA_CHECK);
        }
    }

    /** 시특법 종별 */
    public enum SitbeopClass { CLASS1, CLASS2, CLASS3, NONE }

    public enum DueStatus {
        SCHEDULED, APPROACHING_180, APPROACHING_90, APPROACHING_30, OVERDUE, CONTRACTED, DONE, LOST, CANCELLED;

        public static DueStatus approaching(int threshold) {
            return switch (threshold) {
                case 180 -> APPROACHING_180;
                case 90 -> APPROACHING_90;
                case 30 -> APPROACHING_30;
                default -> throw new IllegalArgumentException("threshold " + threshold);
            };
        }

        /** 진행 순서(퇴행 방지) */
        public int rank() {
            return switch (this) {
                case SCHEDULED -> 0;
                case APPROACHING_180 -> 1;
                case APPROACHING_90 -> 2;
                case APPROACHING_30 -> 3;
                case OVERDUE -> 4;
                default -> 9;
            };
        }
    }
}
