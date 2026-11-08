package com.atomeng.erp.cst.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.cst.adapter.out.persistence.BillingJdbc;
import com.atomeng.erp.cst.application.BillingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/cst 청구 · 수금 · 통장 대사 (설계 08 Step 12·13, docs/CST_BILLING_COLLECTION_DESIGN.md).
 * 기준일(asOf)은 언제나 요청이 준다 — 서버의 "오늘"을 쓰지 않아 같은 기준일이면 같은 숫자가 나온다.
 */
@RestController
@RequestMapping("/api/v1/cst")
class BillingController {

    record IssueRequest(@NotNull UUID projectId, @NotNull UUID clientId, @NotNull LocalDate issueDate, LocalDate dueDate,
                        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal supplyKrw,
                        @NotNull @DecimalMin("0") BigDecimal vatKrw, @Size(max = 40) String taxInvoiceRef,
                        @Size(max = 300) String memo) {}

    record VoidRequest(@NotBlank @Size(max = 300) String reason) {}

    record ReceiptRequest(@NotBlank @Size(max = 40) String bankAccount, @NotNull LocalDate valueDate,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amountKrw,
                          @NotBlank @Size(max = 200) String payerText, @NotBlank @Size(max = 80) String statementKey) {}

    record ProposeRequest(@NotNull UUID invoiceId, @NotNull UUID receiptId,
                          @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amountKrw) {}

    record ReasonRequest(@NotBlank @Size(max = 300) String reason) {}

    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    // ------------------------------------------------------------------ 청구

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("cst:invoice:write")
    public BillingJdbc.InvoiceRow issue(@Valid @RequestBody IssueRequest r) {
        return billing.issue(new BillingService.Issue(r.projectId(), r.clientId(), r.issueDate(), r.dueDate(),
                r.supplyKrw(), r.vatKrw(), r.taxInvoiceRef(), r.memo()));
    }

    @PostMapping("/invoices/{id}:void")
    @RequirePermission("cst:invoice:write")
    public BillingJdbc.InvoiceRow voidInvoice(@PathVariable UUID id, @Valid @RequestBody VoidRequest r) {
        return billing.voidInvoice(id, r.reason());
    }

    /** 프로젝트의 청구 목록 — PM 은 자기 프로젝트만(projectIdParam) */
    @GetMapping("/projects/{projectId}/invoices")
    @RequirePermission(value = "cst:invoice:read", projectIdParam = "projectId")
    public List<BillingJdbc.InvoiceRow> invoices(@PathVariable UUID projectId) {
        return billing.invoicesOfProject(projectId);
    }

    // ------------------------------------------------------------------ 입금

    /** 통장 입금 한 줄. 같은 (계좌 별칭, 명세서 줄)을 다시 보내면 200 과 처음 등록분을 돌려준다 */
    @PostMapping("/receipts")
    @RequirePermission("cst:receipt:write")
    public ResponseEntity<BillingJdbc.ReceiptRow> registerReceipt(@Valid @RequestBody ReceiptRequest r) {
        var res = billing.registerReceipt(new BillingService.RegisterReceipt(r.bankAccount(), r.valueDate(),
                r.amountKrw(), r.payerText(), r.statementKey()));
        return ResponseEntity.status(res.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(res.receipt());
    }

    // ------------------------------------------------------------------ 미수 · aging (전사 조회)

    @GetMapping("/receivables/open-items")
    @RequirePermission("cst:receivable:read")
    public List<BillingJdbc.OpenItem> openItems(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return billing.openItems(asOf, null);
    }

    @GetMapping("/receivables/aging")
    @RequirePermission("cst:receivable:read")
    public List<BillingJdbc.AgingRow> aging(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return billing.aging(asOf);
    }

    // ------------------------------------------------------------------ 대사

    /** 짝 후보(점수 = 금액 50 + 이름 30 + 날짜 20). 보여 주기만 하고 확정하지 않는다 */
    @GetMapping("/allocations/candidates")
    @RequirePermission("cst:allocation:propose")
    public List<BillingService.Candidate> candidates(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @RequestParam(defaultValue = "60") @Min(0) @Max(100) int minScore) {
        return billing.candidates(asOf, minScore);
    }

    @PostMapping("/allocations:propose")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("cst:allocation:propose")
    public BillingJdbc.ProposalRow propose(@Valid @RequestBody ProposeRequest r) {
        return billing.propose(r.invoiceId(), r.receiptId(), r.amountKrw());
    }

    /** 확정 — 경영지원 · 임원, 제안자 ≠ 확정자 */
    @PostMapping("/allocation-proposals/{id}:confirm")
    @RequirePermission("cst:allocation:confirm")
    public BillingJdbc.AllocationRow confirm(@PathVariable UUID id) {
        return billing.confirm(id);
    }

    @PostMapping("/allocation-proposals/{id}:reject")
    @RequirePermission("cst:allocation:confirm")
    public BillingJdbc.ProposalRow reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return billing.reject(id, r.reason());
    }

    @PostMapping("/allocations/{id}:reverse")
    @RequirePermission("cst:allocation:confirm")
    public BillingJdbc.AllocationRow reverse(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return billing.reverse(id, r.reason());
    }
}
