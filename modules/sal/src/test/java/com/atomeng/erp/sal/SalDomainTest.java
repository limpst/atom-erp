package com.atomeng.erp.sal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.sal.domain.DiscountPolicy;
import com.atomeng.erp.sal.domain.EstimateStatus;
import com.atomeng.erp.sal.domain.OpportunityStage;
import com.atomeng.erp.sal.domain.qual.QualificationScorer;
import com.atomeng.erp.sal.domain.qual.QualificationScorer.CompanySnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 01-IMPL Step 2 상태 전이표 · BR-SAL-006 할인 정책 · Step 12 적격심사 점수(수기 계산 대조, ⚠️ 예시 기준) */
class SalDomainTest {

    /** 설계 A6.1 mermaid 에서 옮긴 간선 15개 */
    static final Set<String> A61 = Set.of("LEAD->QUALIFYING", "LEAD->DROPPED", "QUALIFYING->PROPOSING",
            "QUALIFYING->BIDDING", "QUALIFYING->DROPPED", "BIDDING->BID_APPROVED", "BIDDING->DROPPED",
            "BID_APPROVED->SUBMITTED", "SUBMITTED->WON", "SUBMITTED->LOST", "PROPOSING->NEGOTIATING",
            "NEGOTIATING->PROPOSING", "NEGOTIATING->WON", "NEGOTIATING->LOST", "WON->CONTRACTED");

    /** 설계 A6.2 간선 + 구현 보완(새 버전 생성 시 DRAFT · PENDING · ISSUED 도 SUPERSEDED) */
    static final Set<String> A62 = Set.of("DRAFT->PENDING_APPROVAL", "DRAFT->ISSUED", "PENDING_APPROVAL->ISSUED",
            "PENDING_APPROVAL->DRAFT", "ISSUED->SENT", "SENT->ACCEPTED", "SENT->SUPERSEDED", "SENT->REJECTED",
            "SENT->EXPIRED", "DRAFT->SUPERSEDED", "PENDING_APPROVAL->SUPERSEDED", "ISSUED->SUPERSEDED");

    @ParameterizedTest
    @EnumSource(OpportunityStage.class)
    @DisplayName("Step 2 DoD: 기회 전이표 11×11 = 121 조합이 설계 A6.1 과 정확히 같다")
    void opportunityTransitions(OpportunityStage from) {
        for (OpportunityStage to : OpportunityStage.values()) {
            assertThat(from.canMoveTo(to)).as(from + "->" + to).isEqualTo(A61.contains(from + "->" + to));
        }
        assertThat(from.isTerminal()).isEqualTo(Set.of(OpportunityStage.LOST, OpportunityStage.DROPPED,
                OpportunityStage.CONTRACTED).contains(from));
    }

    @ParameterizedTest
    @EnumSource(EstimateStatus.class)
    @DisplayName("견적 전이표 8×8 = 64 조합 (A6.2 + SUPERSEDED 보완), ISSUED 이후는 frozen")
    void estimateTransitions(EstimateStatus from) {
        for (EstimateStatus to : EstimateStatus.values()) {
            assertThat(from.canMoveTo(to)).as(from + "->" + to).isEqualTo(A62.contains(from + "->" + to));
        }
        assertThat(from.isFrozen()).isEqualTo(from != EstimateStatus.DRAFT && from != EstimateStatus.PENDING_APPROVAL);
    }

    static final DiscountPolicy POLICY = new DiscountPolicy(Map.of("SALES", new BigDecimal("0.05"),
            "SALES_LEAD", new BigDecimal("0.10"), "EXEC", new BigDecimal("0.49")));

    @Test
    @DisplayName("BR-SAL-006: SALES 5% · SALES_LEAD 10% · EXEC 49%, 여러 역할이면 큰 한도, 한도 초과만 승인 필요")
    void discountLimits() {
        assertThat(POLICY.limitFor(Set.of("SALES"))).isEqualByComparingTo("0.05");
        assertThat(POLICY.limitFor(Set.of("SALES", "SALES_LEAD"))).isEqualByComparingTo("0.10");
        assertThat(POLICY.limitFor(Set.of("PM"))).isEqualByComparingTo("0");
        assertThat(POLICY.needsApproval(new BigDecimal("0.05"), Set.of("SALES"))).isFalse();
        assertThat(POLICY.needsApproval(new BigDecimal("0.07"), Set.of("SALES"))).isTrue();       // TC-SAL-013
        assertThat(POLICY.needsApproval(new BigDecimal("0.12"), Set.of("SALES", "SALES_LEAD"))).isTrue();   // TC-SAL-015
        assertThat(POLICY.needsApproval(new BigDecimal("0.49"), Set.of("EXEC"))).isFalse();
        assertThat(POLICY.execLimit()).isEqualByComparingTo("0.49");
        assertThatThrownBy(() -> DiscountPolicy.requireValidRate(new BigDecimal("0.5")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-006");
        assertThatThrownBy(() -> DiscountPolicy.requireValidRate(new BigDecimal("-0.01")))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-006");
        assertThatThrownBy(() -> new DiscountPolicy(Map.of("EXEC", new BigDecimal("0.5"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static JsonNode criteria() {
        return FeeEngineGoldenTest.resource("rules/qual-local-gov-v2025.json");
    }

    static final List<BigDecimal> RATES = List.of(new BigDecimal("0.8770"), new BigDecimal("0.8780"),
            new BigDecimal("0.8820"), new BigDecimal("0.8900"), new BigDecimal("0.9000"));

    @Test
    @DisplayName("TC-SAL-040(수기 대조 1): 실적 비율 0.85 → 13.2, 인력 충족 15, BBB0 13, 가점 1 = 42.2 / 투찰률별 총점 · 통과")
    void qualificationCase1() {
        var snap = new CompanySnapshot(new BigDecimal("154700000"), 12, 6, 5, "BBB0", true);   // 1.547억 / 1.82억 = 0.85
        var r = new QualificationScorer().simulate(criteria(), snap, new BigDecimal("182000000"), RATES);
        assertThat(r.capability().total()).isEqualByComparingTo("42.20");
        // 가격점수 = 55 − 2 × |r − 0.88| × 100 (⚠️ 예시 산식), 하한율 0.87745 미만은 0점 · 탈락
        assertThat(r.scenarios()).extracting(s -> s.priceScore().toPlainString(), s -> s.total().toPlainString(),
                QualificationScorer.Scenario::pass).containsExactly(
                org.assertj.core.groups.Tuple.tuple("0.00", "42.20", false),
                org.assertj.core.groups.Tuple.tuple("54.60", "96.80", true),
                org.assertj.core.groups.Tuple.tuple("54.60", "96.80", true),
                org.assertj.core.groups.Tuple.tuple("53.00", "95.20", true),
                org.assertj.core.groups.Tuple.tuple("51.00", "93.20", false));
        assertThat(r.scenarios().get(0).belowLowerLimit()).isTrue();
        assertThat(r.recommendedBidRate()).isEqualByComparingTo("0.8780");
        assertThat(r.notice()).contains("참고값");
    }

    @Test
    @DisplayName("TC-SAL-040(수기 대조 2): 실적 0, 인력 2/5(0.4 → 8), 신용 등급 없음 0, 가점 없음 = 17 → 모든 투찰률 탈락")
    void qualificationCase2() {
        var snap = new CompanySnapshot(BigDecimal.ZERO, 0, 2, 5, null, false);
        var r = new QualificationScorer().simulate(criteria(), snap, new BigDecimal("50000000"), RATES);
        assertThat(r.capability().items()).extracting(i -> i.key() + "=" + i.score().stripTrailingZeros().toPlainString())
                .containsExactly("SIMILAR_PERFORMANCE=9", "ENGINEERS=8", "CREDIT=0", "BONUS_DISABLED_STD=0");
        assertThat(r.capability().total()).isEqualByComparingTo("17");
        assertThat(r.scenarios()).noneMatch(QualificationScorer.Scenario::pass);
        assertThat(r.recommendedBidRate()).isNull();
    }

    @Test
    @DisplayName("TC-SAL-040(수기 대조 3): 실적 비율 정확히 1.0 → 15(상한), AAA 15, 인력 비율 0.5 → 12 = 42")
    void qualificationCase3() {
        var snap = new CompanySnapshot(new BigDecimal("300000000"), 3, 3, 6, "AAA", false);
        var r = new QualificationScorer().simulate(criteria(), snap, new BigDecimal("300000000"),
                List.of(new BigDecimal("0.88")));
        assertThat(r.capability().total()).isEqualByComparingTo("42");
        assertThat(r.scenarios().get(0).total()).isEqualByComparingTo("97");
        assertThatThrownBy(() -> QualificationScorer.priceScore(
                FeeEngineGoldenTest.OM.createObjectNode().put("type", "UNKNOWN"), BigDecimal.ONE))
                .extracting(e -> ((DomainException) e).code()).isEqualTo("SAL-422-020");
    }
}
