package com.atomeng.erp.fcd.domain.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 월 예산 검토 큐 정책 — 거래 1,000건당 k건을 점수 상위 순으로 검토(프로토타입 라운드 3–5 채택 운영 정책,
 * Python {@code experiments.budget_flag}: kk = round(n·k/1000), 점수 내림차순 안정 정렬 상위 kk).
 * 운영 보완: minScore 미만은 예산이 남아도 뽑지 않는다(0점 거래 소명 요청 방지 — 프로토타입은 0).
 */
public final class ReviewBudgetPolicy {

    private ReviewBudgetPolicy() {}

    public record Candidate(String txnId, int score, long order) {}

    public static int budget(int nTxn, double kPer1000) {
        return (int) Math.rint(nTxn * kPer1000 / 1000.0);
    }

    public static List<Candidate> select(List<Candidate> monthTxns, double kPer1000, int minScore) {
        int kk = budget(monthTxns.size(), kPer1000);
        List<Candidate> sorted = new ArrayList<>(monthTxns);
        sorted.sort(Comparator.comparingLong(Candidate::order));                 // 원래 순서
        sorted.sort(Comparator.comparingInt(Candidate::score).reversed());       // 안정 정렬(동점은 원래 순서)
        List<Candidate> out = new ArrayList<>();
        for (Candidate c : sorted) {
            if (out.size() >= kk) {
                break;
            }
            if (c.score() >= minScore) {
                out.add(c);
            }
        }
        return out;
    }
}
