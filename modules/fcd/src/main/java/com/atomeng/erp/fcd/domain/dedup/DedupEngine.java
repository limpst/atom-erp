package com.atomeng.erp.fcd.domain.dedup;

import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import com.atomeng.erp.fcd.domain.ingest.MerchantText;
import com.atomeng.erp.fcd.domain.rules.PyOps;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 채널 간 중복 제거 · 병합 판정 (설계 B7.2 · BR-FCD-002/003/007, Python {@code ingest/ledger.py decide} 이식).
 * 결정(decide)은 쓰기가 없어 미리보기(드라이런)와 커밋이 같은 코드 경로를 탄다.
 * <ol>
 *   <li>원천 원문 해시가 같으면 완전 중복(DUPLICATE)</li>
 *   <li>승인번호 있음: 같은 카드 · 승인번호 · ±1일 · 금액 일치(해외 ±2%) → 병합(분·금액 정확 = NATURAL_KEY, 아니면 APPROVAL_NO)</li>
 *   <li>퍼지: 같은 카드 · ±1일 · 금액 ±2% 후보 중 S = Σw·s/Σw(시간 .35 · 금액 .35 · 가맹점 .20 · 승인번호 .10)
 *       ≥ 0.92 자동 병합, 0.75–0.92 확인 큐, 미만 신규</li>
 *   <li>같은 채널 · 종류 원천은 거래당 1개(같은 파일 안 동일 행 2개 = 별개 거래, TC-FCD-011)</li>
 * </ol>
 */
public final class DedupEngine {

    public static final double AUTO_MERGE = 0.92;
    public static final double REVIEW_MIN = 0.75;

    private DedupEngine() {}

    /** 원장 거래 스냅샷(판정에 필요한 값만) */
    public record TxnView(UUID id, UUID cardId, String approvalNo, LocalDateTime approvedAt, String precision,
                          long amountKrw, Long approvalAmountKrw, String currency, String merchantName, String bizRegNo,
                          List<String> cancelKeys) {
        public boolean overseas() {
            return !"KRW".equals(currency);
        }

        long refAmount() {
            return approvalAmountKrw != null && approvalAmountKrw != 0 ? approvalAmountKrw : amountKrw;
        }
    }

    /** 원장 조회 포트 (JDBC 구현은 adapter) — 목록은 원장 생성 순서 */
    public interface LedgerLookup {
        boolean rawHashExists(String channel, String kind, String rawHash);

        List<TxnView> byApproval(UUID cardId, String approvalNo);

        List<TxnView> byCardDay(UUID cardId, LocalDate day);

        boolean hasSameChannelKind(UUID txnId, String channel, String kind);
    }

    public record Decision(String bucket, UUID txnId, String method, Double score, Map<String, Object> features) {
        static Decision of(String bucket) {
            return new Decision(bucket, null, bucket.equals("NEW") ? "NEW" : bucket, null, Map.of());
        }
    }

    public static Decision decide(CanonicalRow r, LedgerLookup l) {
        if (l.rawHashExists(r.channel(), r.kind(), r.rawHash())) {
            return new Decision("DUPLICATE", null, "RAW_HASH", null, Map.of());
        }
        if ("CANCEL".equals(r.kind())) {
            return decideCancel(r, l);
        }
        if (r.approvalNo() != null) {
            for (TxnView t : l.byApproval(r.cardId(), r.approvalNo())) {
                if (Math.abs(ChronoUnit.DAYS.between(r.approvedAt().toLocalDate(), t.approvedAt().toLocalDate())) > 1) {
                    continue;
                }
                if (l.hasSameChannelKind(t.id(), r.channel(), r.kind())) {
                    continue;
                }
                long ref = t.refAmount();
                boolean exact = r.amountKrw() == ref || r.amountKrw() == t.amountKrw();
                boolean fxOk = (r.isOverseas() || t.overseas()) && Math.abs(r.amountKrw() - ref) <= 0.02 * Math.max(ref, 1);
                if (!(exact || fxOk)) {
                    continue;
                }
                boolean sameMin = !"DATE".equals(r.precision()) && !"DATE".equals(t.precision())
                        && r.approvedAt().withSecond(0).withNano(0).equals(t.approvedAt().withSecond(0).withNano(0));
                return new Decision("MERGED", t.id(), sameMin && exact ? "NATURAL_KEY" : "APPROVAL_NO", 1.0, Map.of());
            }
        }
        TxnView best = null;
        double bestS = 0.0;
        Map<String, Object> bestF = Map.of();
        for (int k = -1; k <= 1; k++) {
            for (TxnView t : l.byCardDay(r.cardId(), r.approvedAt().toLocalDate().plusDays(k))) {
                if (r.approvalNo() != null && t.approvalNo() != null && !r.approvalNo().equals(t.approvalNo())) {
                    continue;
                }
                if (l.hasSameChannelKind(t.id(), r.channel(), r.kind())) {
                    continue;
                }
                long ref = t.refAmount();
                if (Math.abs(r.amountKrw() - ref) > 0.02 * Math.max(ref, 1)) {
                    continue;
                }
                Map<String, Object> f = new LinkedHashMap<>();
                double s = fuzzyScore(r, t, MerchantText.normalize(t.merchantName(), true), f);
                if (s > bestS) {
                    best = t;
                    bestS = s;
                    bestF = f;
                }
            }
        }
        if (best != null && bestS >= AUTO_MERGE) {
            return new Decision("MERGED", best.id(), "FUZZY", PyOps.round(bestS, 4), bestF);
        }
        if (best != null && bestS >= REVIEW_MIN) {
            return new Decision("REVIEW", best.id(), "FUZZY", PyOps.round(bestS, 4), bestF);
        }
        return Decision.of("NEW");
    }

    /** B7.2 퍼지 점수 */
    public static double fuzzyScore(CanonicalRow r, TxnView t, String merchNormT, Map<String, Object> feats) {
        double sTime;
        if ("DATE".equals(r.precision()) || "DATE".equals(t.precision())) {
            sTime = r.approvedAt().toLocalDate().equals(t.approvedAt().toLocalDate()) ? 0.7 : 0.0;
        } else {
            double mins = Math.abs(Duration.between(r.approvedAt(), t.approvedAt()).toMillis()) / 60000.0;
            sTime = Math.max(0.0, 1 - mins / 10);
        }
        feats.put("s_time", sTime);
        long ref = t.refAmount();
        double sAmt;
        if (r.isOverseas() || t.overseas()) {
            sAmt = Math.max(0.0, 1 - Math.abs(r.amountKrw() - ref) / (0.02 * Math.max(ref, 1)));
        } else {
            sAmt = r.amountKrw() == ref || r.amountKrw() == t.amountKrw() ? 1.0 : 0.0;
        }
        feats.put("s_amt", sAmt);
        double sM = r.bizRegNo() != null && t.bizRegNo() != null && r.bizRegNo().equals(t.bizRegNo()) ? 1.0
                : MerchantText.jaroWinkler(r.merchantNorm(), merchNormT);
        feats.put("s_merch", PyOps.round(sM, 4));
        double num = 0.35 * sTime + 0.35 * sAmt + 0.20 * sM;
        double den = 0.90;
        if (r.approvalNo() != null && t.approvalNo() != null) {
            num += 0.10;
            den += 0.10;
        }
        return num / den;
    }

    /** 취소 식별키(금액|일자) — 채널 간 중복 취소 방지 */
    public static String cancelKey(CanonicalRow r) {
        LocalDateTime when = r.cancelAt() != null ? r.cancelAt() : r.approvedAt();
        return r.cancelAmount() + "|" + when.toLocalDate();
    }

    static Decision decideCancel(CanonicalRow r, LedgerLookup l) {
        TxnView t = findOriginal(r, l);
        if (t == null) {
            return new Decision("ORPHAN_CANCEL", null, "ORPHAN", null, Map.of());
        }
        String key = cancelKey(r);
        String[] kp = key.split("\\|");
        for (String k : t.cancelKeys()) {
            String[] p = k.split("\\|");
            boolean sameAmt = Objects.equals(p[0], kp[0]);
            long days = Math.abs(ChronoUnit.DAYS.between(LocalDate.parse(p[1]), LocalDate.parse(kp[1])));
            if (k.equals(key) || (days <= 1 && sameAmt)) {
                return new Decision("CANCEL_DUP", t.id(), "CANCEL", null, Map.of());
            }
        }
        return new Decision("CANCEL", t.id(), "CANCEL", null, Map.of());
    }

    static TxnView findOriginal(CanonicalRow r, LedgerLookup l) {
        LocalDateTime when = r.cancelAt() != null ? r.cancelAt() : r.approvedAt();
        if (r.approvalNo() != null) {
            TxnView best = null;
            for (TxnView t : l.byApproval(r.cardId(), r.approvalNo())) {
                Duration d = Duration.between(t.approvedAt(), when);
                if (d.compareTo(Duration.ofDays(-2)) >= 0 && d.compareTo(Duration.ofDays(31)) <= 0
                        && (best == null || t.approvedAt().isAfter(best.approvedAt()))) {
                    best = t;
                }
            }
            if (best != null) {
                return best;
            }
        }
        // 승인번호 없는 취소: 같은 카드 · 가맹점 유사 · 금액 ≤ 원승인 · 30일 이내
        TxnView best = null;
        double bs = 0.0;
        for (int k = 0; k < 31; k++) {
            for (TxnView t : l.byCardDay(r.cardId(), when.toLocalDate().minusDays(k))) {
                long ca = r.cancelAmount() == null ? 0 : r.cancelAmount();
                if (ca > t.amountKrw()) {
                    continue;
                }
                double s = MerchantText.jaroWinkler(r.merchantNorm(), MerchantText.normalize(t.merchantName(), true));
                if (s > bs && s >= 0.9) {
                    best = t;
                    bs = s;
                }
            }
        }
        return best;
    }
}
