package com.atomeng.erp.fcd.domain.features;

import com.atomeng.erp.fcd.domain.rules.PyOps;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * L1 결정적 특징(tx.*) — Python {@code detect/features.py} 의 build_features · _limits_and_splits · _recurring 이식.
 * 룰셋 YAML 의 condition_expr 는 이 네임스페이스만 참조하므로, 특징 이름·값 정의를 프로토타입과 똑같이 유지한다.
 *
 * <p>입력은 순수 값(거래·카드·가맹점·임직원·포트 맥락)이고 DB·Spring 에 의존하지 않는다. 같은 카드 묶음·기간의
 * 거래 전체를 넣어야 일 합계·월 누계·쪼개기·정기결제 판정이 맞다.
 */
public final class L1FeatureBuilder {

    private L1FeatureBuilder() {}

    /** 원장 거래(정규화) — seq 는 같은 승인시각일 때의 원장 순서(안정 정렬 키) */
    public record TxnInput(String txnId, long seq, String cardId, String employeeId, LocalDateTime approvedAt,
                           String precision, long amountKrw, long cancelledAmountKrw, String status, String currency,
                           String country, Boolean isOnline, String merchantId, String merchantName,
                           String categoryHint, boolean pgUnresolved) {
        long netAmount() {
            return Math.max(0, amountKrw - cancelledAmountKrw);
        }
    }

    public record CardInfo(String cardType, String status, LocalDate closedOn, long limitPerTxn, long limitDaily,
                           long limitMonthly) {}

    public record MerchantInfo(String name, String category, boolean isOnline, String country, boolean blacklisted,
                               boolean relatedParty) {}

    /** L2 포트가 주는 L1 입력(사전결재 · 해외출장 · 상품권 명세) — 없으면 기본값 */
    public record PortInput(boolean preapproved, boolean preapprovedCat, boolean tripOverseas,
                            Object giftcardRecipients) {
        public static final PortInput NONE = new PortInput(false, false, false, null);
    }

    public record Row(TxnInput txn, LocalDate date, Map<String, Object> tx, Map<String, Object> ctx) {}

    private static final List<String> PERSONAL_KW = List.of("생활", "패션", "뷰티", "키즈", "화장품");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    public static List<Row> build(List<TxnInput> txns, Map<String, CardInfo> cards, Map<String, MerchantInfo> merchants,
                                  Map<String, LocalDate> resignedOn, Map<String, PortInput> port,
                                  Map<String, Object> params, CategoryCatalog categories, HolidayCalendar holidays) {
        List<TxnInput> sorted = new ArrayList<>(txns);
        sorted.sort(Comparator.comparing(TxnInput::approvedAt).thenComparingLong(TxnInput::seq));
        int nf = (int) PyOps.toInt(params.getOrDefault("night_from", 23L));
        int nt = (int) PyOps.toInt(params.getOrDefault("night_to", 6L));
        List<Row> rows = new ArrayList<>(sorted.size());
        for (TxnInput t : sorted) {
            CardInfo card = cards.get(t.cardId());
            MerchantInfo m = t.merchantId() == null ? null : merchants.get(t.merchantId());
            String cat = m != null ? m.category() : "UNKNOWN";
            if ("UNKNOWN".equals(cat) && t.categoryHint() != null && !t.categoryHint().isEmpty()) {
                cat = categories.fromHint(t.categoryHint()).orElse("UNKNOWN");
            }
            String emp = t.employeeId();
            LocalDateTime at = t.approvedAt();
            LocalDate d = at.toLocalDate();
            Object[] pol = policy(cat, params);
            LocalDate closed = card.closedOn();
            LocalDate resigned = emp == null ? null : resignedOn.get(emp);
            boolean inactive = (closed != null && !d.isBefore(closed)) || (resigned != null && !d.isBefore(resigned));
            Long hour = "DATE".equals(t.precision()) ? null : (long) at.getHour();
            boolean isNight = hour != null && (hour >= nf || hour < nt);
            PortInput pi = port.getOrDefault(t.txnId(), PortInput.NONE);
            Map<String, Object> tx = new LinkedHashMap<>();
            tx.put("amount", (double) t.netAmount());
            tx.put("gross_amount", (double) t.amountKrw());
            tx.put("cancelled", "CANCELLED".equals(t.status()));
            tx.put("cancelled_amount", (double) t.cancelledAmountKrw());
            tx.put("status", t.status());
            tx.put("hour", hour);
            tx.put("local_time", hour != null ? at.format(HHMM) : "시각없음");
            tx.put("date", d.toString());
            tx.put("weekday", (long) (d.getDayOfWeek().getValue() - 1));
            tx.put("precision", t.precision());
            tx.put("is_night", isNight);
            tx.put("is_deep_night", hour != null && 1 <= hour && hour < 5);
            tx.put("is_holiday", d.getDayOfWeek().getValue() >= 6 || holidays.isHoliday(d));
            tx.put("category", cat);
            tx.put("category_name", categories.name(cat));
            tx.put("policy", pol[0]);
            tx.put("cat_max", pol[1]);
            tx.put("is_online", m != null ? m.isOnline() : Boolean.TRUE.equals(t.isOnline()));
            tx.put("is_overseas", !"KRW".equals(t.currency()));
            String country = t.country() != null && !t.country().isEmpty() ? t.country() : (m != null ? m.country() : null);
            tx.put("country", country);
            tx.put("blacklisted", m != null && m.blacklisted());
            tx.put("related_party", m != null && m.relatedParty());
            tx.put("pg_unresolved", t.pgUnresolved());
            tx.put("merchant_name", t.merchantName());
            tx.put("card_id", t.cardId());
            tx.put("card_type", card.cardType());
            tx.put("card_status", inactive ? card.status() : "ACTIVE");
            tx.put("card_closed_on", closed != null ? closed.toString() : null);
            tx.put("card_inactive", inactive);
            tx.put("limit_txn", (double) card.limitPerTxn());
            tx.put("limit_day", (double) card.limitDaily());
            tx.put("limit_month", (double) card.limitMonthly());
            tx.put("online_personal", m != null && m.isOnline() && PERSONAL_KW.stream().anyMatch(k -> m.name().contains(k)));
            tx.put("preapproved", pi.preapproved());
            tx.put("preapproved_cat", pi.preapprovedCat());
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("shared_unassigned", "SHARED".equals(card.cardType()) && emp == null);
            ctx.put("trip_overseas", pi.tripOverseas());
            ctx.put("giftcard_recipients", pi.giftcardRecipients());
            ctx.put("preapproved", pi.preapproved());
            ctx.put("preapproved_cat", pi.preapprovedCat());
            rows.add(new Row(t, d, tx, ctx));
        }
        limitsAndSplits(rows);
        recurring(rows);
        return rows;
    }

    private static Object[] policy(String cat, Map<String, Object> params) {
        Object polObj = params.get("category_policy");
        Map<?, ?> pol = polObj instanceof Map<?, ?> m ? m : Map.of();
        Object block = pol.get("BLOCK");
        if (block instanceof List<?> bl && bl.contains(cat)) {
            return new Object[] {"BLOCK", null};
        }
        Object rest = pol.get("RESTRICT");
        if (rest instanceof Map<?, ?> rm && rm.containsKey(cat)) {
            return new Object[] {"RESTRICT", PyOps.toFloat(rm.get(cat))};
        }
        return new Object[] {"ALLOW", null};
    }

    private static boolean cancelled(Row r) {
        return (Boolean) r.tx().get("cancelled");
    }

    private static double amt(Row r) {
        return (Double) r.tx().get("amount");
    }

    private static double limit(Row r) {
        return (Double) r.tx().get("limit_txn");
    }

    private static String person(Row r) {
        return r.txn().employeeId() != null ? r.txn().employeeId() : r.txn().cardId();
    }

    static void limitsAndSplits(List<Row> rows) {
        Map<String, Double> dayTot = new HashMap<>();
        Map<String, Double> monthCum = new HashMap<>();
        for (Row r : rows) {
            if (cancelled(r)) {
                r.tx().put("day_total", 0.0);
                r.tx().put("month_cum", 0.0);
                continue;
            }
            String kd = r.txn().cardId() + "|" + r.date();
            dayTot.merge(kd, amt(r), Double::sum);
            String km = r.txn().cardId() + "|" + r.date().toString().substring(0, 7);
            double mc = monthCum.merge(km, amt(r), Double::sum);
            r.tx().put("month_cum", mc);
        }
        for (Row r : rows) {
            if (!cancelled(r)) {
                r.tx().put("day_total", dayTot.get(r.txn().cardId() + "|" + r.date()));
            }
            r.tx().put("hug", false);
            r.tx().put("hug_level", null);
            for (double lv : new double[] {limit(r), 100_000.0, 500_000.0}) {
                if (0.95 * lv <= amt(r) && amt(r) < lv) {
                    r.tx().put("hug", true);
                    r.tx().put("hug_level", lv);
                    break;
                }
            }
            r.tx().put("split_hit", false);
            r.tx().put("split_cnt", 0L);
            r.tx().put("split_total", 0.0);
            r.tx().put("split_intent", 0.0);
            r.tx().put("split_group", null);
            r.tx().put("split_multi_hit", false);
            r.tx().put("split_multi_users", 0L);
            r.tx().put("split_multi_total", 0.0);
        }
        // R-SPLIT: 같은 사용자(카드) · 같은 가맹점 · ±60분, 각 건 ≤ 1회 한도 < 합계 (v3.0 G3.3 의도성)
        Map<String, List<Row>> grp = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!cancelled(r)) {
                grp.computeIfAbsent(person(r) + "|" + r.txn().merchantId(), k -> new ArrayList<>()).add(r);
            }
        }
        for (List<Row> lst : grp.values()) {
            if (lst.size() < 2) {
                continue;
            }
            lst.sort(Comparator.comparing(x -> x.txn().approvedAt()));
            for (Row r : lst) {
                List<Row> win = lst.stream().filter(x -> Math.abs(ChronoUnit.SECONDS.between(r.txn().approvedAt(),
                        x.txn().approvedAt())) <= 3600).toList();
                double lv = limit(r);
                if (win.size() >= 2 && win.stream().allMatch(x -> amt(x) <= lv)) {
                    double tot = 0;
                    for (Row x : win) {
                        tot += amt(x);
                    }
                    if (tot > lv) {
                        LocalDateTime mx = win.stream().map(x -> x.txn().approvedAt()).max(Comparator.naturalOrder()).get();
                        LocalDateTime mn = win.stream().map(x -> x.txn().approvedAt()).min(Comparator.naturalOrder()).get();
                        double span = ChronoUnit.SECONDS.between(mn, mx) / 60.0;
                        double minAmt = win.stream().mapToDouble(L1FeatureBuilder::amt).min().getAsDouble();
                        double intent = 0.5 * minAmt / lv + 0.3 * Math.exp(-span / 30) + 0.2 * 2 / win.size();
                        r.tx().put("split_hit", true);
                        r.tx().put("split_cnt", (long) win.size());
                        r.tx().put("split_total", tot);
                        r.tx().put("split_intent", PyOps.round(intent, 3));
                        r.tx().put("split_group", win.stream().map(x -> x.txn().txnId()).min(Comparator.naturalOrder()).get());
                    }
                }
            }
        }
        // R-SPLIT-MULTI: 같은 가맹점 · 10분 창 · 서로 다른 사용자 ≥ 2 · 합계 > 관련 카드 최소 1회 한도, 쪼개기 모양만
        Map<String, List<Row>> bym = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!cancelled(r) && !(Boolean) r.tx().get("is_online")) {
                bym.computeIfAbsent(String.valueOf(r.txn().merchantId()), k -> new ArrayList<>()).add(r);
            }
        }
        for (List<Row> lst : bym.values()) {
            if (lst.size() < 2) {
                continue;
            }
            lst.sort(Comparator.comparing(x -> x.txn().approvedAt()));
            int j = 0;
            for (int i = 0; i < lst.size(); i++) {
                Row r = lst.get(i);
                while (ChronoUnit.SECONDS.between(lst.get(j).txn().approvedAt(), r.txn().approvedAt()) > 600) {
                    j++;
                }
                List<Row> win = lst.subList(j, i + 1);
                Set<String> users = new HashSet<>();
                win.forEach(x -> users.add(person(x)));
                if (users.size() >= 2) {
                    double tot = 0;
                    for (Row x : win) {
                        tot += amt(x);
                    }
                    double lv = win.stream().mapToDouble(L1FeatureBuilder::limit).min().getAsDouble();
                    final double total = tot;
                    if (tot > lv && win.stream().allMatch(x -> 0.5 * lv <= amt(x) && amt(x) < lv)) {
                        String minId = win.stream().map(x -> x.txn().txnId()).min(Comparator.naturalOrder()).get();
                        for (Row x : win) {
                            x.tx().put("split_multi_hit", true);
                            x.tx().put("split_multi_users", (long) users.size());
                            x.tx().put("split_multi_total", total);
                            Object g = x.tx().get("split_group");
                            x.tx().put("split_group", PyOps.truthy(g) ? g : "M" + minId);
                        }
                    }
                }
            }
        }
    }

    /** 부록 A 결함 3: 같은 카드 · 가맹점이 3개월 이상 유사 일자(±3일) · 유사 금액(±15%)으로 반복 → recurring */
    static void recurring(List<Row> rows) {
        Map<String, List<Row>> grp = new LinkedHashMap<>();
        for (Row r : rows) {
            grp.computeIfAbsent(r.txn().cardId() + "|" + r.txn().merchantId(), k -> new ArrayList<>()).add(r);
            r.tx().put("recurring", false);
        }
        for (List<Row> lst : grp.values()) {
            Set<String> months = new HashSet<>();
            lst.forEach(x -> months.add(x.date().toString().substring(0, 7)));
            if (months.size() < 3) {
                continue;
            }
            double[] doms = lst.stream().mapToDouble(x -> x.date().getDayOfMonth()).toArray();
            double[] amts = lst.stream().mapToDouble(x -> (Double) x.tx().get("gross_amount")).toArray();
            double med = median(amts);
            if (pstd(doms) <= 3.0 && lst.size() <= months.size() + 2) {
                for (Row x : lst) {
                    double g = (Double) x.tx().get("gross_amount");
                    if (med > 0 && Math.abs(g - med) <= 0.15 * med) {
                        x.tx().put("recurring", true);
                    }
                }
            }
        }
    }

    static double median(double[] a) {
        double[] s = a.clone();
        java.util.Arrays.sort(s);
        int n = s.length;
        return n % 2 == 1 ? s[n / 2] : (s[n / 2 - 1] + s[n / 2]) / 2.0;
    }

    /** numpy.std (모집단, 2-pass) */
    static double pstd(double[] a) {
        double mean = 0;
        for (double x : a) {
            mean += x;
        }
        mean /= a.length;
        double ss = 0;
        for (double x : a) {
            ss += (x - mean) * (x - mean);
        }
        return Math.sqrt(ss / a.length);
    }
}
