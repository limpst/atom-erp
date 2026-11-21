package com.atomeng.erp.cst.domain.billing;

import com.atomeng.erp.cst.domain.shared.Money;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

/**
 * 입금 ↔ 청구 짝 점수 (BR-CST-BIL-008, 순수 도메인). 점수 = 금액 50 + 이름 30 + 날짜 20 (0~100).
 * <ul>
 *   <li>금액: 입금액 = 청구 합계(VAT 포함) 50 · 입금액 = 공급가액(VAT 별도 입금) 35 · 그 밖 0</li>
 *   <li>이름: 정규화한 거래처명과 통장 거래내용의 bigram Dice 유사도 × 30 (HALF_UP). 한쪽이 다른 쪽을 포함하면 30</li>
 *   <li>날짜: 입금일 − 발행일. 0~30일 20 · 31~90일 10 · 91~180일 5 · 그 밖(발행 전 입금 포함) 0</li>
 * </ul>
 * 90점 이상만 자동 짝 후보이고, 확정은 언제나 사람(제안자와 다른 사람)이 한다.
 * 같은 규칙을 Python 으로 따로 구현한 합성 정답(tools/cst_billing_golden_gen.py)과 값이 같아야 한다.
 */
public final class MatchScorer {
    private MatchScorer() {}

    public static final int AUTO_CANDIDATE_MIN = 90;

    public record Score(int amount, int name, int date) {
        public int total() {
            return amount + name + date;
        }

        public boolean autoCandidate() {
            return total() >= AUTO_CANDIDATE_MIN;
        }
    }

    public static Score score(Money invoiceTotal, Money invoiceSupply, String clientName, LocalDate issueDate,
                              Money receiptAmount, String payerText, LocalDate valueDate) {
        return new Score(amountScore(invoiceTotal, invoiceSupply, receiptAmount), nameScore(clientName, payerText),
                dateScore(issueDate, valueDate));
    }

    static int amountScore(Money total, Money supply, Money receipt) {
        if (receipt.compareTo(total) == 0) {
            return 50;
        }
        return receipt.compareTo(supply) == 0 ? 35 : 0;
    }

    static int dateScore(LocalDate issueDate, LocalDate valueDate) {
        long lag = ChronoUnit.DAYS.between(issueDate, valueDate);
        if (lag < 0) {
            return 0;
        }
        if (lag <= 30) {
            return 20;
        }
        if (lag <= 90) {
            return 10;
        }
        return lag <= 180 ? 5 : 0;
    }

    static int nameScore(String clientName, String payerText) {
        String a = PartyName.normalize(clientName);
        String b = PartyName.normalize(payerText);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = a.length() <= b.length() ? b : a;
        if (shorter.length() >= 2 && longer.contains(shorter)) {
            return 30;
        }
        Map<String, Integer> ga = bigrams(a);
        Map<String, Integer> gb = bigrams(b);
        int inter = 0;
        for (Map.Entry<String, Integer> e : ga.entrySet()) {
            inter += Math.min(e.getValue(), gb.getOrDefault(e.getKey(), 0));
        }
        int sizes = size(ga) + size(gb);
        return BigDecimal.valueOf(30L * 2 * inter).divide(BigDecimal.valueOf(sizes), 0, RoundingMode.HALF_UP).intValueExact();
    }

    private static Map<String, Integer> bigrams(String s) {
        Map<String, Integer> m = new HashMap<>();
        if (s.length() < 2) {
            m.merge(s, 1, Integer::sum);
            return m;
        }
        for (int i = 0; i + 2 <= s.length(); i++) {
            m.merge(s.substring(i, i + 2), 1, Integer::sum);
        }
        return m;
    }

    private static int size(Map<String, Integer> m) {
        int n = 0;
        for (int v : m.values()) {
            n += v;
        }
        return n;
    }
}
