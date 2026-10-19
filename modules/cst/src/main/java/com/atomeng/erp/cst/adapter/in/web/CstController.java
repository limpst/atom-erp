package com.atomeng.erp.cst.adapter.in.web;

import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.Role;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc;
import com.atomeng.erp.cst.adapter.out.persistence.CstJdbc.LedgerRow;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.cst.application.BudgetService;
import com.atomeng.erp.cst.application.CstErrors;
import com.atomeng.erp.cst.application.EvmService;
import com.atomeng.erp.cst.application.PeriodService;
import com.atomeng.erp.cst.application.RateService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/cst (설계 08 B4 일부) — 요율, 원장 조회(실단가 열 마스킹), EVM, 진척 수동 보정, 회계기간 마감·재오픈·대사 조회.
 * 실행예산 편성·결재(Step 2·5)·경비·청구 API 는 후속 단계.
 */
@RestController
@RequestMapping("/api/v1/cst")
class CstController {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final String COST_REAL = "cst:cost-real:read";

    record RateRequest(@NotBlank String rateKind, @NotBlank @Size(max = 50) String keyCode,
                       @NotNull @DecimalMin("0") BigDecimal value, @NotNull LocalDate validFrom,
                       @NotBlank String sourceRef) {}

    record ProgressRequest(@NotNull UUID wbsId, @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal pctComplete,
                           LocalDate asOf, @NotBlank String reason) {}

    record ReopenRequest(@NotBlank String reason) {}

    private final RateService rates;
    private final EvmService evm;
    private final PeriodService periods;
    private final CstJdbc jdbc;
    private final PermissionRegistry registry;
    private final Clock clock;
    private final BudgetService budgets;

    CstController(RateService rates, EvmService evm, PeriodService periods, CstJdbc jdbc, PermissionRegistry registry,
                  Clock clock, BudgetService budgets) {
        this.budgets = budgets;
        this.rates = rates;
        this.evm = evm;
        this.periods = periods;
        this.jdbc = jdbc;
        this.registry = registry;
        this.clock = clock;
    }

    /** 실단가(개인 급여 역산 가능) 열람 권한 — ADMIN·EXEC (설계 A2 🔒, TC-CST-11) */
    private boolean realCostVisible() {
        return AtomAuthentication.currentPrincipal()
                .map(p -> registry.scopeFor(p.roles(), COST_REAL).isPresent()).orElse(false);
    }

    // ------------------------------------------------------------------ 요율

    @GetMapping("/rates")
    @RequirePermission("cst:rate:read")
    public List<CstJdbc.RateRow> rates(@RequestParam(required = false) String kind) {
        return jdbc.rates(kind);
    }

    @PostMapping("/rates")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("cst:rate:write")
    public CstJdbc.RateRow registerRate(@Valid @RequestBody RateRequest r) {
        return rates.register(new RateService.Register(r.rateKind(), r.keyCode(), r.value(), r.validFrom(),
                r.sourceRef()));
    }

    // ------------------------------------------------------------------ 원장

    @GetMapping("/projects/{projectId}/ledger")
    @RequirePermission(value = "cst:ledger:read", projectIdParam = "projectId")
    public List<Map<String, Object>> ledger(@PathVariable UUID projectId,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                            LocalDate from,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                            LocalDate to) {
        boolean real = realCostVisible();
        return jdbc.projectLedger(projectId, from, to).stream().map(r -> view(r, real)).toList();
    }

    private static Map<String, Object> view(LedgerRow r, boolean real) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("periodYm", r.periodYm());
        m.put("postingDate", r.postingDate());
        m.put("sourceType", r.sourceType());
        m.put("sourceId", r.sourceId());
        m.put("costElement", r.costElement());
        m.put("employeeId", r.employeeId());
        m.put("gradeCode", r.gradeCode());
        m.put("workType", r.workType());
        m.put("hours", r.hours());
        m.put("qty", r.qty());
        m.put("amountStdKrw", r.amountStdKrw());
        m.put("amountKrw", real ? r.amountKrw() : null);   // 실단가 열 마스킹
        m.put("amountKrwMasked", !real);
        m.put("reversalOf", r.reversalOf());
        m.put("sourceRef", r.sourceRef());
        m.put("flags", r.flags());
        return m;
    }

    @GetMapping("/projects/{projectId}/ledger/summary")
    @RequirePermission(value = "cst:ledger:read", projectIdParam = "projectId")
    public List<Map<String, Object>> ledgerSummary(@PathVariable UUID projectId) {
        boolean real = realCostVisible();
        return jdbc.periodTotals(projectId).stream().map(m -> {
            Map<String, Object> out = new LinkedHashMap<>(m);
            if (!real) {
                out.put("amount_krw", null);
            }
            return out;
        }).toList();
    }

    // ------------------------------------------------------------------ 실행예산 · 수익성

    @GetMapping("/projects/{projectId}/budget-revisions")
    @RequirePermission(value = "cst:budget:read", projectIdParam = "projectId")
    public List<Map<String, Object>> budgetRevisions(@PathVariable UUID projectId) {
        return budgets.revisions(projectId);
    }

    /** 실행예산 결재(EXEC) — DRAFT → APPROVED(Baseline). If-Match 필수 */
    @PostMapping("/projects/{projectId}/budget-revisions/{revisionId}:approve")
    @RequirePermission(value = "cst:budget:approve", projectIdParam = "projectId")
    public Map<String, Object> approveBudget(@PathVariable UUID projectId, @PathVariable UUID revisionId,
                                             @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        return budgets.approve(projectId, revisionId, IfMatch.require(ifMatch));
    }

    /** 수익성(원장 기준 실시간) — 실단가 권한이 없으면 표준단가 기준 */
    @GetMapping("/projects/{projectId}/profitability")
    @RequirePermission(value = "cst:evm:read", projectIdParam = "projectId")
    public Map<String, Object> profitability(@PathVariable UUID projectId) {
        return budgets.profitability(projectId, realCostVisible());
    }

    // ------------------------------------------------------------------ EVM · 진척

    @GetMapping("/projects/{projectId}/evm")
    @RequirePermission(value = "cst:evm:read", projectIdParam = "projectId")
    public EvmService.EvmView evm(@PathVariable UUID projectId,
                                  @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                                  LocalDate asOf) {
        return evm.evaluate(projectId, asOf != null ? asOf : LocalDate.now(clock.withZone(SEOUL)), realCostVisible());
    }

    @PostMapping("/projects/{projectId}/progress")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(value = "cst:progress:write", projectIdParam = "projectId")
    public Map<String, Object> progress(@PathVariable UUID projectId, @Valid @RequestBody ProgressRequest r) {
        boolean exec = AtomAuthentication.currentPrincipal().map(p -> p.has(Role.EXEC)).orElse(false);
        LocalDate asOf = r.asOf() != null ? r.asOf() : LocalDate.now(clock.withZone(SEOUL));
        UUID id = evm.recordManualProgress(projectId, r.wbsId(), asOf, r.pctComplete(), r.reason(), exec);
        return Map.of("id", id, "wbsId", r.wbsId(), "pctComplete", r.pctComplete(), "asOf", asOf);
    }

    // ------------------------------------------------------------------ 회계기간

    @GetMapping("/periods/{ym}")
    @RequirePermission("cst:period:read")
    public Map<String, Object> period(@PathVariable String ym) {
        String p = CstErrors.period(ym).value();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("periodYm", p);
        jdbc.period(p).ifPresent(r -> {
            out.put("status", r.status());
            out.put("closedAt", r.closedAt());
            out.put("hrqClosedAt", r.hrqClosedAt());
            out.put("reopenReason", r.reopenReason());
        });
        out.put("reconciliation", jdbc.latestReconciliation(p).orElse(null));
        return out;
    }

    @PostMapping("/periods/{ym}:close")
    @RequirePermission("cst:period:close")
    public PeriodService.CloseResult close(@PathVariable String ym) {
        return periods.close(ym);
    }

    @PostMapping("/periods/{ym}:reopen")
    @RequirePermission("cst:period:reopen")
    public Map<String, Object> reopen(@PathVariable String ym, @Valid @RequestBody ReopenRequest r) {
        periods.reopen(ym, r.reason());
        return Map.of("periodYm", ym, "status", "REOPENED");
    }
}
