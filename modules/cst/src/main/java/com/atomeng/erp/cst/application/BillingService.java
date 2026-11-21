package com.atomeng.erp.cst.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.cst.api.CstEvents;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.AllocationRow;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.FreeReceipt;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.InvoiceRow;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.OpenItem;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.ProposalRow;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc.ReceiptRow;
import com.atomeng.erp.cst.domain.billing.BankLineClassifier;
import com.atomeng.erp.cst.domain.billing.BankStatementParser;
import com.atomeng.erp.cst.domain.billing.MatchScorer;
import com.atomeng.erp.cst.domain.shared.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 청구 · 수금 · 통장 대사 (설계 08 Step 12·13, docs/CST_BILLING_COLLECTION_DESIGN.md).
 * cst.invoice · bank_receipt · allocation_proposal · receipt_allocation 쓰기 진입점은 이 서비스 하나다.
 *
 * <p>절차: 재무(FIN)가 청구를 발행하고 통장 입금을 등록 → 짝 후보(점수)를 보고 "제안" → 경영지원 · 임원이 "확정"(제안자 ≠ 확정자)
 * → 배분 1줄(INSERT 만). 취소는 역분개 1줄. 금액 · 불변 · 초과 배분은 DB 트리거도 지킨다.
 */
@Service
public class BillingService {

    public record Issue(UUID projectId, UUID clientId, LocalDate issueDate, LocalDate dueDate, BigDecimal supplyKrw,
                        BigDecimal vatKrw, String taxInvoiceRef, String memo) {}

    public record RegisterReceipt(String bankAccount, LocalDate valueDate, BigDecimal amountKrw, String payerText,
                                  String statementKey) {}

    public record Registered(ReceiptRow receipt, boolean replayed) {}

    /** 명세 업로드 결과 — 모든 줄은 bank_line 원장에, 입금 줄은 bank_receipt 에도. 숫자는 전부 건수 · 원 */
    public record UploadResult(String fileSha256, String kind, String encoding, int headerRow, List<String> headers, int rowsTotal,
                               int inflowLines, int outflowLines, int registered, int replayed, int skipped,
                               List<Integer> balanceMismatchRows, List<String> warnings,
                               int linesStored, int autoClassified, int suggested, int unclassified) {}

    public record Candidate(UUID invoiceId, String invoiceNo, String clientName, LocalDate issueDate,
                            BigDecimal openKrw, UUID receiptId, LocalDate valueDate, BigDecimal receiptKrw,
                            BigDecimal remainingKrw, String payerText, int amountScore, int nameScore, int dateScore,
                            int score, boolean autoCandidate) {}

    private static final int MAX_CANDIDATES = 500;

    private final BillingJdbc jdbc;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final OutboxPublisher outbox;

    public BillingService(BillingJdbc jdbc, AuditLog audit, CurrentActor actor, OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.actor = actor;
        this.outbox = outbox;
    }

    // ------------------------------------------------------------------ 청구

    @Transactional
    public InvoiceRow issue(Issue c) {
        if (c.supplyKrw() == null || c.supplyKrw().signum() <= 0 || c.vatKrw() == null || c.vatKrw().signum() < 0) {
            throw new DomainException(BillingErrors.INVALID, "공급가액은 0보다 커야 하고 부가세는 0 이상이어야 합니다");
        }
        if (c.dueDate() != null && c.dueDate().isBefore(c.issueDate())) {
            throw new DomainException(BillingErrors.INVALID, "입금 기한은 발행일보다 앞설 수 없습니다");
        }
        UUID by = actor.actorIdOrSystem();
        UUID id = UuidV7.next();
        InvoiceRow row = new InvoiceRow(id, jdbc.nextInvoiceNo(c.issueDate().getYear()), c.projectId(), c.clientId(),
                c.issueDate(), c.dueDate(), c.supplyKrw(), c.vatKrw(), c.supplyKrw().add(c.vatKrw()), "ISSUED",
                c.taxInvoiceRef(), c.memo(), 0);
        jdbc.insertInvoice(row, by);
        InvoiceRow saved = requireInvoice(id);
        audit.append("CST", AuditLog.Action.CREATE, "invoice", id, null, saved);
        outbox.publish(new CstEvents.InvoiceIssued(saved.id(), saved.invoiceNo(), saved.projectId(), saved.clientId(),
                saved.issueDate(), saved.supplyKrw(), saved.vatKrw(), saved.totalKrw()));
        return saved;
    }

    /** 취소(VOID) — 받은 돈(순 배분)이 남아 있으면 안 된다. 먼저 역분개한다. */
    @Transactional
    public InvoiceRow voidInvoice(UUID id, String reason) {
        InvoiceRow before = requireInvoice(id);
        if (!"ISSUED".equals(before.status())) {
            throw new DomainException(BillingErrors.INVOICE_NOT_ISSUED, "이미 취소된 청구입니다: " + before.invoiceNo());
        }
        if (jdbc.netAllocatedToInvoice(id).signum() != 0) {
            throw new DomainException(BillingErrors.INVOICE_HAS_RECEIPTS,
                    "배분된 입금이 남아 있습니다 — 먼저 역분개하세요: " + before.invoiceNo());
        }
        jdbc.voidInvoice(id, reason, actor.actorIdOrSystem());
        InvoiceRow after = requireInvoice(id);
        audit.append("CST", AuditLog.Action.UPDATE, "invoice", id, before, after);
        outbox.publish(new CstEvents.InvoiceVoided(id, after.invoiceNo(), reason));
        return after;
    }

    public InvoiceRow requireInvoice(UUID id) {
        return jdbc.invoice(id).orElseThrow(() -> new DomainException(BillingErrors.NOT_FOUND, "청구 없음: " + id));
    }

    public List<InvoiceRow> invoicesOfProject(UUID projectId) {
        return jdbc.invoicesOfProject(projectId);
    }

    // ------------------------------------------------------------------ 입금

    /** 통장 입금 한 줄 등록. 같은 (계좌 별칭, 명세서 줄)은 한 번만 — 다시 보내면 처음 것을 돌려준다(멱등). */
    @Transactional
    public Registered registerReceipt(RegisterReceipt c) {
        if (c.amountKrw() == null || c.amountKrw().signum() <= 0) {
            throw new DomainException(BillingErrors.INVALID, "입금액은 0보다 커야 합니다");
        }
        var existing = jdbc.receiptByLine(c.bankAccount(), c.statementKey());
        if (existing.isPresent()) {
            return new Registered(existing.get(), true);
        }
        ReceiptRow row = new ReceiptRow(UuidV7.next(), c.bankAccount(), c.valueDate(), c.amountKrw(), c.payerText(),
                c.statementKey());
        jdbc.insertReceipt(row, actor.actorIdOrSystem());
        audit.append("CST", AuditLog.Action.CREATE, "bank_receipt", row.id(), null, row);
        return new Registered(row, false);
    }

    /** 통장 명세 파일(CSV · XLSX) 업로드 → 입금 줄을 bank_receipt 로. 같은 파일을 다시 올리면 전부 replayed(멱등). 출금 줄은 세기만 한다. */
    @Transactional
    public UploadResult uploadStatement(String bankAccount, byte[] data) {
        if (bankAccount == null || bankAccount.isBlank()) {
            throw new DomainException(BillingErrors.INVALID, "계좌 별칭(bankAccount)이 필요합니다 — 계좌번호 원문은 쓰지 않는다");
        }
        BankStatementParser.Result r;
        try {
            r = BankStatementParser.parse(data);
        } catch (BankStatementParser.FormatException e) {
            throw new DomainException(BillingErrors.INVALID, "명세서를 읽지 못했습니다: " + e.getMessage());
        }
        int registered = 0;
        int replayed = 0;
        int outflow = 0;
        int stored = 0;
        int auto = 0;
        int suggestedN = 0;
        int unclassified = 0;
        UUID by = actor.actorIdOrSystem();
        for (BankStatementParser.Line l : r.lines()) {
            String memo = l.memo().length() > 200 ? l.memo().substring(0, 200) : l.memo();
            if (memo.isBlank()) {
                memo = "(거래내용 없음)";
            }
            UUID receiptId = null;
            if (l.direction() == BankStatementParser.Direction.OUT) {
                outflow++;
            } else {
                Registered reg = registerReceipt(new RegisterReceipt(bankAccount, l.valueDate(), l.amountKrw(), memo, l.lineKey()));
                receiptId = reg.receipt().id();
                if (reg.replayed()) {
                    replayed++;
                } else {
                    registered++;
                }
            }
            if (jdbc.bankLineExists(bankAccount, l.lineKey())) {
                continue;   // 같은 파일 재업로드 — 줄 원장도 한 번만
            }
            String dir = l.direction() == BankStatementParser.Direction.OUT ? "OUT" : "IN";
            Optional<BankLineClassifier.Suggestion> sg = BankLineClassifier.classify(dir, memo);
            String confirmed = sg.filter(BankLineClassifier.Suggestion::autoConfirm).map(BankLineClassifier.Suggestion::acct).orElse(null);
            jdbc.insertBankLine(new BillingJdbc.BankLineRow(UUID.randomUUID(), bankAccount, l.valueDate(), dir, l.amountKrw(), memo, l.lineKey(),
                    receiptId, sg.map(BankLineClassifier.Suggestion::acct).orElse(null), sg.map(BankLineClassifier.Suggestion::ruleCode).orElse(null),
                    confirmed, confirmed == null ? null : by), by);
            stored++;
            if (confirmed != null) {
                auto++;
            } else if (sg.isPresent()) {
                suggestedN++;
            } else {
                unclassified++;
            }
        }
        audit.append("CST", AuditLog.Action.CREATE, "bank_statement_upload", UUID.nameUUIDFromBytes(r.fileSha256().getBytes()), null,
                java.util.Map.of("bankAccount", bankAccount, "sha256", r.fileSha256(), "rows", r.rowsTotal(), "registered", registered,
                        "replayed", replayed, "outflow", outflow));
        return new UploadResult(r.fileSha256(), r.kind(), r.encoding(), r.headerRow(), r.headers(), r.rowsTotal(),
                (int) r.inflowCount(), outflow, registered, replayed, r.rowsSkipped(), r.balanceMismatchRows(), r.warnings(),
                stored, auto, suggestedN, unclassified);
    }

    // ------------------------------------------------------------------ 통장 줄 원장 · 계정 분류 (V0057)

    public List<BillingJdbc.BankLineRow> bankLines(LocalDate from, LocalDate to, boolean unclassifiedOnly, String directionOrNull) {
        return jdbc.bankLines(from, to, unclassifiedOnly, directionOrNull);
    }

    public List<BillingJdbc.AcctSum> bankLineSummary(LocalDate from, LocalDate to) {
        return jdbc.bankLineSummary(from, to);
    }

    /** 사람이 계정을 확정한다. 이미 확정된 줄은 force 없이는 바꾸지 못한다(CST-409-035). 계정명은 BankLineClassifier.ACCOUNTS 안의 것만 */
    @Transactional
    public BillingJdbc.BankLineRow classifyLine(UUID id, String acct, boolean force, String reason) {
        BillingJdbc.BankLineRow line = jdbc.bankLine(id).orElseThrow(() -> new DomainException(BillingErrors.NOT_FOUND, "통장 줄 없음: " + id));
        if (!BankLineClassifier.ACCOUNTS.contains(acct)) {
            throw new DomainException(BillingErrors.INVALID, "허용되지 않은 계정명: " + acct + " — 목록: " + BankLineClassifier.ACCOUNTS);
        }
        if (line.confirmedAcct() != null && !force) {
            throw new DomainException(BillingErrors.LINE_CONFIRMED, "이미 '" + line.confirmedAcct() + "' 로 확정된 줄 — 바꾸려면 force 와 사유");
        }
        if (force && (reason == null || reason.isBlank())) {
            throw new DomainException(BillingErrors.INVALID, "재확정(force)에는 사유가 필요합니다");
        }
        UUID by = actor.actorIdOrSystem();
        jdbc.confirmBankLine(id, acct, by);
        audit.append("CST", AuditLog.Action.UPDATE, "bank_line", id, line.confirmedAcct(),
                java.util.Map.of("acct", acct, "suggested", line.suggestedAcct() == null ? "" : line.suggestedAcct(), "force", force,
                        "reason", reason == null ? "" : reason));
        return jdbc.bankLine(id).orElseThrow();
    }

    // ------------------------------------------------------------------ 미수 · 짝 후보

    public List<OpenItem> openItems(LocalDate asOf, UUID projectIdOrNull) {
        return jdbc.openItems(asOf, projectIdOrNull);
    }

    public List<BillingJdbc.AgingRow> aging(LocalDate asOf) {
        return jdbc.aging(asOf);
    }

    /** 미수 청구 × 남은 입금의 짝 점수(MatchScorer). minScore 이상만, 점수 높은 순. 확정은 하지 않는다. */
    public List<Candidate> candidates(LocalDate asOf, int minScore) {
        List<OpenItem> open = jdbc.openItems(asOf, null);
        List<FreeReceipt> free = jdbc.freeReceipts(asOf);
        List<Candidate> out = new ArrayList<>();
        for (OpenItem o : open) {
            for (FreeReceipt r : free) {
                MatchScorer.Score s = MatchScorer.score(Money.krw(o.totalKrw()), Money.krw(o.supplyKrw()),
                        o.clientName(), o.issueDate(), Money.krw(r.amountKrw()), r.payerText(), r.valueDate());
                if (s.total() >= minScore) {
                    out.add(new Candidate(o.invoiceId(), o.invoiceNo(), o.clientName(), o.issueDate(), o.openKrw(),
                            r.id(), r.valueDate(), r.amountKrw(), r.remainingKrw(), r.payerText(), s.amount(),
                            s.name(), s.date(), s.total(), s.autoCandidate()));
                }
            }
        }
        out.sort(Comparator.comparingInt(Candidate::score).reversed().thenComparing(Candidate::invoiceNo)
                .thenComparing(Candidate::receiptId));
        return out.size() > MAX_CANDIDATES ? out.subList(0, MAX_CANDIDATES) : out;
    }

    // ------------------------------------------------------------------ 제안 · 확정 · 반려 · 역분개

    /** 재무가 "이 입금은 이 청구의 돈"이라고 제안한다(PENDING). 금액은 청구 미수와 입금 잔액을 넘을 수 없다. */
    @Transactional
    public ProposalRow propose(UUID invoiceId, UUID receiptId, BigDecimal amountKrw) {
        InvoiceRow inv = requireInvoice(invoiceId);
        ReceiptRow rcp = jdbc.receipt(receiptId)
                .orElseThrow(() -> new DomainException(BillingErrors.NOT_FOUND, "입금 없음: " + receiptId));
        if (!"ISSUED".equals(inv.status())) {
            throw new DomainException(BillingErrors.INVOICE_NOT_ISSUED, "취소된 청구에는 배분할 수 없습니다: " + inv.invoiceNo());
        }
        if (amountKrw == null || amountKrw.signum() <= 0) {
            throw new DomainException(BillingErrors.INVALID, "배분 금액은 0보다 커야 합니다");
        }
        if (jdbc.netAllocatedToInvoice(invoiceId).add(amountKrw).compareTo(inv.totalKrw()) > 0) {
            throw new DomainException(BillingErrors.OVER_INVOICE, "청구 합계를 넘는 배분입니다: " + inv.invoiceNo());
        }
        if (jdbc.netAllocatedFromReceipt(receiptId).add(amountKrw).compareTo(rcp.amountKrw()) > 0) {
            throw new DomainException(BillingErrors.OVER_RECEIPT, "입금액을 넘는 배분입니다");
        }
        if (jdbc.pendingProposalExists(invoiceId, receiptId)) {
            throw new DomainException(BillingErrors.PROPOSAL_EXISTS, "같은 청구 · 입금의 확인 대기 제안이 이미 있습니다");
        }
        String clientName = jdbc.openItems(LocalDate.of(9999, 12, 31), inv.projectId()).stream()
                .filter(o -> o.invoiceId().equals(invoiceId)).map(OpenItem::clientName).findFirst().orElse("");
        MatchScorer.Score s = MatchScorer.score(Money.krw(inv.totalKrw()), Money.krw(inv.supplyKrw()), clientName,
                inv.issueDate(), Money.krw(rcp.amountKrw()), rcp.payerText(), rcp.valueDate());
        ProposalRow p = new ProposalRow(UuidV7.next(), invoiceId, receiptId, amountKrw, s.total(), "PENDING",
                actor.actorIdOrSystem(), null, null, null);
        jdbc.insertProposal(p);
        audit.append("CST", AuditLog.Action.CREATE, "allocation_proposal", p.id(), null, p);
        return p;
    }

    /** 확정 — 제안자가 아닌 사람만. 배분 1줄을 INSERT 하고 제안을 CONFIRMED 로 바꾼다. */
    @Transactional
    public AllocationRow confirm(UUID proposalId) {
        ProposalRow p = pending(proposalId);
        UUID by = actor.actorIdOrSystem();
        if (by.equals(p.proposedBy())) {
            throw new DomainException(BillingErrors.SAME_PERSON, "제안한 사람은 확정할 수 없습니다(두 사람 규칙)");
        }
        AllocationRow a = new AllocationRow(UuidV7.next(), p.invoiceId(), p.receiptId(), p.amountKrw(), null,
                p.matchScore(), p.proposedBy(), by, null);
        jdbc.insertAllocation(a);
        jdbc.decideProposal(proposalId, "CONFIRMED", by, null, a.id());
        audit.append("CST", AuditLog.Action.APPROVE, "receipt_allocation", a.id(), null, a);
        outbox.publish(new CstEvents.ReceiptAllocated(a.id(), a.invoiceId(), a.receiptId(), a.amountKrw(), null, by));
        InvoiceRow inv = requireInvoice(a.invoiceId());
        if (jdbc.netAllocatedToInvoice(a.invoiceId()).compareTo(inv.totalKrw()) == 0) {
            outbox.publish(new CstEvents.InvoiceSettled(inv.id(), inv.invoiceNo(), inv.projectId(), inv.clientId(), inv.totalKrw(),
                    jdbc.receipt(a.receiptId()).map(ReceiptRow::valueDate).orElse(null), a.id()));
        }
        return a;
    }

    @Transactional
    public ProposalRow reject(UUID proposalId, String reason) {
        ProposalRow p = pending(proposalId);
        UUID by = actor.actorIdOrSystem();
        if (by.equals(p.proposedBy())) {
            throw new DomainException(BillingErrors.SAME_PERSON, "제안한 사람은 반려할 수 없습니다(두 사람 규칙)");
        }
        jdbc.decideProposal(proposalId, "REJECTED", by, reason, null);
        ProposalRow after = jdbc.proposal(proposalId).orElseThrow();
        audit.append("CST", AuditLog.Action.UPDATE, "allocation_proposal", proposalId, p, after);
        return after;
    }

    /** 역분개 — 원 배분을 그대로 뒤집은 음수 한 줄. 원 제안자는 할 수 없고, 한 배분은 한 번만. */
    @Transactional
    public AllocationRow reverse(UUID allocationId, String reason) {
        AllocationRow orig = jdbc.allocation(allocationId)
                .orElseThrow(() -> new DomainException(BillingErrors.NOT_FOUND, "배분 없음: " + allocationId));
        if (orig.reversalOf() != null || jdbc.reversed(allocationId)) {
            throw new DomainException(BillingErrors.ALREADY_REVERSED, "이미 역분개된 배분이거나 역분개 줄입니다");
        }
        UUID by = actor.actorIdOrSystem();
        if (by.equals(orig.proposedBy())) {
            throw new DomainException(BillingErrors.SAME_PERSON, "원 제안자는 역분개를 확정할 수 없습니다(두 사람 규칙)");
        }
        AllocationRow rev = new AllocationRow(UuidV7.next(), orig.invoiceId(), orig.receiptId(), orig.amountKrw().negate(),
                orig.id(), orig.matchScore(), orig.proposedBy(), by, reason);
        jdbc.insertAllocation(rev);
        audit.append("CST", AuditLog.Action.UPDATE, "receipt_allocation", rev.id(), orig, rev);
        outbox.publish(new CstEvents.ReceiptAllocated(rev.id(), rev.invoiceId(), rev.receiptId(), rev.amountKrw(), orig.id(), by));
        InvoiceRow inv = requireInvoice(rev.invoiceId());
        BigDecimal net = jdbc.netAllocatedToInvoice(rev.invoiceId());
        if (net.add(orig.amountKrw()).compareTo(inv.totalKrw()) == 0) {   // 역분개 전에는 전액 입금 상태였다
            outbox.publish(new CstEvents.InvoiceReopened(inv.id(), inv.invoiceNo(), inv.totalKrw().subtract(net), rev.id()));
        }
        return rev;
    }

    private ProposalRow pending(UUID proposalId) {
        ProposalRow p = jdbc.proposal(proposalId)
                .orElseThrow(() -> new DomainException(BillingErrors.NOT_FOUND, "제안 없음: " + proposalId));
        if (!"PENDING".equals(p.status())) {
            throw new DomainException(BillingErrors.PROPOSAL_STATE, "확인 대기 상태가 아닙니다: " + p.status());
        }
        return p;
    }
}
