package com.atomeng.erp.cst.domain.budget;

import java.math.BigDecimal;
import java.util.Set;

/**
 * 실행예산 결재 판정(순수 도메인, BR-CST-001 일부). 결재 가능한 상태는 DRAFT · SUBMITTED · REVIEWED,
 * 라인이 하나 이상이고 BAC 가 0 보다 커야 한다. 승인되면 같은 프로젝트의 이전 APPROVED 판은 SUPERSEDED(Baseline 1개).
 */
public final class BudgetApproval {

    public static final Set<String> APPROVABLE = Set.of("DRAFT", "SUBMITTED", "REVIEWED");

    public enum Rejection { NOT_APPROVABLE_STATUS, NO_LINES, ZERO_BAC }

    private BudgetApproval() {}

    /** @return 거부 사유, 결재 가능하면 null */
    public static Rejection check(String status, int lineCount, BigDecimal bacKrw) {
        if (status == null || !APPROVABLE.contains(status)) {
            return Rejection.NOT_APPROVABLE_STATUS;
        }
        if (lineCount <= 0) {
            return Rejection.NO_LINES;
        }
        if (bacKrw == null || bacKrw.signum() <= 0) {
            return Rejection.ZERO_BAC;
        }
        return null;
    }
}
