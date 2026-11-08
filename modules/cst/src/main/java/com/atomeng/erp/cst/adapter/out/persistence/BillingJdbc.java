package com.atomeng.erp.cst.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * cst 청구 · 수금 · 대사 JDBC 접근 (V0052). bank_receipt · receipt_allocation 은 INSERT 만 한다(UPDATE·DELETE 는 DB 트리거가 거부).
 * 쓰기 진입점은 application.BillingService 하나.
 */
@Repository
public class BillingJdbc {

    private final JdbcTemplate jdbc;

    public BillingJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record InvoiceRow(UUID id, String invoiceNo, UUID projectId, UUID clientId, LocalDate issueDate,
                             LocalDate dueDate, BigDecimal supplyKrw, BigDecimal vatKrw, BigDecimal totalKrw,
                             String status, String taxInvoiceRef, String memo, long version) {}

    public record ReceiptRow(UUID id, String bankAccount, LocalDate valueDate, BigDecimal amountKrw, String payerText,
                             String statementKey) {}

    public record ProposalRow(UUID id, UUID invoiceId, UUID receiptId, BigDecimal amountKrw, int matchScore,
                              String status, UUID proposedBy, UUID decidedBy, String rejectReason, UUID allocationId) {}

    public record AllocationRow(UUID id, UUID invoiceId, UUID receiptId, BigDecimal amountKrw, UUID reversalOf,
                                Integer matchScore, UUID proposedBy, UUID confirmedBy, String reason) {}

    public record OpenItem(UUID invoiceId, String invoiceNo, UUID projectId, UUID clientId, String clientName,
                           LocalDate issueDate, BigDecimal supplyKrw, BigDecimal totalKrw, BigDecimal allocatedKrw,
                           BigDecimal openKrw, int ageDays, String bucket) {}

    public record AgingRow(String bucket, long invoiceCount, BigDecimal openKrw) {}

    /** 아직 다 배분되지 않은 입금 */
    public record FreeReceipt(UUID id, String bankAccount, LocalDate valueDate, BigDecimal amountKrw, String payerText,
                              BigDecimal remainingKrw) {}

    private static Date d(LocalDate v) {
        return v == null ? null : Date.valueOf(v);
    }

    private static LocalDate ld(ResultSet rs, String col) throws SQLException {
        Date v = rs.getDate(col);
        return v == null ? null : v.toLocalDate();
    }

    // ================================================================ 청구

    public String nextInvoiceNo(int year) {
        Long n = jdbc.queryForObject("SELECT nextval('cst.invoice_no_seq')", Long.class);
        return "INV-%d-%06d".formatted(year, n);
    }

    public void insertInvoice(InvoiceRow r, UUID by) {
        jdbc.update("INSERT INTO cst.invoice(id, invoice_no, project_id, client_id, issue_date, due_date, supply_krw, "
                        + "vat_krw, tax_invoice_ref, memo, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.invoiceNo(), r.projectId(), r.clientId(), d(r.issueDate()), d(r.dueDate()), r.supplyKrw(),
                r.vatKrw(), r.taxInvoiceRef(), r.memo(), by);
    }

    private static InvoiceRow invoice(ResultSet rs, int i) throws SQLException {
        return new InvoiceRow(rs.getObject("id", UUID.class), rs.getString("invoice_no"),
                rs.getObject("project_id", UUID.class), rs.getObject("client_id", UUID.class), ld(rs, "issue_date"),
                ld(rs, "due_date"), rs.getBigDecimal("supply_krw"), rs.getBigDecimal("vat_krw"),
                rs.getBigDecimal("total_krw"), rs.getString("status"), rs.getString("tax_invoice_ref"),
                rs.getString("memo"), rs.getLong("version"));
    }

    public Optional<InvoiceRow> invoice(UUID id) {
        return jdbc.query("SELECT * FROM cst.invoice WHERE id = ?", BillingJdbc::invoice, id).stream().findFirst();
    }

    public List<InvoiceRow> invoicesOfProject(UUID projectId) {
        return jdbc.query("SELECT * FROM cst.invoice WHERE project_id = ? ORDER BY issue_date, invoice_no",
                BillingJdbc::invoice, projectId);
    }

    public BigDecimal netAllocatedToInvoice(UUID invoiceId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_krw), 0) FROM cst.receipt_allocation WHERE invoice_id = ?",
                BigDecimal.class, invoiceId);
    }

    public int voidInvoice(UUID id, String memo, UUID by) {
        return jdbc.update("UPDATE cst.invoice SET status = 'VOID', memo = ?, updated_at = now(), updated_by = ?, "
                + "version = version + 1 WHERE id = ? AND status = 'ISSUED'", memo, by, id);
    }

    // ================================================================ 입금

    public Optional<ReceiptRow> receiptByLine(String bankAccount, String statementKey) {
        return jdbc.query("SELECT * FROM cst.bank_receipt WHERE bank_account = ? AND statement_key = ?",
                BillingJdbc::receipt, bankAccount, statementKey).stream().findFirst();
    }

    public Optional<ReceiptRow> receipt(UUID id) {
        return jdbc.query("SELECT * FROM cst.bank_receipt WHERE id = ?", BillingJdbc::receipt, id).stream().findFirst();
    }

    private static ReceiptRow receipt(ResultSet rs, int i) throws SQLException {
        return new ReceiptRow(rs.getObject("id", UUID.class), rs.getString("bank_account"), ld(rs, "value_date"),
                rs.getBigDecimal("amount_krw"), rs.getString("payer_text"), rs.getString("statement_key"));
    }

    public void insertReceipt(ReceiptRow r, UUID by) {
        jdbc.update("INSERT INTO cst.bank_receipt(id, bank_account, value_date, amount_krw, payer_text, statement_key, "
                        + "created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                r.id(), r.bankAccount(), d(r.valueDate()), r.amountKrw(), r.payerText(), r.statementKey(), by);
    }

    public BigDecimal netAllocatedFromReceipt(UUID receiptId) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount_krw), 0) FROM cst.receipt_allocation WHERE receipt_id = ?",
                BigDecimal.class, receiptId);
    }

    public List<FreeReceipt> freeReceipts(LocalDate asOf) {
        return jdbc.query("""
                SELECT r.id, r.bank_account, r.value_date, r.amount_krw, r.payer_text,
                       r.amount_krw - coalesce((SELECT sum(a.amount_krw) FROM cst.receipt_allocation a
                                                 WHERE a.receipt_id = r.id), 0) AS remaining
                  FROM cst.bank_receipt r
                 WHERE r.value_date <= ?
                   AND r.amount_krw - coalesce((SELECT sum(a.amount_krw) FROM cst.receipt_allocation a
                                                 WHERE a.receipt_id = r.id), 0) > 0
                 ORDER BY r.value_date, r.id""",
                (rs, i) -> new FreeReceipt(rs.getObject("id", UUID.class), rs.getString("bank_account"),
                        ld(rs, "value_date"), rs.getBigDecimal("amount_krw"), rs.getString("payer_text"),
                        rs.getBigDecimal("remaining")), d(asOf));
    }

    // ================================================================ 미수 · aging

    public List<OpenItem> openItems(LocalDate asOf, UUID projectIdOrNull) {
        return jdbc.query("""
                SELECT o.invoice_id, o.invoice_no, o.project_id, o.client_id, c.name AS client_name, o.issue_date,
                       i.supply_krw, o.total_krw, o.allocated_krw, o.open_krw, o.age_days, o.bucket
                  FROM cst.invoice_open_items(?) o
                  JOIN cst.invoice i ON i.id = o.invoice_id
                  JOIN core.client c ON c.id = o.client_id
                 WHERE (?::uuid IS NULL OR o.project_id = ?::uuid)
                 ORDER BY o.age_days DESC, o.open_krw DESC, o.invoice_no""",
                (rs, i) -> new OpenItem(rs.getObject("invoice_id", UUID.class), rs.getString("invoice_no"),
                        rs.getObject("project_id", UUID.class), rs.getObject("client_id", UUID.class),
                        rs.getString("client_name"), ld(rs, "issue_date"), rs.getBigDecimal("supply_krw"),
                        rs.getBigDecimal("total_krw"), rs.getBigDecimal("allocated_krw"), rs.getBigDecimal("open_krw"),
                        rs.getInt("age_days"), rs.getString("bucket")),
                d(asOf), projectIdOrNull == null ? null : projectIdOrNull.toString(),
                projectIdOrNull == null ? null : projectIdOrNull.toString());
    }

    public List<AgingRow> aging(LocalDate asOf) {
        return jdbc.query("SELECT bucket, invoice_count, open_krw FROM cst.receivable_aging(?)",
                (rs, i) -> new AgingRow(rs.getString("bucket"), rs.getLong("invoice_count"), rs.getBigDecimal("open_krw")),
                d(asOf));
    }

    // ================================================================ 제안 · 배분

    public boolean pendingProposalExists(UUID invoiceId, UUID receiptId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM cst.allocation_proposal WHERE invoice_id = ? "
                + "AND receipt_id = ? AND status = 'PENDING'", Integer.class, invoiceId, receiptId);
        return n != null && n > 0;
    }

    public void insertProposal(ProposalRow p) {
        jdbc.update("INSERT INTO cst.allocation_proposal(id, invoice_id, receipt_id, amount_krw, match_score, proposed_by) "
                + "VALUES (?, ?, ?, ?, ?, ?)", p.id(), p.invoiceId(), p.receiptId(), p.amountKrw(), p.matchScore(),
                p.proposedBy());
    }

    public Optional<ProposalRow> proposal(UUID id) {
        return jdbc.query("SELECT * FROM cst.allocation_proposal WHERE id = ?",
                (rs, i) -> new ProposalRow(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getObject("receipt_id", UUID.class), rs.getBigDecimal("amount_krw"), rs.getInt("match_score"),
                        rs.getString("status"), rs.getObject("proposed_by", UUID.class),
                        rs.getObject("decided_by", UUID.class), rs.getString("reject_reason"),
                        rs.getObject("allocation_id", UUID.class)), id).stream().findFirst();
    }

    public int decideProposal(UUID id, String status, UUID by, String rejectReason, UUID allocationId) {
        return jdbc.update("UPDATE cst.allocation_proposal SET status = ?, decided_by = ?, decided_at = now(), "
                + "reject_reason = ?, allocation_id = ? WHERE id = ? AND status = 'PENDING'",
                status, by, rejectReason, allocationId, id);
    }

    public void insertAllocation(AllocationRow a) {
        jdbc.update("INSERT INTO cst.receipt_allocation(id, invoice_id, receipt_id, amount_krw, reversal_of, match_score, "
                        + "proposed_by, confirmed_by, reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                a.id(), a.invoiceId(), a.receiptId(), a.amountKrw(), a.reversalOf(), a.matchScore(), a.proposedBy(),
                a.confirmedBy(), a.reason());
    }

    public Optional<AllocationRow> allocation(UUID id) {
        return jdbc.query("SELECT * FROM cst.receipt_allocation WHERE id = ?",
                (rs, i) -> new AllocationRow(rs.getObject("id", UUID.class), rs.getObject("invoice_id", UUID.class),
                        rs.getObject("receipt_id", UUID.class), rs.getBigDecimal("amount_krw"),
                        rs.getObject("reversal_of", UUID.class), rs.getObject("match_score", Integer.class),
                        rs.getObject("proposed_by", UUID.class), rs.getObject("confirmed_by", UUID.class),
                        rs.getString("reason")), id).stream().findFirst();
    }

    public boolean reversed(UUID allocationId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM cst.receipt_allocation WHERE reversal_of = ?",
                Integer.class, allocationId);
        return n != null && n > 0;
    }
}
