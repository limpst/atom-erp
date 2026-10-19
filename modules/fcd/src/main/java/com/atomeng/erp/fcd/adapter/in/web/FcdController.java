package com.atomeng.erp.fcd.adapter.in.web;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.fcd.application.CardService;
import com.atomeng.erp.fcd.application.CaseService;
import com.atomeng.erp.fcd.application.ExpenseService;
import com.atomeng.erp.fcd.application.FcdErrors;
import com.atomeng.erp.fcd.application.ImportService;
import com.atomeng.erp.fcd.application.ReviewQueueService;
import com.atomeng.erp.fcd.application.RuleSetService;
import com.atomeng.erp.fcd.application.ScoringService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /api/v1/fcd (설계 12 B4 일부) — 카드 · 가져오기 · 중복 확인 큐 · 룰셋 · 채점 · 검토 큐 · 케이스 · 정산.
 * PAN 은 카드 등록 본문으로만 받고 응답 · 로그에 남기지 않는다(마스킹 번호만).
 */
@RestController
@RequestMapping("/api/v1/fcd")
class FcdController {

    record CardRequest(@NotBlank String cardCompany, @NotBlank String pan, @NotBlank String cardType,
                       UUID holderEmployeeId, String dept, String alias, @PositiveOrZero long limitPerTxn,
                       @PositiveOrZero long limitDaily, @PositiveOrZero long limitMonthly, LocalDate validThru) {
        @Override
        public String toString() {   // 로그 · 오류 메시지에 PAN 이 섞이지 않게
            return "CardRequest[" + cardCompany + ", ****]";
        }
    }

    record AssignRequest(@NotNull UUID employeeId, @NotNull LocalDateTime start, @NotNull LocalDateTime end,
                         @NotBlank String purpose, UUID projectId) {}

    record ReasonRequest(@NotBlank String reason) {}

    record DecisionRequest(@NotBlank String decision) {}

    record ScoringRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}

    record ReviewRequest(String policy, Double kPer1000, Integer minScore) {}

    record ExplanationRequest(@NotBlank String purpose, @NotBlank String text, JsonNode attendees) {}

    record ResolveRequest(@NotBlank String label, @NotBlank String reason, BigDecimal recoveryAmount) {}

    record NoteRequest(String note) {}

    record ExpenseRequest(@NotBlank String accountCode, @NotBlank String purpose, UUID projectId, String wbsCode,
                          @NotNull @PositiveOrZero BigDecimal supplyAmount, @NotNull @PositiveOrZero BigDecimal vatAmount,
                          boolean vatDeductible, Integer attendees) {}

    private final CardService cards;
    private final ImportService imports;
    private final RuleSetService rulesets;
    private final ScoringService scoring;
    private final ReviewQueueService review;
    private final CaseService cases;
    private final ExpenseService expenses;
    private final PermissionRegistry registry;
    private final JdbcTemplate jdbc;

    FcdController(CardService cards, ImportService imports, RuleSetService rulesets, ScoringService scoring,
                  ReviewQueueService review, CaseService cases, ExpenseService expenses, PermissionRegistry registry,
                  JdbcTemplate jdbc) {
        this.cards = cards;
        this.imports = imports;
        this.rulesets = rulesets;
        this.scoring = scoring;
        this.review = review;
        this.cases = cases;
        this.expenses = expenses;
        this.registry = registry;
        this.jdbc = jdbc;
    }

    private static AtomPrincipal me() {
        return AtomAuthentication.currentPrincipal()
                .orElseThrow(() -> new DomainException("CORE-401-001", "인증 필요"));
    }

    private boolean has(String permission) {
        return registry.scopeFor(me().roles(), permission).isPresent();
    }

    private boolean selfOnly(String permission) {
        AtomPrincipal p = me();
        return registry.scopeFor(p.roles(), permission).map(s -> s == PermissionScope.SELF).orElse(true);
    }

    // ------------------------------------------------------------------ 카드

    @PostMapping("/cards")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fcd:card:write")
    public Map<String, Object> register(@Valid @RequestBody CardRequest r) {
        return cards.register(new CardService.RegisterCard(r.cardCompany(), r.pan().toCharArray(), r.cardType(),
                r.holderEmployeeId(), r.dept(), r.alias(), r.limitPerTxn(), r.limitDaily(), r.limitMonthly(),
                r.validThru()), me().actorId());
    }

    @GetMapping("/cards")
    @RequirePermission("fcd:card:read")
    public List<Map<String, Object>> cards() {
        return cards.list();
    }

    @PostMapping("/cards/{id}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fcd:card:assign")
    public Map<String, Object> assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest r) {
        if (selfOnly("fcd:card:assign") && !r.employeeId().equals(me().actorId())) {
            throw new DomainException(FcdErrors.NOT_OWNER, "본인 대여만 신청할 수 있습니다");
        }
        return Map.of("id", cards.assign(id, r.employeeId(), r.start(), r.end(), r.purpose(), r.projectId(), me().actorId()));
    }

    // ------------------------------------------------------------------ 가져오기

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fcd:import:write")
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file,
                                      @RequestParam(required = false) String profileCode) throws IOException {
        return imports.upload(file.getBytes(), file.getOriginalFilename(), profileCode, me().actorId());
    }

    @GetMapping("/imports/{id}")
    @RequirePermission("fcd:import:read")
    public Map<String, Object> batch(@PathVariable UUID id) {
        return imports.batch(id);
    }

    @GetMapping("/imports/{id}/preview")
    @RequirePermission("fcd:import:read")
    public List<Map<String, Object>> preview(@PathVariable UUID id, @RequestParam(required = false) String bucket) {
        return imports.preview(id, bucket);
    }

    @GetMapping("/imports/{id}/errors.csv")
    @RequirePermission("fcd:import:read")
    public ResponseEntity<byte[]> errors(@PathVariable UUID id) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"errors-" + id + ".csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .body(imports.errorsCsv(id));
    }

    @PostMapping("/imports/{id}:commit")
    @RequirePermission("fcd:import:write")
    public Map<String, Object> commit(@PathVariable UUID id) {
        return imports.commit(id, me().actorId());
    }

    @PostMapping("/imports/{id}:discard")
    @RequirePermission("fcd:import:write")
    public Map<String, Object> discard(@PathVariable UUID id) {
        return imports.discard(id, me().actorId());
    }

    @GetMapping("/imports/{id}/rollback-impact")
    @RequirePermission("fcd:import:rollback")
    public Map<String, Integer> rollbackImpact(@PathVariable UUID id) {
        return imports.rollbackImpact(id);
    }

    @PostMapping("/imports/{id}:rollback")
    @RequirePermission("fcd:import:rollback")
    public Map<String, Object> rollback(@PathVariable UUID id, @Valid @RequestBody ReasonRequest r) {
        return imports.rollback(id, r.reason(), me().actorId());
    }

    @GetMapping("/dedup-candidates")
    @RequirePermission("fcd:dedup:decide")
    public List<Map<String, Object>> candidates() {
        return imports.candidates();
    }

    @PostMapping("/dedup-candidates/{id}:decide")
    @RequirePermission("fcd:dedup:decide")
    public Map<String, Object> decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest r) {
        return imports.decideCandidate(id, r.decision(), me().actorId());
    }

    // ------------------------------------------------------------------ 룰셋 · 채점 · 검토 큐

    @GetMapping("/rule-sets")
    @RequirePermission("fcd:ruleset:read")
    public List<Map<String, Object>> rulesets() {
        return rulesets.list();
    }

    @PostMapping(value = "/rule-sets", consumes = {"application/yaml", "text/plain"})
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fcd:ruleset:write")
    public Map<String, Object> draft(@RequestBody String yaml) {
        return rulesets.createDraft(yaml, me().actorId());
    }

    @PostMapping("/rule-sets/{version}:approve")
    @RequirePermission("fcd:ruleset:approve")
    public Map<String, Object> approve(@PathVariable String version,
                                       @RequestParam(required = false) String name) {
        return rulesets.approve(version, name, me().actorId());
    }

    @PostMapping("/scoring:run")
    @RequirePermission("fcd:score:run")
    public Map<String, Object> score(@Valid @RequestBody ScoringRequest r) {
        var res = scoring.scorePeriod(r.from(), r.to(), "RESCORE");
        return Map.of("scored", res.size(), "byGrade", res.stream()
                .collect(Collectors.groupingBy(ScoringService.Scored::gradeCode, Collectors.counting())));
    }

    @PostMapping("/review-queue/{ym}:run")
    @RequirePermission("fcd:review:run")
    public Map<String, Object> runReview(@PathVariable String ym, @RequestBody(required = false) ReviewRequest r) {
        ReviewRequest q = r == null ? new ReviewRequest(null, null, null) : r;
        return review.run(YearMonth.parse(ym), q.policy(), q.kPer1000(), q.minScore(), me().actorId());
    }

    // ------------------------------------------------------------------ 거래 조회

    @GetMapping("/transactions")
    @RequirePermission("fcd:txn:read")
    public List<Map<String, Object>> transactions(@RequestParam(required = false) LocalDate from,
                                                  @RequestParam(required = false) LocalDate to) {
        UUID emp = selfOnly("fcd:txn:read") ? me().actorId() : null;
        return jdbc.queryForList("""
                SELECT t.id, c.masked_no, t.employee_id, t.approval_no, t.approved_at, t.approved_at_precision, t.amount_krw,
                       t.cancelled_amount_krw, t.status, t.merchant_name, t.risk_score, t.risk_grade
                  FROM fcd.txn t JOIN fcd.card c ON c.id = t.card_id
                 WHERE t.removed_at IS NULL AND (?::uuid IS NULL OR t.employee_id = ?)
                   AND (?::date IS NULL OR t.approved_at >= ?::date) AND (?::date IS NULL OR t.approved_at < ?::date)
                 ORDER BY t.approved_at, t.seq LIMIT 1000""", emp, emp, from, from, to, to);
    }

    @GetMapping("/transactions/{id}/risk")
    @RequirePermission("fcd:txn:read")
    public List<Map<String, Object>> risk(@PathVariable UUID id) {
        if (selfOnly("fcd:txn:read")) {
            UUID owner = jdbc.query("SELECT employee_id FROM fcd.txn WHERE id = ?", rs -> rs.next() ? rs.getObject(1, UUID.class)
                    : null, id);
            if (!me().actorId().equals(owner)) {
                throw new DomainException(FcdErrors.NOT_OWNER, "본인 거래만 조회할 수 있습니다");
            }
        }
        return jdbc.queryForList("""
                SELECT scored_at, trigger, rule_set_version, model_version, score, grade, hits::text AS hits,
                       mitigations::text AS mitigations, reasons::text AS reasons, l3_status
                  FROM fcd.risk_score WHERE txn_id = ? ORDER BY scored_at DESC, id DESC""", id);
    }

    // ------------------------------------------------------------------ 케이스

    @GetMapping("/cases")
    @RequirePermission("fcd:case:read")
    public List<Map<String, Object>> cases() {
        return cases.list(selfOnly("fcd:case:read") ? me().actorId() : null);
    }

    @GetMapping("/cases/{id}")
    @RequirePermission("fcd:case:read")
    public Map<String, Object> caseView(@PathVariable UUID id) {
        Map<String, Object> c = cases.view(id);
        if (selfOnly("fcd:case:read") && !me().actorId().equals(c.get("subject_employee_id"))) {
            throw new DomainException(FcdErrors.NOT_OWNER, "본인 케이스만 조회할 수 있습니다");
        }
        return c;
    }

    @PostMapping("/cases/{id}:request-explanation")
    @RequirePermission("fcd:case:request")
    public Map<String, Object> requestExplanation(@PathVariable UUID id) {
        return cases.requestExplanation(id, me().actorId());
    }

    @PostMapping("/cases/{id}/explanations")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("fcd:case:explain")
    public Map<String, Object> explain(@PathVariable UUID id, @Valid @RequestBody ExplanationRequest r) {
        return cases.submitExplanation(id, me().actorId(), r.purpose(), r.text(),
                r.attendees() == null ? null : r.attendees().toString());
    }

    @PostMapping("/cases/{id}:request-supplement")
    @RequirePermission("fcd:case:request")
    public Map<String, Object> supplement(@PathVariable UUID id, @RequestBody(required = false) NoteRequest r) {
        return cases.requestSupplement(id, me().actorId(), r == null ? null : r.note());
    }

    @PostMapping("/cases/{id}:escalate")
    @RequirePermission("fcd:case:resolve")
    public Map<String, Object> escalate(@PathVariable UUID id) {
        return cases.escalate(id, me().actorId());
    }

    @PostMapping("/cases/{id}:resolve")
    @RequirePermission("fcd:case:resolve")
    public Map<String, Object> resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest r) {
        AtomPrincipal p = me();
        Set<String> roles = p.roles().stream().map(Enum::name).collect(Collectors.toSet());
        return cases.resolve(id, p.actorId(), roles, r.label(), r.reason(), r.recoveryAmount());
    }

    @PostMapping("/cases:sweep")
    @RequirePermission("fcd:case:request")
    public Map<String, Object> sweep() {
        return cases.sweep(me().actorId());
    }

    // ------------------------------------------------------------------ 정산

    @PutMapping("/transactions/{id}/expense")
    @RequirePermission("fcd:expense:write")
    public Map<String, Object> expense(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest r) {
        return expenses.upsertLine(id, me().actorId(), new ExpenseService.LineInput(r.accountCode(), r.purpose(),
                r.projectId(), r.wbsCode(), r.supplyAmount(), r.vatAmount(), r.vatDeductible(), r.attendees()));
    }

    @PostMapping("/expense-reports/{period}:submit")
    @RequirePermission("fcd:expense:write")
    public Map<String, Object> submit(@PathVariable String period) {
        UUID me = me().actorId();
        return expenses.submit(me, period, cases.blockingCases(me));
    }

    @PostMapping("/expense-reports/{id}:approve")
    @RequirePermission("fcd:expense:approve")
    public Map<String, Object> approveReport(@PathVariable UUID id) {
        return expenses.approve(id, me().actorId());
    }

    @PostMapping("/expense-reports/{id}:post")
    @RequirePermission("fcd:expense:post")
    public Map<String, Object> post(@PathVariable UUID id) {
        return expenses.post(id, me().actorId());
    }

    @GetMapping("/expense-reports/{id}")
    @RequirePermission("fcd:expense:write")
    public Map<String, Object> report(@PathVariable UUID id) {
        Map<String, Object> r = expenses.report(id);
        // SEC-FCD-002: fcd:expense:write 는 모든 역할에 SELF — 결재(approve) · 전기(post) 권한자만 남의 정산서를 본다
        if (!me().actorId().equals(r.get("employee_id")) && !has("fcd:expense:approve") && !has("fcd:expense:post")) {
            throw new DomainException(FcdErrors.NOT_OWNER, "본인 정산서만 조회할 수 있습니다");
        }
        return r;
    }

    @GetMapping("/employees/{id}/expense-gate")
    @RequirePermission("fcd:case:read")
    public Map<String, Object> gate(@PathVariable UUID id) {
        // SEC-FCD-003: SELF 범위(EMPLOYEE · PM)는 본인 게이트만 — 남의 '조사 중' 여부 노출 방지
        if (selfOnly("fcd:case:read") && !me().actorId().equals(id)) {
            throw new DomainException(FcdErrors.NOT_OWNER, "본인 정산 게이트만 조회할 수 있습니다");
        }
        List<String> blocking = cases.blockingCases(id);
        return Map.of("canSubmit", blocking.isEmpty(), "blockingCases", blocking);
    }
}
