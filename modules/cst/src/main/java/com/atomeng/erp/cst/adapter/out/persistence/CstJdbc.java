package com.atomeng.erp.cst.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * cst 스키마 JDBC 접근. 원장(cost_ledger_entry)은 INSERT 만 한다(UPDATE·DELETE 는 DB 트리거가 거부).
 * 원장 쓰기 진입점은 application.CostLedgerService 하나(08-IMPL Step 6 DoD).
 */
@Repository
public class CstJdbc {

    private final JdbcTemplate jdbc;

    public CstJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static Date d(LocalDate v) {
        return v == null ? null : Date.valueOf(v);
    }

    private static LocalDate ld(ResultSet rs, String col) throws SQLException {
        Date v = rs.getDate(col);
        return v == null ? null : v.toLocalDate();
    }

    // ================================================================ 회계기간

    public void ensurePartition(String ym) {
        jdbc.queryForObject("SELECT cst.ensure_ledger_partition(?::char(6))::text", String.class, ym);
    }

    public Optional<String> periodStatus(String ym) {
        return jdbc.queryForList("SELECT status FROM cst.accounting_period WHERE period_ym = ?", String.class, ym)
                .stream().findFirst();
    }

    /** 마감(CLOSED) 회계기간 중 [fromYm, toYm] 에 드는 월(toYm null = 끝 없음) — CST-02 요율 소급 검사 */
    public List<String> closedPeriods(String fromYm, String toYm) {
        return jdbc.queryForList("""
                SELECT period_ym FROM cst.accounting_period
                 WHERE status = 'CLOSED' AND period_ym >= ? AND (?::text IS NULL OR period_ym <= ?)
                 ORDER BY period_ym""", String.class, fromYm, toYm, toYm);
    }

    public Optional<String> lockPeriod(String ym) {
        return jdbc.queryForList("SELECT status FROM cst.accounting_period WHERE period_ym = ? FOR UPDATE",
                String.class, ym).stream().findFirst();
    }

    public record PeriodRow(String periodYm, String status, java.sql.Timestamp closedAt, java.sql.Timestamp hrqClosedAt,
                            String reopenReason) {}

    public Optional<PeriodRow> period(String ym) {
        return jdbc.query("SELECT * FROM cst.accounting_period WHERE period_ym = ?", (rs, i) -> new PeriodRow(
                rs.getString("period_ym"), rs.getString("status"), rs.getTimestamp("closed_at"),
                rs.getTimestamp("hrq_closed_at"), rs.getString("reopen_reason")), ym).stream().findFirst();
    }

    public void closePeriod(String ym, UUID by) {
        jdbc.update("UPDATE cst.accounting_period SET status = 'CLOSED', closed_by = ?, closed_at = now() "
                + "WHERE period_ym = ?", by, ym);
    }

    public void reopenPeriod(String ym, String reason, UUID by) {
        jdbc.update("UPDATE cst.accounting_period SET status = 'REOPENED', reopened_by = ?, reopened_at = now(), "
                + "reopen_reason = ? WHERE period_ym = ?", by, reason, ym);
    }

    public void markHrqClosed(String ym, UUID eventId) {
        jdbc.update("UPDATE cst.accounting_period SET hrq_closed_at = now(), hrq_close_event_id = ? WHERE period_ym = ?",
                eventId, ym);
    }

    // ================================================================ 원장

    public record LedgerRow(UUID id, UUID projectId, UUID wbsId, LocalDate postingDate, String periodYm,
                            String sourceType, UUID sourceId, String costElement, UUID employeeId, String gradeCode,
                            String workType, BigDecimal hours, BigDecimal qty, BigDecimal amountKrw,
                            BigDecimal amountStdKrw, String origCurrency, BigDecimal origAmount, BigDecimal fxRate,
                            UUID reversalOf, String sourceRef, List<String> flags, UUID createdBy) {
        public LedgerRow {
            flags = flags == null ? List.of() : List.copyOf(flags);
        }
    }

    /** 멱등 INSERT (ux_ledger_idem 충돌 시 0행) → 기록된 id */
    public Optional<UUID> insertLedger(LedgerRow r) {
        return jdbc.queryForList("""
                INSERT INTO cst.cost_ledger_entry(id, project_id, wbs_id, posting_date, period_ym, source_type, source_id,
                       cost_element, employee_id, grade_code, work_type, hours, qty, amount_krw, amount_std_krw,
                       orig_currency, orig_amount, fx_rate, reversal_of, source_ref, flags, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS cst.cost_element), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        coalesce(string_to_array(?, ','), '{}'::text[]), ?)
                ON CONFLICT DO NOTHING
                RETURNING id""", UUID.class,
                r.id(), r.projectId(), r.wbsId(), d(r.postingDate()), r.periodYm(), r.sourceType(), r.sourceId(),
                r.costElement(), r.employeeId(), r.gradeCode(), r.workType(), r.hours(), r.qty(), r.amountKrw(),
                r.amountStdKrw(), r.origCurrency(), r.origAmount(), r.fxRate(), r.reversalOf(), r.sourceRef(),
                r.flags().isEmpty() ? null : String.join(",", r.flags()), r.createdBy()).stream().findFirst();
    }

    private LedgerRow ledger(ResultSet rs, int i) throws SQLException {
        Array flags = rs.getArray("flags");
        List<String> fl = flags == null ? List.of() : Arrays.asList((String[]) flags.getArray());
        return new LedgerRow(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("wbs_id", UUID.class), ld(rs, "posting_date"), rs.getString("period_ym"),
                rs.getString("source_type"), rs.getObject("source_id", UUID.class), rs.getString("cost_element"),
                rs.getObject("employee_id", UUID.class), rs.getString("grade_code"), rs.getString("work_type"),
                rs.getBigDecimal("hours"), rs.getBigDecimal("qty"), rs.getBigDecimal("amount_krw"),
                rs.getBigDecimal("amount_std_krw"), rs.getString("orig_currency"), rs.getBigDecimal("orig_amount"),
                rs.getBigDecimal("fx_rate"), rs.getObject("reversal_of", UUID.class), rs.getString("source_ref"),
                fl, rs.getObject("created_by", UUID.class));
    }

    /** 원천(source_type ≠ REVERSAL)으로 전기된 행 — 월과 무관하게 최초 1건 */
    public Optional<LedgerRow> findPosted(String sourceType, UUID sourceId) {
        return jdbc.query("SELECT * FROM cst.cost_ledger_entry WHERE source_type = ? AND source_id = ? "
                + "ORDER BY created_at, id LIMIT 1", this::ledger, sourceType, sourceId).stream().findFirst();
    }

    public boolean isReversed(UUID entryId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM cst.cost_ledger_entry WHERE reversal_of = ?)", Boolean.class, entryId));
    }

    public List<LedgerRow> projectLedger(UUID projectId, LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT * FROM cst.cost_ledger_entry WHERE project_id = ?
                   AND (?::date IS NULL OR posting_date >= ?) AND (?::date IS NULL OR posting_date <= ?)
                 ORDER BY posting_date, created_at, id""", this::ledger, projectId, d(from), d(from), d(to), d(to));
    }

    public List<Map<String, Object>> periodTotals(UUID projectId) {
        return jdbc.queryForList("""
                SELECT period_ym, sum(amount_krw) AS amount_krw, sum(amount_std_krw) AS amount_std_krw,
                       coalesce(sum(hours), 0) AS hours, count(*) AS row_count
                  FROM cst.cost_ledger_entry WHERE project_id = ? GROUP BY period_ym ORDER BY period_ym""", projectId);
    }

    /**
     * 대사용 인건비 그룹(BR-HRQ-013): 해당 월 TIMESHEET 행 + 같은 월에 원본이 있는 REVERSAL 행.
     * 다른 월 원본의 조정 역분개(마감 후 adjustment)는 원 월 대사에 이미 반영된 리비전이므로 제외한다 —
     * HRQ 집계가 posting_period 의 현재 리비전 기준이기 때문.
     */
    public List<Map<String, Object>> laborGroupsForReconciliation(String ym) {
        return jdbc.queryForList("""
                SELECT l.project_id, l.employee_id, coalesce(l.grade_code, 'UNGRADED') AS grade, l.work_type,
                       sum(l.hours) AS hours
                  FROM cst.cost_ledger_entry l
                 WHERE l.period_ym = ? AND l.cost_element = 'LABOR'
                   AND (l.source_type = 'TIMESHEET'
                        OR (l.source_type = 'REVERSAL' AND EXISTS (
                              SELECT 1 FROM cst.cost_ledger_entry o WHERE o.id = l.reversal_of AND o.period_ym = l.period_ym)))
                 GROUP BY l.project_id, l.employee_id, coalesce(l.grade_code, 'UNGRADED'), l.work_type""", ym);
    }

    public Optional<UUID> wbsIdOf(UUID projectId, String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT id FROM cst.wbs_node WHERE project_id = ? AND code = ?", UUID.class,
                projectId, code).stream().findFirst();
    }

    // ================================================================ 요율

    /** 원장 · 예산이 쓰는 유효 요율 — 승인(APPROVED)된 행만 */
    public Optional<BigDecimal> effectiveRate(String kind, String key, LocalDate asOf) {
        return jdbc.queryForList("""
                SELECT value FROM cst.rate_table WHERE rate_kind = ? AND key_code = ? AND status = 'APPROVED'
                   AND valid_from <= ? AND (valid_to IS NULL OR valid_to > ?)""", BigDecimal.class,
                kind, key, d(asOf), d(asOf)).stream().findFirst();
    }

    /** 기준 요율 행. status = DRAFT(ADMIN 등록 · 수정) → APPROVED(EXEC 승인, 등록자 ≠ 승인자) | REJECTED */
    public record RateRow(UUID id, String rateKind, String keyCode, BigDecimal value, LocalDate validFrom,
                          LocalDate validTo, String sourceRef, long version, String status, UUID createdBy,
                          java.time.Instant createdAt, UUID approvedBy, java.time.Instant approvedAt,
                          String rejectReason, java.time.Instant retiredAt) {}

    private RateRow rate(ResultSet rs, int i) throws SQLException {
        return new RateRow(rs.getObject("id", UUID.class), rs.getString("rate_kind"), rs.getString("key_code"),
                rs.getBigDecimal("value"), ld(rs, "valid_from"), ld(rs, "valid_to"), rs.getString("source_ref"),
                rs.getLong("version"), rs.getString("status"), rs.getObject("created_by", UUID.class),
                instant(rs, "created_at"), rs.getObject("approved_by", UUID.class), instant(rs, "approved_at"),
                rs.getString("reject_reason"), instant(rs, "retired_at"));
    }

    private static java.time.Instant instant(ResultSet rs, String col) throws SQLException {
        var t = rs.getTimestamp(col);
        return t == null ? null : t.toInstant();
    }

    public List<RateRow> rates(String kind) {
        return jdbc.query("SELECT * FROM cst.rate_table WHERE (?::text IS NULL OR rate_kind = ?) "
                + "ORDER BY rate_kind, key_code, valid_from, created_at", this::rate, kind, kind);
    }

    public Optional<RateRow> findRate(UUID id) {
        return jdbc.query("SELECT * FROM cst.rate_table WHERE id = ?", this::rate, id).stream().findFirst();
    }

    public UUID rateUpdatedBy(UUID id) {
        return jdbc.queryForList("SELECT updated_by FROM cst.rate_table WHERE id = ?", UUID.class, id).stream()
                .filter(Objects::nonNull).findFirst().orElse(null);
    }

    public Optional<RateRow> lockRate(UUID id) {
        return jdbc.query("SELECT * FROM cst.rate_table WHERE id = ? FOR UPDATE", this::rate, id).stream().findFirst();
    }

    public Optional<RateRow> lockOpenEndedRate(String kind, String key) {
        return jdbc.query("SELECT * FROM cst.rate_table WHERE rate_kind = ? AND key_code = ? AND valid_to IS NULL "
                + "AND status = 'APPROVED' FOR UPDATE", this::rate, kind, key).stream().findFirst();
    }

    /** [from, to) 와 겹치는 APPROVED 행(자기 자신 · except 제외) */
    public List<RateRow> overlappingApproved(String kind, String key, LocalDate from, LocalDate to, UUID except) {
        return jdbc.query("""
                SELECT * FROM cst.rate_table WHERE rate_kind = ? AND key_code = ? AND status = 'APPROVED'
                   AND (?::uuid IS NULL OR id <> ?::uuid)
                   AND daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[)')
                       && daterange(?::date, coalesce(?::date, 'infinity'::date), '[)')
                 ORDER BY valid_from""", this::rate, kind, key, except, except, d(from), d(to));
    }

    public boolean draftExists(String kind, String key, UUID except) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM cst.rate_table WHERE rate_kind = ? AND key_code = ? AND status = 'DRAFT'
                                 AND (?::uuid IS NULL OR id <> ?::uuid))""", Boolean.class, kind, key, except, except));
    }

    public void closeRate(UUID id, LocalDate validTo, UUID by) {
        jdbc.update("UPDATE cst.rate_table SET valid_to = ?, updated_at = now(), updated_by = ?, version = version + 1 "
                + "WHERE id = ?", d(validTo), by, id);
    }

    public void retireRate(UUID id, LocalDate validTo, UUID by) {
        jdbc.update("UPDATE cst.rate_table SET valid_to = ?, retired_at = now(), retired_by = ?, updated_at = now(), "
                + "updated_by = ?, version = version + 1 WHERE id = ?", d(validTo), by, by, id);
    }

    public void updateDraft(UUID id, BigDecimal value, LocalDate validFrom, LocalDate validTo, String sourceRef, UUID by) {
        jdbc.update("""
                UPDATE cst.rate_table SET value = ?, valid_from = ?, valid_to = ?, source_ref = ?, updated_at = now(),
                       updated_by = ?, version = version + 1 WHERE id = ? AND status = 'DRAFT'""",
                value, d(validFrom), d(validTo), sourceRef, by, id);
    }

    public void approveRate(UUID id, UUID by) {
        jdbc.update("UPDATE cst.rate_table SET status = 'APPROVED', approved_by = ?, approved_at = now(), "
                + "updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ? AND status = 'DRAFT'", by, by, id);
    }

    public void rejectRate(UUID id, String reason, UUID by) {
        jdbc.update("UPDATE cst.rate_table SET status = 'REJECTED', reject_reason = ?, updated_at = now(), "
                + "updated_by = ?, version = version + 1 WHERE id = ? AND status = 'DRAFT'", reason, by, id);
    }

    public void insertRate(RateRow r, UUID by) {
        jdbc.update("""
                INSERT INTO cst.rate_table(id, rate_kind, key_code, value, valid_from, valid_to, source_ref, created_by,
                                           status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", r.id(), r.rateKind(), r.keyCode(), r.value(), d(r.validFrom()),
                d(r.validTo()), r.sourceRef(), by, r.status());
    }

    public Optional<BigDecimal> realDailyCost(UUID employeeId, String ym) {
        return jdbc.queryForList("SELECT daily_cost_krw FROM cst.employee_cost_rate WHERE employee_id = ? AND period_ym = ?",
                BigDecimal.class, employeeId, ym).stream().findFirst();
    }

    // ================================================================ 대사·전표

    /** @return false = 같은 eventId 또는 같은 마감 내용(payload 해시)이 이미 대사됨 */
    public boolean insertReconciliation(UUID id, String ym, UUID eventId, String sourceHash, BigDecimal hrq,
                                        BigDecimal cst, BigDecimal diff, String status, String detailJson) {
        return jdbc.update("""
                INSERT INTO cst.manday_reconciliation(id, period_ym, source_event_id, source_hash, hrq_total_mandays,
                       cst_total_mandays, diff_mandays, status, detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING""", id, ym, eventId, sourceHash, hrq, cst, diff, status, detailJson) == 1;
    }

    /** 웹 목록: 회계기간 + 마지막 인·일 대사 결과(읽기 전용) */
    public List<Map<String, Object>> periods(String toYm, int limit) {
        return jdbc.queryForList("""
                SELECT p.period_ym, p.status, p.closed_at, p.closed_by, p.reopened_at, p.reopened_by, p.reopen_reason,
                       p.hrq_closed_at, r.status AS recon_status, r.diff_mandays, r.hrq_total_mandays, r.cst_total_mandays,
                       r.created_at AS reconciled_at
                  FROM cst.accounting_period p
                  LEFT JOIN LATERAL (SELECT status, diff_mandays, hrq_total_mandays, cst_total_mandays, created_at
                                       FROM cst.manday_reconciliation m WHERE m.period_ym = p.period_ym
                                      ORDER BY created_at DESC LIMIT 1) r ON true
                 WHERE p.period_ym <= ? ORDER BY p.period_ym DESC LIMIT ?""", toYm, limit);
    }

    /** 웹 목록: 한 회계월의 인·일 대사 실행 이력(최근부터) */
    public List<Map<String, Object>> reconciliations(String ym) {
        return jdbc.queryForList("""
                SELECT id, period_ym, source_event_id, hrq_total_mandays, cst_total_mandays, diff_mandays, status,
                       detail::text AS detail, created_at
                  FROM cst.manday_reconciliation WHERE period_ym = ? ORDER BY created_at DESC""", ym);
    }

    /** 웹 목록: EVM 스냅샷 추이(as_of 오름차순) */
    public List<Map<String, Object>> evmSnapshots(UUID projectId, LocalDate from, LocalDate to) {
        return jdbc.queryForList("""
                SELECT as_of, bac, pv, ev, ac, ac_std, cv, sv, cpi, spi, eac, etc, vac, tcpi, alert_level, computed_at
                  FROM cst.evm_snapshot WHERE project_id = ? AND (?::date IS NULL OR as_of >= ?::date)
                   AND (?::date IS NULL OR as_of <= ?::date) ORDER BY as_of LIMIT 400""",
                projectId, d(from), d(from), d(to), d(to));
    }

    public Optional<Map<String, Object>> latestReconciliation(String ym) {
        return jdbc.queryForList("""
                SELECT id, period_ym, source_event_id, hrq_total_mandays, cst_total_mandays, diff_mandays, status,
                       detail::text AS detail, created_at
                  FROM cst.manday_reconciliation WHERE period_ym = ? ORDER BY created_at DESC LIMIT 1""", ym)
                .stream().findFirst();
    }

    public void insertVoucherSync(UUID id, UUID reportId, List<UUID> lineIds, String kind, String ym, String docNo,
                                  String status, String error) {
        jdbc.update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO cst.voucher_sync(id, expense_report_id, line_ids, kind, period_ym, douzone_doc_no, status, error)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""");
            ps.setObject(1, id);
            ps.setObject(2, reportId);
            ps.setArray(3, con.createArrayOf("uuid", lineIds.toArray()));
            ps.setString(4, kind);
            ps.setString(5, ym);
            ps.setString(6, docNo);
            ps.setString(7, status);
            ps.setString(8, error);
            return ps;
        });
    }

    // ================================================================ EVM

    public record BudgetHead(UUID revisionId, int revNo, BigDecimal bacKrw, BigDecimal contractAmountKrw) {}

    public Optional<BudgetHead> approvedBudget(UUID projectId) {
        return jdbc.query("""
                SELECT id, rev_no, bac_krw, contract_amount_krw FROM cst.budget_revision
                 WHERE project_id = ? AND status = 'APPROVED' AND deleted_at IS NULL""",
                (rs, i) -> new BudgetHead(rs.getObject("id", UUID.class), rs.getInt("rev_no"),
                        rs.getBigDecimal("bac_krw"), rs.getBigDecimal("contract_amount_krw")), projectId)
                .stream().findFirst();
    }

    public record LineRow(UUID wbsId, BigDecimal amount, LocalDate plannedStart, LocalDate plannedEnd, String curve) {}

    public List<LineRow> budgetLines(UUID revisionId) {
        return jdbc.query("SELECT wbs_id, amount_krw, planned_start, planned_end, pv_curve FROM cst.budget_line "
                        + "WHERE revision_id = ? ORDER BY id",
                (rs, i) -> new LineRow(rs.getObject("wbs_id", UUID.class), rs.getBigDecimal("amount_krw"),
                        ld(rs, "planned_start"), ld(rs, "planned_end"), rs.getString("pv_curve")), revisionId);
    }

    public record WbsRow(UUID id, String code, UUID projectId) {}

    public List<WbsRow> wbsNodes(UUID projectId) {
        return jdbc.query("SELECT id, code, project_id FROM cst.wbs_node WHERE project_id = ? ORDER BY sort_order, code",
                (rs, i) -> new WbsRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getObject("project_id", UUID.class)), projectId);
    }

    public Optional<WbsRow> wbs(UUID wbsId) {
        return jdbc.query("SELECT id, code, project_id FROM cst.wbs_node WHERE id = ?",
                (rs, i) -> new WbsRow(rs.getObject("id", UUID.class), rs.getString("code"),
                        rs.getObject("project_id", UUID.class)), wbsId).stream().findFirst();
    }

    /** asOf 시점 WBS 최신 진척률 (같은 일자 다건이면 마지막 입력) */
    public Optional<BigDecimal> latestProgress(UUID wbsId, LocalDate asOf) {
        return jdbc.queryForList("""
                SELECT pct_complete FROM cst.progress_record WHERE wbs_id = ? AND as_of <= ?
                 ORDER BY as_of DESC, created_at DESC, id DESC LIMIT 1""", BigDecimal.class, wbsId, d(asOf))
                .stream().findFirst();
    }

    public void insertProgress(UUID id, UUID projectId, UUID wbsId, LocalDate asOf, BigDecimal pct, String source,
                               String sourceEvent, String reason, UUID by) {
        jdbc.update("""
                INSERT INTO cst.progress_record(id, project_id, wbs_id, as_of, pct_complete, source, source_event, reason,
                       created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, projectId, wbsId, d(asOf), pct, source, sourceEvent, reason, by);
    }

    /** posting_date ≤ asOf 원장 합: [실단가 합, 표준단가 합] (전체) */
    public BigDecimal[] actualCost(UUID projectId, LocalDate asOf) {
        return jdbc.queryForObject("""
                SELECT coalesce(sum(amount_krw), 0) AS a, coalesce(sum(amount_std_krw), 0) AS s
                  FROM cst.cost_ledger_entry WHERE project_id = ? AND posting_date <= ?""",
                (rs, i) -> new BigDecimal[] {rs.getBigDecimal("a"), rs.getBigDecimal("s")}, projectId, d(asOf));
    }

    /** WBS 별 원장 합 (wbs_id NULL 제외): wbsId → [실, 표준] */
    public Map<UUID, BigDecimal[]> actualCostByWbs(UUID projectId, LocalDate asOf) {
        Map<UUID, BigDecimal[]> out = new java.util.HashMap<>();
        jdbc.query("""
                SELECT wbs_id, coalesce(sum(amount_krw), 0) AS a, coalesce(sum(amount_std_krw), 0) AS s
                  FROM cst.cost_ledger_entry WHERE project_id = ? AND posting_date <= ? AND wbs_id IS NOT NULL
                 GROUP BY wbs_id""", rs -> {
            out.put(rs.getObject("wbs_id", UUID.class), new BigDecimal[] {rs.getBigDecimal("a"), rs.getBigDecimal("s")});
        }, projectId, d(asOf));
        return out;
    }

    public record SnapshotPoint(LocalDate asOf, BigDecimal cpi) {}

    public List<SnapshotPoint> snapshotsBetween(UUID projectId, LocalDate from, LocalDate toExclusive) {
        List<SnapshotPoint> out = new ArrayList<>();
        jdbc.query("SELECT as_of, cpi FROM cst.evm_snapshot WHERE project_id = ? AND as_of >= ? AND as_of < ? "
                        + "AND cpi IS NOT NULL ORDER BY as_of",
                rs -> {
                    out.add(new SnapshotPoint(ld(rs, "as_of"), rs.getBigDecimal("cpi")));
                }, projectId, d(from), d(toExclusive));
        return out;
    }

    public void upsertSnapshot(UUID projectId, LocalDate asOf, Map<String, BigDecimal> v, BigDecimal acStd,
                               String level, String alertsJson) {
        jdbc.update("""
                INSERT INTO cst.evm_snapshot(project_id, as_of, bac, pv, ev, ac, ac_std, cv, sv, cpi, spi, eac, etc, vac,
                       tcpi, alert_level, alerts, computed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, now())
                ON CONFLICT (project_id, as_of) DO UPDATE SET bac = EXCLUDED.bac, pv = EXCLUDED.pv, ev = EXCLUDED.ev,
                       ac = EXCLUDED.ac, ac_std = EXCLUDED.ac_std, cv = EXCLUDED.cv, sv = EXCLUDED.sv, cpi = EXCLUDED.cpi,
                       spi = EXCLUDED.spi, eac = EXCLUDED.eac, etc = EXCLUDED.etc, vac = EXCLUDED.vac,
                       tcpi = EXCLUDED.tcpi, alert_level = EXCLUDED.alert_level, alerts = EXCLUDED.alerts,
                       computed_at = now()""",
                projectId, d(asOf), v.get("bac"), v.get("pv"), v.get("ev"), v.get("ac"), acStd, v.get("cv"), v.get("sv"),
                v.get("cpi"), v.get("spi"), v.get("eac"), v.get("etc"), v.get("vac"), v.get("tcpi"), level, alertsJson);
    }
}
