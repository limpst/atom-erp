package com.atomeng.erp.fcd.domain.dedup;

import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 원천들로부터 거래 필드 재도출 (BR-FCD-002/007/008, Python {@code Ledger.recompute} 이식).
 * 금액 · 환율은 매입 &gt; 승인, 승인시각은 정밀도(SECOND&gt;MINUTE&gt;DATE) · 채널(API&gt;EXCEL&gt;CSV),
 * 가맹점 상세는 정보가 더 많은 원천, 취소는 취소키 중복 제외 누적 → 전액이면 CANCELLED, 일부면 PARTIALLY_CANCELLED.
 */
public final class TxnDerivation {

    private TxnDerivation() {}

    private static final Map<String, Integer> CH = Map.of("API", 3, "EXCEL", 2, "CSV", 1);
    private static final Map<String, Integer> PREC = Map.of("SECOND", 3, "MINUTE", 2, "DATE", 1);

    /** 현재 거래 값(재도출 시 일부 필드는 기존 값 유지) */
    public record Current(String approvalNo, LocalDateTime approvedAt, String precision, Double fxRate, String bizRegNo,
                          String categoryHint, String currency) {}

    public record Derived(long amountKrw, Long approvalAmountKrw, Double fxRate, LocalDateTime approvedAt,
                          String precision, String merchantName, String bizRegNo, String categoryHint,
                          String approvalNo, long cancelledAmountKrw, String status) {}

    /** @param sources 분리되지 않은 원천(부착 순서) */
    public static Derived derive(Current cur, List<CanonicalRow> sources, String fallbackMerchant) {
        List<CanonicalRow> main = sources.stream().filter(r -> r.kind().equals("APPROVAL") || r.kind().equals("PURCHASE"))
                .toList();
        if (main.isEmpty()) {
            return null;
        }
        List<CanonicalRow> purch = main.stream().filter(r -> r.kind().equals("PURCHASE")).toList();
        List<CanonicalRow> appr = main.stream().filter(r -> r.kind().equals("APPROVAL")).toList();
        long amount = !purch.isEmpty() ? purch.get(0).amountKrw() : appr.get(0).amountKrw();
        Long approvalAmount = !appr.isEmpty() ? appr.get(0).amountKrw() : null;
        Double fx = cur.fxRate();
        if (!purch.isEmpty() && purch.get(0).fxRate() != null && purch.get(0).fxRate() != 0) {
            fx = purch.get(0).fxRate();
        }
        // max(): 같은 키면 먼저 온 원천(Python max 와 같음)
        CanonicalRow timed = main.get(0);
        for (CanonicalRow r : main) {
            if (cmpTimed(r, timed) > 0) {
                timed = r;
            }
        }
        CanonicalRow rich = main.get(0);
        for (CanonicalRow r : main) {
            if (richKey().compare(r, rich) > 0) {
                rich = r;
            }
        }
        String merchant = rich.merchantName() != null ? rich.merchantName() : fallbackMerchant;
        String biz = rich.bizRegNo() != null && !rich.bizRegNo().isEmpty() ? rich.bizRegNo() : cur.bizRegNo();
        String hint = rich.categoryHint() != null && !rich.categoryHint().isEmpty() ? rich.categoryHint() : cur.categoryHint();
        String approvalNo = cur.approvalNo();
        if (approvalNo == null || approvalNo.isEmpty()) {
            approvalNo = main.stream().map(CanonicalRow::approvalNo).filter(x -> x != null && !x.isEmpty()).findFirst()
                    .orElse(null);
        }
        List<CanonicalRow> cancels = sources.stream().filter(r -> r.kind().equals("CANCEL")).toList();
        long cancelled = 0;
        Set<String> seen = new HashSet<>();
        for (CanonicalRow c : cancels) {
            if (seen.add(DedupEngine.cancelKey(c))) {
                cancelled += c.cancelAmount() == null ? 0 : c.cancelAmount();
            }
        }
        long ref = approvalAmount != null && approvalAmount != 0 ? approvalAmount : amount;
        boolean explicitFull = cancels.stream().anyMatch(c -> Boolean.FALSE.equals(c.partial())
                && (c.cancelAmount() == null ? 0 : c.cancelAmount()) >= ref);
        boolean overseas = !"KRW".equals(cur.currency());
        String status;
        if (cancelled > 0 && (explicitFull || cancelled >= amount || (overseas && cancelled >= 0.98 * amount))) {
            cancelled = Math.max(cancelled, amount);
            status = "CANCELLED";
        } else if (cancelled > 0) {
            status = "PARTIALLY_CANCELLED";
        } else {
            status = !purch.isEmpty() ? "PURCHASED" : "APPROVED";
        }
        return new Derived(amount, approvalAmount, fx, timed.approvedAt(), timed.precision(), merchant, biz, hint,
                approvalNo, cancelled, status);
    }

    private static int cmpTimed(CanonicalRow a, CanonicalRow b) {
        int c = Integer.compare(PREC.get(a.precision()), PREC.get(b.precision()));
        return c != 0 ? c : Integer.compare(CH.get(a.channel()), CH.get(b.channel()));
    }

    private static Comparator<CanonicalRow> richKey() {
        return Comparator.<CanonicalRow, Boolean>comparing(r -> r.bizRegNo() != null && !r.bizRegNo().isEmpty())
                .thenComparing(r -> r.categoryHint() != null && !r.categoryHint().isEmpty())
                .thenComparingInt(r -> r.merchantName() == null ? 0 : r.merchantName().length());
    }
}
