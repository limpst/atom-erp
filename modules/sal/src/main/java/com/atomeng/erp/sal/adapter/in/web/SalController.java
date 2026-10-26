package com.atomeng.erp.sal.adapter.in.web;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.common.web.http.PageResponse;
import com.atomeng.erp.sal.application.BidCollectService;
import com.atomeng.erp.sal.application.ContractService;
import com.atomeng.erp.sal.application.EstimateService;
import com.atomeng.erp.sal.application.FeeService;
import com.atomeng.erp.sal.application.OpportunityService;
import com.atomeng.erp.sal.application.QualificationService;
import com.atomeng.erp.sal.domain.SalCodes;
import com.atomeng.erp.sal.domain.fee.FeeModels.Expense;
import com.atomeng.erp.sal.domain.fee.FeeModels.FacilityProfile;
import com.atomeng.erp.sal.domain.fee.FeeModels.FeeCalcCommand;
import com.atomeng.erp.sal.domain.fee.FeeModels.ManDayOverride;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/sal (설계 01 B4). 상태를 바꾸는 단건 액션(:move · :approve-discount · 계약 :approve · :sign)은 ETag/If-Match(412).
 * 권한: 8.2 표의 sal:opportunity 셀 + 모듈 세부 권한(V0016) — 견적은 sal:quote:*, 할인 승인은 sal:discount:approve(EXEC 전용),
 * 계약 등록 · 결재 · 서명 기록은 sal:agreement:* (8.2 표의 estimate · contract 리소스와 겹치지 않게 분리, README 편차 참조).
 */
@RestController
@RequestMapping("/api/v1/sal")
class SalController {

    record OpportunityRequest(@NotBlank @Pattern(regexp = "G2B|DUE_SOON|INQUIRY|REFERRAL|RENEWAL|PORTAL") String source,
                              UUID clientId, UUID facilityId, UUID bidNoticeId,
                              @NotBlank @Size(max = 30) String serviceType, @NotBlank @Size(max = 300) String title,
                              @PositiveOrZero BigDecimal expectedAmount, Integer probability, LocalDate expectedClose,
                              UUID ownerId) {}

    record MoveRequest(@NotBlank String toStage, @Size(max = 1000) String note, String lostReason, UUID ownerId) {}

    record ActivityRequest(@NotBlank @Pattern(regexp = "CALL|VISIT|EMAIL|MEETING|NOTE") String type,
                           @NotBlank @Size(max = 4000) String summary, @Size(max = 300) String nextAction,
                           LocalDate nextActionAt) {}

    record FacilityRequest(@NotBlank String facilityClass, @NotNull @PositiveOrZero BigDecimal grossAreaM2,
                           String structure, Integer basementFloors, Integer floors, Integer completionYear,
                           Boolean inUse) {}

    record ExpenseRequest(@NotBlank @Size(max = 50) String item, @NotNull @PositiveOrZero BigDecimal quantity,
                          @NotNull @PositiveOrZero BigDecimal unitPrice) {}

    record OverrideRequest(@NotBlank String workItem, @NotNull @Positive BigDecimal adjFactor,
                           @NotBlank @Size(max = 300) String reason) {}

    /** 설계 B4.1 FeeCalcRequest (facility = 시설물 규모 — fac::api 조회 포트 공개 전까지 요청에 포함) */
    record FeeCalcRequest(@NotNull LocalDate asOfDate, String serviceType, @NotNull @Valid FacilityRequest facility,
                          BigDecimal overheadRate, BigDecimal techFeeRate, List<@Valid ExpenseRequest> expenses,
                          List<@Valid OverrideRequest> manDayOverrides,
                          @DecimalMin("0") @DecimalMax(value = "0.5", inclusive = false) BigDecimal discountRate,
                          @Positive Integer validDays) {

        FeeCalcCommand command() {
            return new FeeCalcCommand(asOfDate, serviceType, overheadRate, techFeeRate,
                    expenses == null ? List.of() : expenses.stream()
                            .map(e -> new Expense(e.item(), e.quantity(), e.unitPrice())).toList(),
                    manDayOverrides == null ? List.of() : manDayOverrides.stream()
                            .map(o -> new ManDayOverride(o.workItem(), o.adjFactor(), o.reason())).toList());
        }

        FacilityProfile profile() {
            return new FacilityProfile(facility.facilityClass(), facility.grossAreaM2(), facility.structure(),
                    facility.basementFloors(), facility.floors(), facility.completionYear(), facility.inUse());
        }
    }

    record DiscountDecision(@NotBlank @Pattern(regexp = "APPROVE|REJECT") String decision,
                            @Size(max = 1000) String comment) {}

    record SendRequest(List<String> contacts, @Size(max = 2000) String message) {}

    record RespondRequest(@Size(max = 1000) String note) {}

    record SimulationRequest(String criteriaCode, LocalDate noticeDate, @Positive BigDecimal estPrice, String serviceType,
                             @NotEmpty List<@NotNull BigDecimal> bidRates, Integer engineersQualified,
                             Integer engineersRequired, String creditGrade, boolean disabledStandardWorkplace) {}

    record PerformanceRow(@Size(max = 40) String certNo, UUID projectId, UUID facilityId, String serviceType,
                          String clientName, BigDecimal amountKrw, BigDecimal jointShare, LocalDate startDate,
                          LocalDate endDate) {}

    record PerformanceImport(@NotEmpty List<PerformanceRow> rows, @Pattern(regexp = "MIGRATED|SYSTEM") String source) {}

    record CollectRequest(@NotNull OffsetDateTime from, @NotNull OffsetDateTime to) {}

    record ContractRequest(UUID opportunityId, UUID estimateId, UUID clientId, @NotBlank @Size(max = 300) String title,
                           @Size(max = 30) String serviceType,
                           @NotBlank @Pattern(regexp = "COMPETITIVE|PRIVATE_NEGOTIATED|PRIVATE_SECTOR|OVERSEAS")
                           String contractMethod,
                           @Size(max = 30) String negotiatedReason,
                           @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") BigDecimal jointShare,
                           @NotNull @PositiveOrZero BigDecimal amount, @Pattern(regexp = "[A-Z]{3}") String currency,
                           @Positive BigDecimal fxRate, boolean vatIncluded, @NotNull LocalDate signDate,
                           @NotNull LocalDate startDate, @NotNull LocalDate endDate, Integer warrantyMonths,
                           @Size(max = 1000) String amountDiffReason, UUID scanAttachmentId) {}

    record ContractApproveRequest(LocalDate signDeadline, List<String> signers) {}

    record ContractSignRequest(LocalDate signedOn) {}

    private final OpportunityService opportunities;
    private final FeeService fees;
    private final EstimateService estimates;
    private final ContractService contracts;
    private final QualificationService qualification;
    private final BidCollectService collector;

    SalController(OpportunityService opportunities, FeeService fees, EstimateService estimates,
                  ContractService contracts, QualificationService qualification, BidCollectService collector) {
        this.opportunities = opportunities;
        this.fees = fees;
        this.estimates = estimates;
        this.contracts = contracts;
        this.qualification = qualification;
        this.collector = collector;
    }

    private static long version(Map<String, Object> v) {
        return ((Number) v.get("version")).longValue();
    }

    private static ResponseEntity<Map<String, Object>> withEtag(Map<String, Object> v) {
        return ResponseEntity.ok().eTag(IfMatch.etagOf(version(v))).body(v);
    }

    private static Set<String> roles() {
        return AtomAuthentication.currentPrincipal()
                .map(p -> p.roles().stream().map(Enum::name).collect(Collectors.toSet()))
                .orElse(Set.of());
    }

    // ------------------------------------------------------------------ 기회 (FR-SAL-014–016)

    @PostMapping("/opportunities")
    @RequirePermission("sal:opportunity:create")
    public ResponseEntity<Map<String, Object>> createOpportunity(@Valid @RequestBody OpportunityRequest r) {
        if (r.probability() != null && (r.probability() < 0 || r.probability() > 100)) {
            throw new DomainException(SalCodes.INVALID, "probability 는 0–100");
        }
        Map<String, Object> v = opportunities.create(new OpportunityService.Create(r.source(), r.clientId(),
                r.facilityId(), r.bidNoticeId(), r.serviceType(), r.title(), r.expectedAmount(), r.probability(),
                r.expectedClose(), r.ownerId()));
        return ResponseEntity.created(URI.create("/api/v1/sal/opportunities/" + v.get("id")))
                .eTag(IfMatch.etagOf(version(v))).body(v);
    }

    @GetMapping("/opportunities")
    @RequirePermission("sal:opportunity:read")
    public PageResponse<Map<String, Object>> opportunities(@RequestParam(required = false) String stage,
                                                           @RequestParam(required = false) UUID ownerId,
                                                           @RequestParam(required = false) String source,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "50") int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        long total = opportunities.count(stage, ownerId, source);
        return new PageResponse<>(opportunities.list(stage, ownerId, source, p, s), p, s, total,
                (int) ((total + s - 1) / s));
    }

    @GetMapping("/opportunities/{id}")
    @RequirePermission("sal:opportunity:read")
    public ResponseEntity<Map<String, Object>> opportunity(@PathVariable UUID id) {
        return withEtag(opportunities.get(id));
    }

    @PostMapping("/opportunities/{id}:move")
    @RequirePermission("sal:opportunity:update")
    public ResponseEntity<Map<String, Object>> move(@PathVariable UUID id,
                                                    @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                    @Valid @RequestBody MoveRequest r) {
        return withEtag(opportunities.move(id, IfMatch.require(ifMatch), r.toStage(), r.note(), r.lostReason(),
                r.ownerId()));
    }

    /** SAL-03 입찰 품의 승인(BIDDING → BID_APPROVED) — EXEC(sal:opportunity:approve), If-Match */
    @PostMapping("/opportunities/{id}:approve-bid")
    @RequirePermission("sal:opportunity:approve")
    public ResponseEntity<Map<String, Object>> approveBid(@PathVariable UUID id,
                                                          @RequestHeader(value = "If-Match", required = false)
                                                          String ifMatch,
                                                          @RequestBody(required = false) RespondRequest r) {
        return withEtag(opportunities.approveBid(id, IfMatch.require(ifMatch), r == null ? null : r.note()));
    }

    @GetMapping("/opportunities/{id}/history")
    @RequirePermission("sal:opportunity:read")
    public List<Map<String, Object>> history(@PathVariable UUID id) {
        return opportunities.stageHistory(id);
    }

    @PostMapping("/opportunities/{id}/activities")
    @RequirePermission("sal:opportunity:update")
    public Map<String, Object> addActivity(@PathVariable UUID id, @Valid @RequestBody ActivityRequest r) {
        return opportunities.addActivity(id, r.type(), r.summary(), r.nextAction(), r.nextActionAt());
    }

    @GetMapping("/opportunities/{id}/activities")
    @RequirePermission("sal:opportunity:read")
    public List<Map<String, Object>> activities(@PathVariable UUID id) {
        return opportunities.activities(id);
    }

    // ------------------------------------------------------------------ 대가 산정 · 견적 (FR-SAL-009–012)

    @PostMapping("/fee-calculations")
    @RequirePermission("sal:fee:calculate")
    public Map<String, Object> calculate(@Valid @RequestBody FeeCalcRequest r) {
        if (r.serviceType() == null) {
            throw new DomainException(SalCodes.INVALID, "serviceType 이 필요합니다");
        }
        return fees.calculateView(r.command(), r.profile());
    }

    @GetMapping("/fee-rules")
    @RequirePermission("sal:fee-rule:read")
    public List<Map<String, Object>> feeRules() {
        return fees.rules();
    }

    @PostMapping("/opportunities/{id}/estimates")
    @RequirePermission("sal:quote:create")
    public ResponseEntity<Map<String, Object>> createEstimate(@PathVariable UUID id,
                                                              @Valid @RequestBody FeeCalcRequest r) {
        Map<String, Object> v = estimates.createVersion(id, new EstimateService.CreateVersion(r.command(), r.profile(),
                r.discountRate(), r.validDays()), roles());
        return ResponseEntity.created(URI.create("/api/v1/sal/estimates/" + v.get("id")))
                .eTag(IfMatch.etagOf(version(v))).body(v);
    }

    @GetMapping("/opportunities/{id}/estimates")
    @RequirePermission("sal:quote:read")
    public List<Map<String, Object>> estimateVersions(@PathVariable UUID id) {
        return estimates.versionsOf(id);
    }

    @GetMapping("/estimates/{id}")
    @RequirePermission("sal:quote:read")
    public ResponseEntity<Map<String, Object>> estimate(@PathVariable UUID id) {
        return withEtag(estimates.get(id));
    }

    @PostMapping("/estimates/{id}:issue")
    @RequirePermission("sal:quote:issue")
    public ResponseEntity<Map<String, Object>> issue(@PathVariable UUID id) {
        return withEtag(estimates.issue(id));
    }

    /** v0.2 BR-SAL-006 — EXEC 전용(sal:discount:approve), If-Match 필수 */
    @PostMapping("/estimates/{id}:approve-discount")
    @RequirePermission("sal:discount:approve")
    public ResponseEntity<Map<String, Object>> approveDiscount(@PathVariable UUID id,
                                                               @RequestHeader(value = "If-Match", required = false)
                                                               String ifMatch,
                                                               @Valid @RequestBody DiscountDecision r) {
        return withEtag(estimates.approveDiscount(id, IfMatch.require(ifMatch), r.decision(), r.comment()));
    }

    @PostMapping("/estimates/{id}:send")
    @RequirePermission("sal:quote:send")
    public ResponseEntity<Map<String, Object>> send(@PathVariable UUID id, @RequestBody(required = false) SendRequest r) {
        return withEtag(estimates.send(id, r == null ? List.of() : r.contacts()));
    }

    @PostMapping("/estimates/{id}:accept")
    @RequirePermission("sal:quote:send")
    public ResponseEntity<Map<String, Object>> accept(@PathVariable UUID id,
                                                      @RequestBody(required = false) RespondRequest r) {
        return withEtag(estimates.respond(id, true, r == null ? null : r.note()));
    }

    @PostMapping("/estimates/{id}:reject")
    @RequirePermission("sal:quote:send")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable UUID id,
                                                      @RequestBody(required = false) RespondRequest r) {
        return withEtag(estimates.respond(id, false, r == null ? null : r.note()));
    }

    // ------------------------------------------------------------------ 적격심사 · 실적 (FR-SAL-006, BR-SAL-014)

    @PostMapping("/opportunities/{id}/simulations")
    @RequirePermission("sal:simulation:create")
    public Map<String, Object> simulate(@PathVariable UUID id, @Valid @RequestBody SimulationRequest r) {
        return qualification.simulate(id, new QualificationService.Simulate(r.criteriaCode(), r.noticeDate(),
                r.estPrice(), r.serviceType(), r.bidRates(), r.engineersQualified(), r.engineersRequired(),
                r.creditGrade(), r.disabledStandardWorkplace()));
    }

    @PostMapping("/performance-records:import")
    @RequirePermission("sal:performance:write")
    public Map<String, Object> importPerformance(@Valid @RequestBody PerformanceImport r) {
        return qualification.importRecords(r.rows().stream().map(x -> new QualificationService.PerformanceInput(
                x.certNo(), x.projectId(), x.facilityId(), x.serviceType(), x.clientName(), x.amountKrw(),
                x.jointShare(), x.startDate(), x.endDate())).toList(), r.source());
    }

    @GetMapping("/performance-records")
    @RequirePermission("sal:performance:read")
    public List<Map<String, Object>> performanceRecords(@RequestParam(required = false) String serviceType) {
        return qualification.performanceRecords(serviceType);
    }

    // ------------------------------------------------------------------ 공고 수집 (FR-SAL-001·002, BR-SAL-013)

    @GetMapping("/bid-notices")
    @RequirePermission("sal:bid:read")
    public List<Map<String, Object>> notices(@RequestParam(required = false) String status,
                                             @RequestParam(required = false) String q,
                                             @RequestParam(defaultValue = "0") int page,
                                             @RequestParam(defaultValue = "50") int size) {
        return collector.notices(status, q, Math.max(page, 0), Math.min(Math.max(size, 1), 500));
    }

    /** 수동 수집(백필) — 설계는 202 Job, 지금은 동기 실행 후 결과(collect_log 1행) */
    @PostMapping("/bid-notices:collect")
    @RequirePermission("sal:bid:collect")
    public Map<String, Object> collect(@Valid @RequestBody CollectRequest r) {
        return BidCollectService.view(collector.backfill(r.from(), r.to()));
    }

    @PostMapping("/bid-notices:collect-now")
    @RequirePermission("sal:bid:collect")
    public Map<String, Object> collectNow() {
        return BidCollectService.view(collector.runScheduled());
    }

    @GetMapping("/collect-logs")
    @RequirePermission("sal:bid:read")
    public List<Map<String, Object>> collectLogs(@RequestParam(defaultValue = "20") int limit) {
        return collector.collectLogs(Math.min(Math.max(limit, 1), 200));
    }

    // ------------------------------------------------------------------ 계약 (FR-SAL-018–020, B5.2, v0.3)

    @PostMapping("/contracts")
    @RequirePermission("sal:agreement:create")
    public ResponseEntity<Map<String, Object>> createContract(@Valid @RequestBody ContractRequest r) {
        Map<String, Object> v = contracts.create(new ContractService.Create(r.opportunityId(), r.estimateId(),
                r.clientId(), r.title(), r.serviceType(), r.contractMethod(), r.negotiatedReason(), r.jointShare(),
                r.amount(), r.currency(), r.fxRate(), r.vatIncluded(), r.signDate(), r.startDate(), r.endDate(),
                r.warrantyMonths(), r.amountDiffReason(), r.scanAttachmentId()));
        return ResponseEntity.created(URI.create("/api/v1/sal/contracts/" + v.get("id")))
                .eTag(IfMatch.etagOf(version(v))).body(v);
    }

    @GetMapping("/contracts/{id}")
    @RequirePermission("sal:agreement:read")
    public ResponseEntity<Map<String, Object>> contract(@PathVariable UUID id) {
        return withEtag(contracts.get(id));
    }

    /** 사내 결재(EXEC) → 고객 서명 대기 + sal.contract.ready-to-sign */
    @PostMapping("/contracts/{id}:approve")
    @RequirePermission("sal:agreement:approve")
    public ResponseEntity<Map<String, Object>> approveContract(@PathVariable UUID id,
                                                               @RequestHeader(value = "If-Match", required = false)
                                                               String ifMatch,
                                                               @RequestBody(required = false) ContractApproveRequest r) {
        return withEtag(contracts.approve(id, IfMatch.require(ifMatch), r == null ? null : r.signDeadline(),
                r == null ? null : r.signers()));
    }

    /** 고객 서명 기록 → 프로젝트 생성 + sal.contract.signed → {contractId, projectId, projectNo} */
    @PostMapping("/contracts/{id}:sign")
    @RequirePermission("sal:agreement:sign")
    public ResponseEntity<Map<String, Object>> signContract(@PathVariable UUID id,
                                                            @RequestHeader(value = "If-Match", required = false)
                                                            String ifMatch,
                                                            @RequestBody(required = false) ContractSignRequest r) {
        return withEtag(contracts.sign(id, IfMatch.require(ifMatch), r == null ? null : r.signedOn()));
    }
}
