package com.atomeng.erp.eqp.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.common.web.http.PageResponse;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import com.atomeng.erp.eqp.application.CalibrationScanService;
import com.atomeng.erp.eqp.application.CalibrationService;
import com.atomeng.erp.eqp.application.CalibrationValidityService;
import com.atomeng.erp.eqp.application.CheckoutService;
import com.atomeng.erp.eqp.application.DroneService;
import com.atomeng.erp.eqp.application.EquipmentService;
import com.atomeng.erp.eqp.application.ReservationService;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/v1/eqp (설계 04 B4). POST 는 Idempotency-Key(공통 필터, 현장 앱 필수), 장비 단건 · 수정 · 폐기는 ETag/If-Match,
 * 감사 로그는 서비스에서 동기 기록(등록 · 수정 · 폐기 · 교정 · Override · 라벨 · 충돌 · 드론).
 */
@RestController
@RequestMapping("/api/v1/eqp")
class EqpController {

    record RegisterRequest(@NotBlank @Size(max = 20) String typeCode, @NotBlank @Size(max = 100) String serialNo,
                           @Size(max = 100) String model, @Size(max = 100) String manufacturer,
                           @Pattern(regexp = "OWNED|RENTED|LEASED") String ownership, UUID parentId,
                           Map<String, Object> spec, @Size(max = 40) String nfcUid, @Size(max = 100) String homeLocation,
                           LocalDate acquiredOn, @PositiveOrZero BigDecimal acquisitionCost,
                           @Positive Integer usefulLifeMonths, @PositiveOrZero BigDecimal salvageValue,
                           @Pattern(regexp = "SL|DB") String depreciationMethod, @PositiveOrZero BigDecimal dailyRate) {}

    record ChangeRequest(@Size(max = 100) String model, @Size(max = 100) String manufacturer,
                         @Size(max = 100) String homeLocation, @PositiveOrZero BigDecimal dailyRate,
                         @Size(max = 40) String nfcUid, Map<String, Object> spec) {}

    record DisposeRequest(LocalDate disposedOn, @PositiveOrZero BigDecimal disposalAmount,
                          @NotBlank @Size(max = 500) String reason) {}

    record LabelRequest(boolean rotate) {}

    record CalibrationRequest(@NotNull LocalDate calibratedOn, LocalDate nextDueOn,
                              @NotNull @Pattern(regexp = "PASS|FAIL|LIMITED") String result, @Size(max = 50) String certNo,
                              @Size(max = 100) String labName, @Size(max = 30) String labAccreditationNo,
                              Map<String, Object> uncertainty, UUID certAttachmentId,
                              @Pattern(regexp = "^[0-9a-f]{64}$") String certSha256, @PositiveOrZero BigDecimal cost) {}

    /** EQP-04: projectId · requestedBy(예외를 요청한 현장 PM/ENG, 승인 QA 와 다른 사람) 필수 */
    record OverrideRequest(@NotNull UUID projectId, @NotNull UUID requestedBy,
                           @NotBlank @Size(min = 5, max = 1000) String reason,
                           @NotNull LocalDate validFrom, @NotNull LocalDate validTo) {}

    record ReservationRequest(@NotNull UUID equipmentId, @NotNull UUID projectId, @NotNull LocalDate from,
                              @NotNull LocalDate to, @Size(max = 100) String base) {}

    record Location(@DecimalMin("-90") @DecimalMax("90") BigDecimal lat,
                    @DecimalMin("-180") @DecimalMax("180") BigDecimal lng) {}

    record CheckoutRequest(@NotNull UUID clientOpId, @NotBlank @Size(max = 200) String qrToken, @NotNull UUID projectId,
                           UUID reservationId, Instant outAt, @Valid Location location,
                           @NotNull Map<String, Object> preCheck) {}

    record ReturnRequest(Instant inAt, @Valid Location location, Map<String, Object> postCheck) {}

    record DroneProfileRequest(@NotBlank @Size(max = 30) String registrationNo, @NotNull @Positive BigDecimal mtowKg,
                               @Size(max = 50) String insurancePolicyNo, LocalDate insuranceExpiresOn,
                               Map<String, Object> batteryCycles) {}

    record ApprovalRequest(@NotNull UUID projectId, @NotBlank @Size(max = 50) String approvalNo,
                           @NotEmpty List<double[]> area, @Positive Integer maxAltM, @NotNull Instant validFrom,
                           @NotNull Instant validTo, @Size(max = 50) String photoPermitNo) {}

    /** EQP-03: pilotId 는 요청자 본인(생략하면 본인), 다른 사람 대리 등록은 ADMIN 만 */
    record FlightLogRequest(@NotNull UUID projectId, UUID approvalId, UUID pilotId, @NotNull Instant startedAt,
                            @NotNull Instant endedAt, @NotEmpty List<double[]> track, BigDecimal maxAltM,
                            UUID rawLogAttachmentId) {}

    private final EquipmentService equipment;
    private final CalibrationService calibrations;
    private final CalibrationValidityService validity;
    private final ReservationService reservations;
    private final CheckoutService checkouts;
    private final CalibrationScanService scans;
    private final DroneService drones;

    EqpController(EquipmentService equipment, CalibrationService calibrations, CalibrationValidityService validity,
                  ReservationService reservations, CheckoutService checkouts, CalibrationScanService scans,
                  DroneService drones) {
        this.equipment = equipment;
        this.calibrations = calibrations;
        this.validity = validity;
        this.reservations = reservations;
        this.checkouts = checkouts;
        this.scans = scans;
        this.drones = drones;
    }

    // ------------------------------------------------------------------ 장비 대장 (FR-EQP-001)

    @PostMapping("/equipment")
    @RequirePermission("eqp:equipment:create")
    public ResponseEntity<Map<String, Object>> register(@Valid @RequestBody RegisterRequest r) {
        EquipmentRow e = equipment.register(new EquipmentService.Register(r.typeCode(), r.serialNo(), r.model(),
                r.manufacturer(), r.ownership(), r.parentId(), r.spec(), r.nfcUid(), r.homeLocation(), r.acquiredOn(),
                r.acquisitionCost(), r.usefulLifeMonths(), r.salvageValue(), r.depreciationMethod(), r.dailyRate()));
        return ResponseEntity.created(URI.create("/api/v1/eqp/equipment/" + e.id()))
                .eTag(IfMatch.etagOf(e.version())).body(equipment.view(e));
    }

    @GetMapping("/equipment")
    @RequirePermission("eqp:equipment:read")
    public PageResponse<Map<String, Object>> list(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String type,
                                                  @RequestParam(required = false) Integer calDueWithinDays,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 500);
        long total = equipment.count(status, type, calDueWithinDays);
        var rows = equipment.list(status, type, calDueWithinDays, p, s).stream().map(equipment::view).toList();
        return new PageResponse<>(rows, p, s, total, (int) ((total + s - 1) / s));
    }

    @GetMapping("/equipment/{id}")
    @RequirePermission("eqp:equipment:read")
    public ResponseEntity<Map<String, Object>> get(@PathVariable UUID id) {
        Map<String, Object> card = equipment.card(id);
        return ResponseEntity.ok().eTag(IfMatch.etagOf((Long) card.get("version"))).body(card);
    }

    @PatchMapping("/equipment/{id}")
    @RequirePermission("eqp:equipment:update")
    public ResponseEntity<Map<String, Object>> change(@PathVariable UUID id,
                                                      @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                      @Valid @RequestBody ChangeRequest r) {
        EquipmentRow e = equipment.change(id, IfMatch.require(ifMatch), new EquipmentService.Change(r.model(),
                r.manufacturer(), r.homeLocation(), r.dailyRate(), r.nfcUid(), r.spec()));
        return ResponseEntity.ok().eTag(IfMatch.etagOf(e.version())).body(equipment.view(e));
    }

    /** BR-EQP-008 폐기(삭제 아님) */
    @PostMapping("/equipment/{id}:dispose")
    @RequirePermission("eqp:equipment:delete")
    public ResponseEntity<Map<String, Object>> dispose(@PathVariable UUID id,
                                                       @RequestHeader(value = "If-Match", required = false) String ifMatch,
                                                       @Valid @RequestBody DisposeRequest r) {
        EquipmentRow e = equipment.dispose(id, IfMatch.require(ifMatch), r.disposedOn(), r.disposalAmount(), r.reason());
        return ResponseEntity.ok().eTag(IfMatch.etagOf(e.version())).body(equipment.view(e));
    }

    /** 특정일 유효성 판정 (B4 예시) — 기본 오늘(KST) */
    @GetMapping("/equipment/{id}/validity")
    @RequirePermission("eqp:equipment:read")
    public ValidityResult validity(@PathVariable UUID id, @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false) UUID projectId) {
        return validity.validate(id, date == null ? equipment.today() : date, projectId);
    }

    // ------------------------------------------------------------------ QR (FR-EQP-002)

    @PostMapping("/equipment/{id}/labels")
    @RequirePermission("eqp:label:issue")
    public Map<String, Object> label(@PathVariable UUID id, @RequestBody(required = false) LabelRequest r) {
        return equipment.issueLabel(id, r != null && r.rotate());
    }

    @GetMapping("/scan/{qrToken:.+}")
    @RequirePermission("eqp:equipment:read")
    public Map<String, Object> scan(@PathVariable String qrToken) {
        return equipment.scan(qrToken);
    }

    // ------------------------------------------------------------------ 검교정 (FR-EQP-003·004·005)

    @PostMapping("/equipment/{id}/calibrations")
    @RequirePermission("eqp:calibration:write")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> calibrate(@PathVariable UUID id, @Valid @RequestBody CalibrationRequest r) {
        return calibrations.record(id, new CalibrationService.Record(r.calibratedOn(), r.nextDueOn(), r.result(),
                r.certNo(), r.labName(), r.labAccreditationNo(), r.uncertainty(), r.certAttachmentId(), r.certSha256(),
                r.cost()));
    }

    @GetMapping("/equipment/{id}/calibrations")
    @RequirePermission("eqp:calibration:read")
    public List<Map<String, Object>> calibrations(@PathVariable UUID id) {
        return calibrations.list(id);
    }

    @PostMapping("/equipment/{id}/calibration-overrides")
    @RequirePermission("eqp:override:approve")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> override(@PathVariable UUID id, @Valid @RequestBody OverrideRequest r) {
        return calibrations.override(id, r.projectId(), r.requestedBy(), r.reason(), r.validFrom(), r.validTo());
    }

    /** 운영 수동 실행(배치 재실행 — 멱등) */
    @PostMapping("/calibration-scan:run")
    @RequirePermission("eqp:scan:run")
    public CalibrationScanService.ScanResult runScan(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return scans.scan(date == null ? equipment.today() : date);
    }

    // ------------------------------------------------------------------ 예약 (FR-EQP-006)

    @PostMapping("/reservations")
    @RequirePermission(value = "eqp:reservation:create", scopeCheckedInService = true)   // EQP-01 본문 projectId → EqpAccess
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> reserve(@Valid @RequestBody ReservationRequest r) {
        return reservations.request(r.equipmentId(), r.projectId(), r.from(), r.to(), r.base());
    }

    @PostMapping("/reservations/{id}:approve")
    @RequirePermission(value = "eqp:reservation:approve", scopeCheckedInService = true)   // EQP-01 예약 프로젝트 PM · 본인 승인 금지
    public Map<String, Object> approve(@PathVariable UUID id) {
        return reservations.approve(id);
    }

    @PostMapping("/reservations/{id}:cancel")
    @RequirePermission(value = "eqp:reservation:create", scopeCheckedInService = true)   // EQP-01 신청자 또는 승인 권한자
    public Map<String, Object> cancel(@PathVariable UUID id) {
        return reservations.cancel(id);
    }

    @GetMapping("/reservations")
    @RequirePermission("eqp:reservation:read")
    public List<Map<String, Object>> reservations(@RequestParam(required = false) UUID equipmentId,
                                                  @RequestParam(required = false) UUID projectId) {
        return reservations.list(equipmentId, projectId);
    }

    @GetMapping("/reservations/availability")
    @RequirePermission("eqp:reservation:read")
    public List<Map<String, Object>> availability(@RequestParam String type, @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                  @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                  @RequestParam(required = false) UUID projectId,
                                                  @RequestParam(required = false) String base) {
        return reservations.availability(type, from, to, projectId, base);
    }

    // ------------------------------------------------------------------ 반출 · 반납 (FR-EQP-007·008)

    /** 200 정상 · 재전송(Idempotent: clientOpId) / 202 오프라인 충돌 기록 */
    @PostMapping("/checkouts")
    @RequirePermission(value = "eqp:checkout:create", scopeCheckedInService = true)   // EQP-01 본문 projectId → EqpAccess
    public ResponseEntity<Map<String, Object>> checkout(@Valid @RequestBody CheckoutRequest r) {
        var res = checkouts.checkout(new CheckoutService.Checkout(r.clientOpId(), r.qrToken(), r.projectId(),
                r.reservationId(), r.outAt(), r.location() == null ? null : r.location().lat(),
                r.location() == null ? null : r.location().lng(), r.preCheck()));
        var body = new java.util.LinkedHashMap<>(res.body());
        body.put("result", res.status());
        return ResponseEntity.status("CONFLICT_RECORDED".equals(res.status()) ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(body);
    }

    @PostMapping("/checkouts/{id}/return")
    @RequirePermission(value = "eqp:checkout:create", scopeCheckedInService = true)   // EQP-01 반출 프로젝트 → EqpAccess
    public Map<String, Object> returnEquipment(@PathVariable UUID id, @Valid @RequestBody(required = false) ReturnRequest r) {
        ReturnRequest q = r == null ? new ReturnRequest(null, null, null) : r;
        return checkouts.returnEquipment(id, new CheckoutService.Return(q.inAt(),
                q.location() == null ? null : q.location().lat(), q.location() == null ? null : q.location().lng(),
                q.postCheck()));
    }

    // ------------------------------------------------------------------ 드론 (FR-EQP-016·017·018)

    @PutMapping("/drones/{id}/profile")
    @RequirePermission("eqp:drone:write")
    public Map<String, Object> droneProfile(@PathVariable UUID id, @Valid @RequestBody DroneProfileRequest r) {
        return drones.upsertProfile(id, new DroneService.Profile(r.registrationNo(), r.mtowKg(), r.insurancePolicyNo(),
                r.insuranceExpiresOn(), r.batteryCycles()));
    }

    @PostMapping("/drones/{id}/flight-approvals")
    @RequirePermission("eqp:flight-approval:write")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> flightApproval(@PathVariable UUID id, @Valid @RequestBody ApprovalRequest r) {
        return drones.addApproval(id, new DroneService.Approval(r.projectId(), r.approvalNo(), r.area(), r.maxAltM(),
                r.validFrom(), r.validTo(), r.photoPermitNo()));
    }

    @PostMapping("/drones/{id}/flight-logs")
    @RequirePermission("eqp:flight:write")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> flightLog(@PathVariable UUID id, @Valid @RequestBody FlightLogRequest r) {
        return drones.logFlight(id, new DroneService.Flight(r.projectId(), r.approvalId(), r.pilotId(), r.startedAt(),
                r.endedAt(), r.track(), r.maxAltM(), r.rawLogAttachmentId()));
    }

    @GetMapping("/drones/{id}/flight-logs")
    @RequirePermission("eqp:flight:read")
    public List<Map<String, Object>> flightLogs(@PathVariable UUID id) {
        return drones.flights(id);
    }
}
