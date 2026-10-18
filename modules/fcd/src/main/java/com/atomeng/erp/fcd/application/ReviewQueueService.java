package com.atomeng.erp.fcd.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.erp.fcd.domain.review.ReviewBudgetPolicy;
import com.atomeng.erp.fcd.domain.review.ReviewBudgetPolicy.Candidate;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검토 큐 정책 (프로토타입 라운드 3–5 채택 운영 정책 · README §12 섀도 계획):
 * <ul>
 *   <li>MONTHLY_BUDGET(기본): 월 거래 1,000건당 k건(기본 20)을 점수 상위 순으로 케이스화 — 알림량을 예산으로 고정</li>
 *   <li>THRESHOLD: 점수 ≥ 60(BR-FCD-010 소명요청) 전부</li>
 * </ul>
 * 이미 케이스가 있는 거래는 건너뛰고, 실행 기록(review_run)을 남긴다.
 */
@Service
public class ReviewQueueService {

    private final JdbcTemplate jdbc;
    private final CaseService cases;
    private final double defaultK;
    private final int defaultMin;

    public ReviewQueueService(JdbcTemplate jdbc, CaseService cases,
                              @Value("${atom.fcd.review.k-per-1000:20}") double defaultK,
                              @Value("${atom.fcd.review.min-score:30}") int defaultMin) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.defaultK = defaultK;
        this.defaultMin = defaultMin;
    }

    @Transactional
    public Map<String, Object> run(YearMonth ym, String policy, Double kPer1000, Integer minScore, UUID actor) {
        String p = policy == null ? "MONTHLY_BUDGET" : policy;
        double k = kPer1000 == null ? defaultK : kPer1000;
        int min = minScore == null ? (p.equals("THRESHOLD") ? 60 : defaultMin) : minScore;
        List<Candidate> month = jdbc.query("""
                SELECT id, coalesce(risk_score, 0) AS s, seq FROM fcd.txn
                 WHERE removed_at IS NULL AND approved_at >= ? AND approved_at < ? ORDER BY approved_at, seq""",
                (rs, i) -> new Candidate(rs.getObject(1, UUID.class).toString(), rs.getInt(2), rs.getLong(3)),
                Timestamp.valueOf(ym.atDay(1).atStartOfDay()), Timestamp.valueOf(ym.plusMonths(1).atDay(1).atStartOfDay()));
        List<Candidate> selected = p.equals("THRESHOLD")
                ? month.stream().filter(c -> c.score() >= min)
                        .sorted((a, b) -> Integer.compare(b.score(), a.score())).toList()
                : ReviewBudgetPolicy.select(month, k, min);
        List<UUID> opened = cases.openFor(selected.stream().map(c -> UUID.fromString(c.txnId())).toList(),
                p.equals("THRESHOLD") ? null : "월 검토 예산 상위", false);
        jdbc.update("""
                INSERT INTO fcd.review_run(id, period_ym, policy, k_per_1000, n_txn, k_selected, min_score, selected, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", UuidV7.next(), ym.toString(), p, k, month.size(),
                selected.size(), min, "[" + String.join(",", selected.stream().map(c -> "\"" + c.txnId() + "\"").toList())
                        + "]", actor);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", ym.toString());
        out.put("policy", p);
        out.put("nTxn", month.size());
        out.put("budget", p.equals("THRESHOLD") ? null : ReviewBudgetPolicy.budget(month.size(), k));
        out.put("selected", selected.stream().map(Candidate::txnId).toList());
        out.put("casesOpened", opened);
        return out;
    }
}
