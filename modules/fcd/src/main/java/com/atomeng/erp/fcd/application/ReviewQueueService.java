package com.atomeng.erp.fcd.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fcd.domain.review.ReviewBudgetPolicy;
import com.atomeng.erp.fcd.domain.review.ReviewBudgetPolicy.Candidate;
import java.sql.Timestamp;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <p>FCD-13(보안 검토 §2): policy 는 enum, kPer1000 · minScore 는 설정 범위 안에서만, 기본값과 다른 값(재정의)은
 * AUDITOR 만 사유와 함께 — 재정의자 · 사유는 review_run 에 남는다. FIN 은 정책 선택(기본값)으로만 실행한다.
 */
@Service
public class ReviewQueueService {

    /** 검토 큐 정책(enum) — 임의 문자열은 422 */
    public enum ReviewPolicy { MONTHLY_BUDGET, THRESHOLD }

    /** THRESHOLD 정책의 기본 임계(BR-FCD-010 소명요청 60) */
    static final int THRESHOLD_MIN = 60;

    private final JdbcTemplate jdbc;
    private final CaseService cases;
    private final double defaultK;
    private final int defaultMin;
    private final double kMin;
    private final double kMax;
    private final int minScoreMin;
    private final int minScoreMax;

    public ReviewQueueService(JdbcTemplate jdbc, CaseService cases,
                              @Value("${atom.fcd.review.k-per-1000:20}") double defaultK,
                              @Value("${atom.fcd.review.min-score:30}") int defaultMin,
                              @Value("${atom.fcd.review.k-per-1000-min:5}") double kMin,
                              @Value("${atom.fcd.review.k-per-1000-max:100}") double kMax,
                              @Value("${atom.fcd.review.min-score-min:20}") int minScoreMin,
                              @Value("${atom.fcd.review.min-score-max:60}") int minScoreMax) {
        this.jdbc = jdbc;
        this.cases = cases;
        this.defaultK = defaultK;
        this.defaultMin = defaultMin;
        this.kMin = kMin;
        this.kMax = kMax;
        this.minScoreMin = minScoreMin;
        this.minScoreMax = minScoreMax;
    }

    static ReviewPolicy policyOf(String policy) {
        if (policy == null) {
            return ReviewPolicy.MONTHLY_BUDGET;
        }
        try {
            return ReviewPolicy.valueOf(policy);
        } catch (IllegalArgumentException e) {
            throw new DomainException(FcdErrors.REVIEW_PARAM_RANGE, "policy: MONTHLY_BUDGET | THRESHOLD");
        }
    }

    @Transactional
    public Map<String, Object> run(YearMonth ym, String policy, Double kPer1000, Integer minScore, String reason, UUID actor,
                                   Set<String> roles) {
        ReviewPolicy pol = policyOf(policy);
        String p = pol.name();
        int baseMin = pol == ReviewPolicy.THRESHOLD ? THRESHOLD_MIN : defaultMin;
        double k = kPer1000 == null ? defaultK : kPer1000;
        int min = minScore == null ? baseMin : minScore;
        if (Double.isNaN(k) || k < kMin || k > kMax) {
            throw new DomainException(FcdErrors.REVIEW_PARAM_RANGE, "kPer1000 는 " + kMin + "~" + kMax + " 범위");
        }
        if (min < minScoreMin || min > minScoreMax) {
            throw new DomainException(FcdErrors.REVIEW_PARAM_RANGE, "minScore 는 " + minScoreMin + "~" + minScoreMax + " 범위");
        }
        boolean override = Double.compare(k, defaultK) != 0 || min != baseMin;
        String overrideReason = null;
        if (override) {
            if (!roles.contains("AUDITOR")) {
                throw new DomainException(FcdErrors.REVIEW_OVERRIDE_FORBIDDEN,
                        "FCD-13: 검토 파라미터(kPer1000 · minScore) 재정의는 AUDITOR 만 할 수 있습니다");
            }
            if (reason == null || reason.isBlank()) {
                throw new DomainException(FcdErrors.REVIEW_PARAM_RANGE, "FCD-13: 파라미터 재정의 사유(reason) 필수");
            }
            overrideReason = reason;
        }
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
                INSERT INTO fcd.review_run(id, period_ym, policy, k_per_1000, n_txn, k_selected, min_score, selected, created_by,
                                           override_reason, overridden_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""", UuidV7.next(), ym.toString(), p, k, month.size(),
                selected.size(), min, "[" + String.join(",", selected.stream().map(c -> "\"" + c.txnId() + "\"").toList())
                        + "]", actor, overrideReason, override ? actor : null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("period", ym.toString());
        out.put("policy", p);
        out.put("kPer1000", k);
        out.put("minScore", min);
        out.put("override", override);
        out.put("nTxn", month.size());
        out.put("budget", p.equals("THRESHOLD") ? null : ReviewBudgetPolicy.budget(month.size(), k));
        out.put("selected", selected.stream().map(Candidate::txnId).toList());
        out.put("casesOpened", opened);
        return out;
    }
}
