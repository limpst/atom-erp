package com.atomeng.erp.fcd.adapter.out.persistence;

import com.atomeng.common.core.UuidV7;
import com.atomeng.erp.fcd.domain.dedup.DedupEngine;
import com.atomeng.erp.fcd.domain.dedup.DedupEngine.TxnView;
import com.atomeng.erp.fcd.domain.dedup.TxnDerivation;
import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import com.atomeng.erp.fcd.domain.ingest.MerchantText;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 정규화 거래 원장 저장소 — {@link DedupEngine.LedgerLookup} 구현(판정 조회) + 적용(거래 · 원천 쓰기 · 재도출 · 고아 취소 재매칭).
 * 원장에서 제외된 거래(removed_at)와 분리된 원천(detached)은 모든 조회에서 빠진다.
 */
@Repository
public class LedgerStore implements DedupEngine.LedgerLookup {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyyMMddHHmm");

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public LedgerStore(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    // ------------------------------------------------------------------ JSON

    public String toJson(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public CanonicalRow canonical(String json) {
        try {
            return om.readValue(json, CanonicalRow.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ LedgerLookup

    @Override
    public boolean rawHashExists(String channel, String kind, String rawHash) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM fcd.txn_source WHERE channel = ? AND kind = ? AND raw_hash = ? AND NOT detached)""",
                Boolean.class, channel, kind, rawHash));
    }

    private static final String VIEW_SQL = """
            SELECT t.id, t.card_id, t.approval_no, t.approved_at, t.approved_at_precision, t.amount_krw,
                   t.approval_amount_krw, t.currency, t.merchant_name, t.biz_reg_no,
                   coalesce((SELECT array_agg(s.cancel_key ORDER BY s.seq) FROM fcd.txn_source s
                             WHERE s.txn_id = t.id AND NOT s.detached AND s.cancel_key IS NOT NULL), '{}') AS cancel_keys
              FROM fcd.txn t
            """;

    private TxnView view(ResultSet rs, int i) throws SQLException {
        BigDecimal aa = rs.getBigDecimal("approval_amount_krw");
        String[] keys = (String[]) rs.getArray("cancel_keys").getArray();
        return new TxnView(rs.getObject("id", UUID.class), rs.getObject("card_id", UUID.class), rs.getString("approval_no"),
                rs.getTimestamp("approved_at").toLocalDateTime(), rs.getString("approved_at_precision"),
                rs.getBigDecimal("amount_krw").longValueExact(), aa == null ? null : aa.longValueExact(),
                rs.getString("currency"), rs.getString("merchant_name"), rs.getString("biz_reg_no"), List.of(keys));
    }

    @Override
    public List<TxnView> byApproval(UUID cardId, String approvalNo) {
        return jdbc.query(VIEW_SQL + " WHERE t.card_id = ? AND t.approval_no = ? AND t.removed_at IS NULL ORDER BY t.seq",
                this::view, cardId, approvalNo);
    }

    @Override
    public List<TxnView> byCardDay(UUID cardId, LocalDate day) {
        return jdbc.query(VIEW_SQL + " WHERE t.card_id = ? AND t.approved_at >= ? AND t.approved_at < ? "
                        + "AND t.removed_at IS NULL ORDER BY t.seq", this::view, cardId,
                Timestamp.valueOf(day.atStartOfDay()), Timestamp.valueOf(day.plusDays(1).atStartOfDay()));
    }

    @Override
    public boolean hasSameChannelKind(UUID txnId, String channel, String kind) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM fcd.txn_source WHERE txn_id = ? AND channel = ? AND kind = ? AND NOT detached)""",
                Boolean.class, txnId, channel, kind));
    }

    // ------------------------------------------------------------------ 쓰기

    public UUID insertTxn(CanonicalRow r, UUID merchantId, boolean pg, UUID batchId, UUID employeeId) {
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO fcd.txn(id, card_id, employee_id, attribution, approval_no, approved_at, approved_at_precision,
                                    amount_krw, approval_amount_krw, currency, amount_foreign, fx_rate, is_online, country,
                                    merchant_id, merchant_name, biz_reg_no, category_hint, pg_unresolved, status,
                                    created_batch_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, r.cardId(), employeeId, employeeId == null ? "UNASSIGNED" : "RESOLVED", r.approvalNo(),
                Timestamp.valueOf(r.approvedAt()), r.precision(), r.amountKrw(),
                "APPROVAL".equals(r.kind()) ? r.amountKrw() : null, r.currency(), r.amountForeign(), r.fxRate(),
                r.isOnline(), r.country(), merchantId, r.merchantName(), r.bizRegNo(), r.categoryHint(), pg,
                "PURCHASE".equals(r.kind()) ? "PURCHASED" : "APPROVED", batchId);
        return id;
    }

    public UUID attachSource(UUID txnId, CanonicalRow r, UUID batchId, String method, Double score, String cancelKey) {
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO fcd.txn_source(id, txn_id, channel, kind, batch_id, row_no, canonical, raw_hash, match_method,
                                           match_score, cancel_key)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""",
                id, txnId, r.channel(), r.kind(), batchId, r.rowNo(), toJson(r), r.rawHash(), method, score, cancelKey);
        return id;
    }

    public record SourceRow(UUID id, UUID txnId, UUID batchId, String kind, String matchMethod, CanonicalRow row) {}

    public List<SourceRow> sourcesOf(UUID txnId) {
        return jdbc.query("SELECT id, txn_id, batch_id, kind, match_method, canonical::text AS c FROM fcd.txn_source "
                        + "WHERE txn_id = ? AND NOT detached ORDER BY seq",
                (rs, i) -> new SourceRow(rs.getObject("id", UUID.class), rs.getObject("txn_id", UUID.class),
                        rs.getObject("batch_id", UUID.class), rs.getString("kind"), rs.getString("match_method"),
                        canonical(rs.getString("c"))), txnId);
    }

    /** 원천들로부터 거래 필드 재도출 + 자연키 · 귀속 갱신 (BR-FCD-002/006/007/008) */
    public void recompute(UUID txnId) {
        Map<String, Object> t = jdbc.queryForMap("""
                SELECT t.approval_no, t.approved_at, t.approved_at_precision, t.fx_rate, t.biz_reg_no, t.category_hint,
                       t.currency, t.merchant_name, c.card_token
                  FROM fcd.txn t JOIN fcd.card c ON c.id = t.card_id WHERE t.id = ?""", txnId);
        List<CanonicalRow> rows = sourcesOf(txnId).stream().map(SourceRow::row).toList();
        BigDecimal fx = (BigDecimal) t.get("fx_rate");
        TxnDerivation.Derived d = TxnDerivation.derive(new TxnDerivation.Current((String) t.get("approval_no"),
                ((Timestamp) t.get("approved_at")).toLocalDateTime(), (String) t.get("approved_at_precision"),
                fx == null ? null : fx.doubleValue(), (String) t.get("biz_reg_no"), (String) t.get("category_hint"),
                (String) t.get("currency")), rows, (String) t.get("merchant_name"));
        if (d == null) {
            return;
        }
        String nk = d.approvalNo() == null || "DATE".equals(d.precision()) ? null
                : t.get("card_token") + "|" + d.approvalNo() + "|" + d.approvedAt().format(MINUTE) + "|"
                + (d.approvalAmountKrw() != null && d.approvalAmountKrw() != 0 ? d.approvalAmountKrw() : d.amountKrw());
        jdbc.update("""
                UPDATE fcd.txn SET amount_krw = ?, approval_amount_krw = ?, fx_rate = ?, approved_at = ?,
                       approved_at_precision = ?, merchant_name = ?, biz_reg_no = ?, category_hint = ?, approval_no = ?,
                       cancelled_amount_krw = ?, status = ?, natural_key = ?, updated_at = now(), version = version + 1
                 WHERE id = ?""",
                d.amountKrw(), d.approvalAmountKrw(), d.fxRate(), Timestamp.valueOf(d.approvedAt()), d.precision(),
                d.merchantName(), d.bizRegNo(), d.categoryHint(), d.approvalNo(), d.cancelledAmountKrw(), d.status(), nk,
                txnId);
        reattribute(txnId);
    }

    /** 실사용자: 개인카드는 소지자, 공용카드는 승인일시가 포함된 대여 기록(BR-FCD-006), 없으면 미귀속 */
    public void reattribute(UUID txnId) {
        jdbc.update("""
                UPDATE fcd.txn t SET employee_id = x.emp, attribution = CASE WHEN x.emp IS NULL THEN 'UNASSIGNED' ELSE 'RESOLVED' END
                  FROM (SELECT t2.id, CASE WHEN c.card_type = 'SHARED' THEN
                                 (SELECT a.employee_id FROM fcd.card_assignment a WHERE a.card_id = c.id
                                   AND t2.approved_at BETWEEN a.period_start AND a.period_end ORDER BY a.period_start LIMIT 1)
                               ELSE c.holder_employee_id END AS emp
                          FROM fcd.txn t2 JOIN fcd.card c ON c.id = t2.card_id WHERE t2.id = ?) x
                 WHERE t.id = x.id""", txnId);
    }

    /** 원거래보다 먼저 온 고아 취소를 새 거래에 재매칭(BR-FCD-007) */
    public void retryOrphans(UUID txnId) {
        Map<String, Object> t = jdbc.queryForMap("SELECT card_id, approval_no FROM fcd.txn WHERE id = ?", txnId);
        String appr = (String) t.get("approval_no");
        if (appr == null) {
            return;
        }
        List<SourceRow> orphans = jdbc.query("""
                SELECT id, txn_id, batch_id, kind, match_method, canonical::text AS c FROM fcd.txn_source
                 WHERE txn_id IS NULL AND match_method = 'ORPHAN' AND NOT detached
                   AND canonical->>'cardId' = ? AND canonical->>'approvalNo' = ? ORDER BY seq""",
                (rs, i) -> new SourceRow(rs.getObject("id", UUID.class), null, rs.getObject("batch_id", UUID.class),
                        rs.getString("kind"), rs.getString("match_method"), canonical(rs.getString("c"))),
                t.get("card_id").toString(), appr);
        for (SourceRow o : orphans) {
            jdbc.update("UPDATE fcd.txn_source SET txn_id = ?, match_method = 'CANCEL', cancel_key = ? WHERE id = ?",
                    txnId, DedupEngine.cancelKey(o.row()), o.id());
        }
        if (!orphans.isEmpty()) {
            recompute(txnId);
        }
    }

    // ------------------------------------------------------------------ 가맹점 해석

    /** 사업자번호 → 정규화명 정확 일치(유일) → Jaro-Winkler ≥ 0.95 → 신규(UNKNOWN · PG). 배치 1건 동안 메모리 캐시 */
    public final class MerchantResolver {
        private final Map<String, UUID> byBiz = new HashMap<>();
        private final Map<String, List<UUID>> byNorm = new HashMap<>();
        private final Map<UUID, Boolean> pg = new HashMap<>();

        MerchantResolver() {
            jdbc.query("SELECT id, biz_reg_no, name_raw, is_pg FROM fcd.merchant ORDER BY first_seen_at, id", rs -> {
                UUID id = rs.getObject("id", UUID.class);
                if (rs.getString("biz_reg_no") != null) {
                    byBiz.put(rs.getString("biz_reg_no"), id);
                }
                byNorm.computeIfAbsent(MerchantText.normalize(rs.getString("name_raw"), false), k -> new ArrayList<>()).add(id);
                pg.put(id, rs.getBoolean("is_pg"));
            });
        }

        public Map.Entry<UUID, Boolean> resolve(String name, String biz, String hint) {
            if (biz != null && byBiz.containsKey(biz)) {
                UUID id = byBiz.get(biz);
                return Map.entry(id, pg.get(id));
            }
            String n = MerchantText.normalize(name, false);
            if (byNorm.containsKey(n) && byNorm.get(n).size() == 1) {
                UUID id = byNorm.get(n).get(0);
                return Map.entry(id, pg.get(id));
            }
            UUID best = null;
            double bs = 0;
            for (var e : byNorm.entrySet()) {
                if (Math.abs(e.getKey().length() - n.length()) > 4) {
                    continue;
                }
                double s = MerchantText.jaroWinkler(n, e.getKey());
                if (s > bs) {
                    best = e.getValue().get(0);
                    bs = s;
                }
            }
            if (best != null && bs >= 0.95) {
                return Map.entry(best, pg.get(best));
            }
            boolean isPg = MerchantText.isPg(biz, name);
            UUID id = UuidV7.next();
            jdbc.update("""
                    INSERT INTO fcd.merchant(id, biz_reg_no, name_raw, name_norm, card_category_name, internal_category, is_pg)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", id, biz, name, MerchantText.normalize(name, true), hint,
                    isPg ? "PG" : "UNKNOWN", isPg);
            if (biz != null) {
                byBiz.put(biz, id);
            }
            byNorm.computeIfAbsent(n, k -> new ArrayList<>()).add(id);
            pg.put(id, isPg);
            return Map.entry(id, isPg);
        }
    }

    public MerchantResolver merchantResolver() {
        return new MerchantResolver();
    }

    public Optional<UUID> cardHolder(UUID cardId, LocalDateTime at) {
        List<UUID> l = jdbc.query("""
                SELECT CASE WHEN c.card_type = 'SHARED' THEN
                         (SELECT a.employee_id FROM fcd.card_assignment a WHERE a.card_id = c.id
                           AND ? BETWEEN a.period_start AND a.period_end ORDER BY a.period_start LIMIT 1)
                       ELSE c.holder_employee_id END AS emp FROM fcd.card c WHERE c.id = ?""",
                (rs, i) -> rs.getObject("emp", UUID.class), Timestamp.valueOf(at), cardId);
        return l.isEmpty() ? Optional.empty() : Optional.ofNullable(l.get(0));
    }
}
