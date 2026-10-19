package com.atomeng.erp.cst.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.cst.api.CstSubscriptionContracts.FldSurveyCompleted;
import com.atomeng.erp.cst.domain.budget.BudgetApproval;
import com.atomeng.erp.cst.domain.budget.BudgetApproval.Rejection;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 전체 업무 흐름 시험에서 드러난 CST 연결 누락 회귀 — 예산 초안 마일스톤 · 실행예산 결재 판정 */
class BudgetGlueTest {

    @Test
    @DisplayName("예산 초안: 현장조사(FIELD) WBS 마일스톤 = fld.survey.completed(구독 계약 TYPE 과 같은 문자열), 다른 공종은 없음")
    void fieldWbsMilestoneMatchesSubscription() {
        assertThat(BudgetDraftService.MILESTONE.get("FIELD")).isEqualTo(FldSurveyCompleted.TYPE);
        for (String other : new String[] {"DATA", "TEST", "ANALYSIS", "REPORT"}) {
            assertThat(BudgetDraftService.MILESTONE.get(other)).isNull();
        }
    }

    @Test
    @DisplayName("결재 판정: DRAFT · SUBMITTED · REVIEWED 만 · 라인 1개 이상 · BAC > 0, APPROVED · SUPERSEDED · CLOSED 재결재 거부")
    void approvalPolicy() {
        for (String s : new String[] {"DRAFT", "SUBMITTED", "REVIEWED"}) {
            assertThat(BudgetApproval.check(s, 3, new BigDecimal("1000"))).isNull();
        }
        for (String s : new String[] {"APPROVED", "SUPERSEDED", "CLOSED", null}) {
            assertThat(BudgetApproval.check(s, 3, new BigDecimal("1000"))).isEqualTo(Rejection.NOT_APPROVABLE_STATUS);
        }
        assertThat(BudgetApproval.check("DRAFT", 0, new BigDecimal("1000"))).isEqualTo(Rejection.NO_LINES);
        assertThat(BudgetApproval.check("DRAFT", 2, BigDecimal.ZERO)).isEqualTo(Rejection.ZERO_BAC);
        assertThat(BudgetApproval.check("DRAFT", 2, null)).isEqualTo(Rejection.ZERO_BAC);
    }
}
