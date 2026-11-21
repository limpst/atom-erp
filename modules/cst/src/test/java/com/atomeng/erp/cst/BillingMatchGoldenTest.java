package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.cst.domain.billing.AgingBucket;
import com.atomeng.erp.cst.domain.billing.MatchScorer;
import com.atomeng.erp.cst.domain.billing.PartyName;
import com.atomeng.erp.cst.domain.shared.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 청구 ↔ 입금 짝 점수 · aging 구간 골든 패리티 (BR-CST-BIL-006~008).
 * 기대값은 tools/cst_billing_golden_gen.py 가 같은 규칙을 Python 으로 따로 계산한 합성 사례다(실데이터 아님).
 */
class BillingMatchGoldenTest {

    static JsonNode golden() throws Exception {
        try (InputStream in = BillingMatchGoldenTest.class.getResourceAsStream("/golden/billing/match_golden.json")) {
            assertThat(in).as("golden/billing/match_golden.json").isNotNull();
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    @DisplayName("짝 점수(금액 50 + 이름 30 + 날짜 20)가 Python 합성 정답 16건과 한 점도 다르지 않다")
    void matchScoreParity() throws Exception {
        JsonNode cases = golden().get("match");
        assertThat(cases.size()).isGreaterThanOrEqualTo(16);
        for (JsonNode c : cases) {
            long supply = c.get("supplyKrw").asLong();
            long vat = c.get("vatKrw").asLong();
            MatchScorer.Score s = MatchScorer.score(Money.krw(supply + vat), Money.krw(supply), c.get("clientName").asText(),
                    LocalDate.parse(c.get("issueDate").asText()), Money.krw(c.get("receiptKrw").asLong()),
                    c.get("payerText").asText(), LocalDate.parse(c.get("valueDate").asText()));
            JsonNode e = c.get("expected");
            String id = c.get("id").asText();
            assertThat(s.amount()).as(id + " amount").isEqualTo(e.get("amount").asInt());
            assertThat(s.name()).as(id + " name").isEqualTo(e.get("name").asInt());
            assertThat(s.date()).as(id + " date").isEqualTo(e.get("date").asInt());
            assertThat(s.total()).as(id + " total").isEqualTo(e.get("total").asInt());
            assertThat(s.autoCandidate()).as(id + " autoCandidate").isEqualTo(e.get("autoCandidate").asBoolean());
        }
    }

    @Test
    @DisplayName("aging 구간 경계(30 · 31 · 60 · 61 · 90 · 91 · 180 · 181)가 정답과 같고, 음수 경과일은 거부한다")
    void agingBucketParity() throws Exception {
        for (JsonNode c : golden().get("aging")) {
            assertThat(AgingBucket.of(c.get("ageDays").asLong()).name()).as("ageDays " + c.get("ageDays"))
                    .isEqualTo(c.get("bucket").asText());
        }
        assertThat(AgingBucket.of(91).overdue()).isTrue();
        assertThat(AgingBucket.of(90).overdue()).isFalse();
        assertThatThrownBy(() -> AgingBucket.of(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("이름 정규화: 법인 표기 · 공백 · 기호를 지우면 같은 회사는 같은 글자열이 된다")
    void partyNameNormalize() {
        assertThat(PartyName.normalize("㈜ 합성건설")).isEqualTo("합성건설");
        assertThat(PartyName.normalize("(주)합성건설")).isEqualTo(PartyName.normalize("주식회사 합성 건설"));
        assertThat(PartyName.normalize("ABC Tower-관리")).isEqualTo("abctower관리");
        assertThat(PartyName.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("발행 전 입금 · 금액 불일치는 자동 짝 후보가 되지 못한다(확정은 언제나 사람)")
    void neverAutoWithoutAmountAndDate() {
        MatchScorer.Score early = MatchScorer.score(Money.krw(1_100_000), Money.krw(1_000_000), "합성건설",
                LocalDate.parse("2026-05-10"), Money.krw(1_100_000), "합성건설", LocalDate.parse("2026-05-09"));
        assertThat(early.date()).isZero();
        assertThat(early.autoCandidate()).isFalse();
        MatchScorer.Score wrongAmount = MatchScorer.score(Money.krw(1_100_000), Money.krw(1_000_000), "합성건설",
                LocalDate.parse("2026-05-10"), Money.krw(999_999), "합성건설", LocalDate.parse("2026-05-11"));
        assertThat(wrongAmount.amount()).isZero();
        assertThat(wrongAmount.autoCandidate()).isFalse();
    }
}
