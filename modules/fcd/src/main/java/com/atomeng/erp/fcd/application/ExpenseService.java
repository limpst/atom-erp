package com.atomeng.erp.fcd.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fcd.api.FcdEvents;
import com.atomeng.erp.fcd.api.FcdSubscriptionContracts.CstVoucherSynced;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 경비 정산 · 결재 · 전표 연동 (설계 B5.3 · B8.4 · BR-FCD-011/015/016, 12-IMPL Step 8 일부).
 * 정산 라인 → (결재) → {@code fcd.expense.posted}(라인별, CST 멱등키 lineId) → CST 원가 · 더존(스텁) →
 * {@code cst.voucher.synced} 수신 → 라인 SYNCED · 정산서 POSTED. 역분개는 {@code fcd.expense.reversed}.
 * <p>⚠️ 계정 · 부가세 공제 판단은 예시값(세무 확인 필요). 결재선은 core 결재 엔진 대신 FIN · EXEC 단일 승인(후속).
 */
@Service
public class ExpenseService {

    private static final Logger log = LoggerFactory.getLogger(ExpenseService.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 경비 계정(예시) → CST 원가요소 */
    public static final Map<String, String> COST_ELEMENT = Map.of(
            "TRAVEL", "TRAVEL", "LODGING", "LODGING", "VEHICLE", "VEHICLE", "MEAL", "OTHER", "ENTERTAINMENT", "OTHER",
            "SUPPLIES", "OTHER", "OTHER", "OTHER");

    public record LineInput(String accountCode, String purpose, UUID projectId, String wbsCode, BigDecimal supplyAmount,
                            BigDecimal vatAmount, boolean vatDeductible, Integer attendees) {}

    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final Clock clock;

    public ExpenseService(JdbcTemplate jdbc, OutboxPublisher outbox, AuditLog audit, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    /** 거래별 정산 입력 — 본인 거래만, 정산서는 (사용자, 거래월) 1개 */
    @Transactional
    public Map<String, Object> upsertLine(UUID txnId, UUID actor, LineInput in) {
        if (!COST_ELEMENT.containsKey(in.accountCode())) {
            throw new DomainException(FcdErrors.INVALID, "accountCode: " + COST_ELEMENT.keySet());
        }
        Map<String, Object> t = jdbc.query("SELECT employee_id, approved_at, amount_krw, cancelled_amount_krw FROM fcd.txn "
                        + "WHERE id = ? AND removed_at IS NULL",
                rs -> rs.next() ? Map.<String, Object>of("emp", String.valueOf(rs.getObject(1)), "at",
                        rs.getTimestamp(2).toLocalDateTime(), "net",
                        rs.getBigDecimal(3).subtract(rs.getBigDecimal(4))) : null, txnId);
        if (t == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "거래 없음: " + txnId);
        }
        if (!t.get("emp").equals(actor.toString())) {
            throw new DomainException(FcdErrors.NOT_OWNER, "본인 거래만 정산할 수 있습니다");
        }
        BigDecimal total = in.supplyAmount().add(in.vatAmount());
        if (total.compareTo((BigDecimal) t.get("net")) != 0) {
            throw new DomainException(FcdErrors.INVALID, "공급가 + 부가세 = 카드 순액(" + t.get("net") + ")이어야 합니다");
        }
        String period = ((java.time.LocalDateTime) t.get("at")).toLocalDate().toString().substring(0, 7);
        UUID report = jdbc.query("SELECT id FROM fcd.expense_report WHERE employee_id = ? AND period = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, actor, period);
        if (report == null) {
            report = UuidV7.next();
            jdbc.update("INSERT INTO fcd.expense_report(id, employee_id, period, status) VALUES (?, ?, ?, 'DRAFT')",
                    report, actor, period);
        }
        String st = jdbc.queryForObject("SELECT status FROM fcd.expense_report WHERE id = ?", String.class, report);
        if (!List.of("DRAFT", "RETURNED").contains(st)) {
            throw new DomainException(FcdErrors.STATE, "상신 후에는 수정할 수 없습니다(조정 라인으로 처리)");
        }
        jdbc.update("""
                INSERT INTO fcd.expense_line(id, report_id, txn_id, account_code, cost_element, purpose, project_id, wbs_code,
                                             supply_amount, vat_amount, vat_deductible, attendees)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (txn_id) DO UPDATE SET account_code = EXCLUDED.account_code, cost_element = EXCLUDED.cost_element,
                     purpose = EXCLUDED.purpose, project_id = EXCLUDED.project_id, wbs_code = EXCLUDED.wbs_code,
                     supply_amount = EXCLUDED.supply_amount, vat_amount = EXCLUDED.vat_amount,
                     vat_deductible = EXCLUDED.vat_deductible, attendees = EXCLUDED.attendees""",
                UuidV7.next(), report, txnId, in.accountCode(), COST_ELEMENT.get(in.accountCode()), in.purpose(),
                in.projectId(), in.wbsCode(), in.supplyAmount(), in.vatAmount(), in.vatDeductible(), in.attendees());
        if (in.projectId() != null) {
            jdbc.update("UPDATE fcd.txn SET project_id = ? WHERE id = ?", in.projectId(), txnId);
        }
        return Map.of("reportId", report, "period", period, "txnId", txnId);
    }

    /** 상신 — BR-FCD-011 미소명 · 기한초과 케이스가 있으면 거절(TC-FCD-026) */
    @Transactional
    public Map<String, Object> submit(UUID employee, String period, List<String> blockingCases) {
        UUID report = jdbc.query("SELECT id FROM fcd.expense_report WHERE employee_id = ? AND period = ? FOR UPDATE",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, employee, period);
        if (report == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "정산서 없음: " + period);
        }
        if (!blockingCases.isEmpty()) {
            throw new DomainException(FcdErrors.EXPLANATION_PENDING, "BR-FCD-011: 소명이 끝나지 않은 사용 " + blockingCases);
        }
        int n = jdbc.update("""
                UPDATE fcd.expense_report SET status = 'SUBMITTED', submitted_at = now(), version = version + 1
                 WHERE id = ? AND status IN ('DRAFT','RETURNED')
                   AND EXISTS (SELECT 1 FROM fcd.expense_line l WHERE l.report_id = expense_report.id)""", report);
        if (n == 0) {
            throw new DomainException(FcdErrors.STATE, "상신할 수 없는 정산서(상태 또는 라인 없음)");
        }
        return Map.of("reportId", report, "status", "SUBMITTED");
    }

    /** 결재(FIN · EXEC) — 본인 정산서 결재 금지 */
    @Transactional
    public Map<String, Object> approve(UUID report, UUID approver) {
        Map<String, Object> r = jdbc.queryForMap("SELECT employee_id, status FROM fcd.expense_report WHERE id = ? FOR UPDATE",
                report);
        if (approver.equals(r.get("employee_id"))) {
            throw new DomainException(FcdErrors.CONFLICT_OF_INTEREST, "본인 정산서는 결재할 수 없습니다");
        }
        if (!"SUBMITTED".equals(r.get("status"))) {
            throw new DomainException(FcdErrors.STATE, "상신된 정산서만 결재할 수 있습니다");
        }
        jdbc.update("UPDATE fcd.expense_report SET status = 'APPROVED', approved_by = ?, approved_at = now(), "
                + "version = version + 1 WHERE id = ?", approver, report);
        audit.append("FCD", AuditLog.Action.APPROVE, "expense_report", report, r, Map.of("status", "APPROVED"));
        return Map.of("reportId", report, "status", "APPROVED");
    }

    /**
     * 전표 생성 요청 — 라인별 {@code fcd.expense.posted}. BR-FCD-016: 미판정 케이스 거래가 있으면 거절,
     * 부당 판정 거래는 비용 전표에서 제외(환수 대상). 원가 = 공제 시 공급가 · 불공제 시 합계.
     */
    @Transactional
    public Map<String, Object> post(UUID report, UUID actor) {
        Map<String, Object> r = jdbc.queryForMap("SELECT employee_id, status FROM fcd.expense_report WHERE id = ? FOR UPDATE",
                report);
        if (!"APPROVED".equals(r.get("status"))) {
            throw new DomainException(FcdErrors.STATE, "결재 완료 정산서만 전표 생성할 수 있습니다");
        }
        List<String> unresolved = jdbc.queryForList("""
                SELECT c.case_no FROM fcd.expense_line l JOIN fcd.case_txn ct ON ct.txn_id = l.txn_id
                  JOIN fcd.fcd_case c ON c.id = ct.case_id
                 WHERE l.report_id = ? AND c.status IN ('OPEN','EXPLANATION_REQUESTED','OVERDUE','SUBMITTED',
                                                         'SUPPLEMENT_REQUESTED','ESCALATED')""", String.class, report);
        if (!unresolved.isEmpty()) {
            throw new DomainException(FcdErrors.CASE_UNRESOLVED, "BR-FCD-016: 판정 전 케이스 " + unresolved);
        }
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT l.id, l.txn_id, l.project_id, l.wbs_code, l.cost_element, l.supply_amount, l.vat_amount,
                       l.vat_deductible, t.approved_at,
                       EXISTS (SELECT 1 FROM fcd.case_txn ct JOIN fcd.fcd_case c ON c.id = ct.case_id
                                WHERE ct.txn_id = l.txn_id AND c.label IN ('MISUSE','MINOR_VIOLATION')) AS misuse
                  FROM fcd.expense_line l JOIN fcd.txn t ON t.id = l.txn_id
                 WHERE l.report_id = ? AND l.sync_status = 'PENDING' ORDER BY t.approved_at""", report);
        List<UUID> posted = new ArrayList<>();
        List<UUID> skipped = new ArrayList<>();
        for (Map<String, Object> l : lines) {
            UUID lineId = (UUID) l.get("id");
            if ((Boolean) l.get("misuse")) {
                skipped.add(lineId);
                continue;
            }
            if (l.get("project_id") == null) {
                throw new DomainException(FcdErrors.INVALID, "프로젝트 귀속 없는 경비 라인은 이번 단계에서 전기 미지원: " + lineId);
            }
            BigDecimal supply = (BigDecimal) l.get("supply_amount");
            BigDecimal vat = (BigDecimal) l.get("vat_amount");
            boolean deductible = (Boolean) l.get("vat_deductible");
            outbox.publish(new FcdEvents.ExpensePosted(report, lineId, (UUID) l.get("project_id"), (String) l.get("wbs_code"),
                    (String) l.get("cost_element"), deductible ? supply : supply.add(vat), deductible ? vat : BigDecimal.ZERO,
                    ((Timestamp) l.get("approved_at")).toLocalDateTime().toLocalDate(), "fcd:" + l.get("txn_id"),
                    (UUID) r.get("employee_id")));
            jdbc.update("UPDATE fcd.expense_line SET sync_status = 'POSTED' WHERE id = ?", lineId);
            posted.add(lineId);
        }
        audit.append("FCD", AuditLog.Action.UPDATE, "expense_report", report, null,
                Map.of("posted", posted.size(), "skippedMisuse", skipped.size()));
        return Map.of("reportId", report, "postedLines", posted, "skippedMisuseLines", skipped);
    }

    /** 부당 판정 등으로 이미 전기된 라인 역분개 요청 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reverseForTxn(UUID txnId, String reason) {
        List<Map<String, Object>> l = jdbc.queryForList("""
                SELECT id, report_id FROM fcd.expense_line WHERE txn_id = ? AND sync_status IN ('POSTED','SYNCED')""", txnId);
        for (Map<String, Object> x : l) {
            outbox.publish(new FcdEvents.ExpenseReversed((UUID) x.get("report_id"), (UUID) x.get("id"), reason,
                    LocalDate.now(clock.withZone(SEOUL))));
            jdbc.update("UPDATE fcd.expense_line SET sync_status = 'REVERSE_PENDING' WHERE id = ?", x.get("id"));
        }
    }

    /** cst.voucher.synced 수신 — 라인 동기화 상태 · 전표번호, 모두 SYNCED 면 정산서 POSTED */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onVoucherSynced(CstVoucherSynced e) {
        boolean ok = "SYNCED".equals(e.status());
        for (UUID line : e.lineIds()) {
            String next = "REVERSED".equals(e.kind()) ? (ok ? "REVERSED" : "FAILED") : (ok ? "SYNCED" : "FAILED");
            int n = jdbc.update("UPDATE fcd.expense_line SET sync_status = ?, voucher_no = coalesce(?, voucher_no), "
                    + "synced_at = now() WHERE id = ? AND report_id = ?", next, e.douzoneDocNo(), line, e.expenseReportId());
            if (n == 0) {
                log.warn("cst.voucher.synced: 알 수 없는 라인 {} (report {}) — 무시", line, e.expenseReportId());
            }
        }
        jdbc.update("""
                UPDATE fcd.expense_report r SET status = 'POSTED', posted_at = now(), version = version + 1
                 WHERE r.id = ? AND r.status = 'APPROVED'
                   AND NOT EXISTS (SELECT 1 FROM fcd.expense_line l WHERE l.report_id = r.id
                                    AND l.sync_status NOT IN ('SYNCED'))""", e.expenseReportId());
        jdbc.update("""
                UPDATE fcd.expense_report r SET status = 'REVERSED', version = version + 1
                 WHERE r.id = ? AND r.status = 'POSTED'
                   AND NOT EXISTS (SELECT 1 FROM fcd.expense_line l WHERE l.report_id = r.id
                                    AND l.sync_status NOT IN ('REVERSED'))""", e.expenseReportId());
    }

    public Map<String, Object> report(UUID id) {
        Map<String, Object> r = new java.util.LinkedHashMap<>(jdbc.queryForMap(
                "SELECT id, employee_id, period, status, approved_by, posted_at FROM fcd.expense_report WHERE id = ?", id));
        r.put("lines", jdbc.queryForList("""
                SELECT id, txn_id, account_code, cost_element, project_id, supply_amount, vat_amount, vat_deductible,
                       sync_status, voucher_no FROM fcd.expense_line WHERE report_id = ? ORDER BY id""", id));
        return r;
    }
}
