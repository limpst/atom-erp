package com.atomeng.erp.ptl.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.ptl.application.PortalAccess;
import com.atomeng.erp.ptl.application.PortalFacilityService;
import com.atomeng.erp.ptl.application.PortalNotificationService;
import com.atomeng.erp.ptl.application.PortalReportService;
import com.atomeng.erp.ptl.application.ServiceRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
 * 고객 포털 API /api/v1/ptl (설계 09 B4 의 외부 API 중 이번 단계 범위 — BFF 분리 전에는 erp-server 가 직접 제공).
 * 운영(OIDC)에서는 보안 체인이 /api/v1/ptl/** 를 CUSTOMER 역할로만 열고, 모든 메서드가 {@link PortalAccess#require()} 로
 * 포털 realm · cid 클레임을 다시 확인한다. 셀 권한은 8.2 표 ptl:share(CUSTOMER = CLIENT 범위).
 * 다른 고객사 자원은 403 이 아니라 404(존재 은닉, TC-PTL-01).
 */
@RestController
@RequestMapping("/api/v1/ptl")
class PortalController {

    record ServiceRequestBody(@NotNull @Pattern(regexp = "QUOTE|EMERGENCY|REINSPECTION|VERIFY_REPAIR") String kind,
                              UUID facilityId, @Size(max = 30) @Pattern(regexp = "[A-Z][A-Z0-9_]*") String serviceType,
                              @Pattern(regexp = "EARTHQUAKE|FIRE|CRACK_GROWTH|LEAK|OTHER") String reasonCode,
                              @NotBlank @Size(max = 4000) String description, @Size(max = 50) String contactName) {}

    private final PortalAccess access;
    private final PortalFacilityService facilities;
    private final PortalReportService reports;
    private final ServiceRequestService requests;
    private final PortalNotificationService notifications;

    PortalController(PortalAccess access, PortalFacilityService facilities, PortalReportService reports,
                     ServiceRequestService requests, PortalNotificationService notifications) {
        this.access = access;
        this.facilities = facilities;
        this.reports = reports;
        this.requests = requests;
        this.notifications = notifications;
    }

    @GetMapping("/me")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    Map<String, Object> me() {
        PortalAccess.Customer c = access.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", c.customerId());
        m.put("actorId", c.actorId());
        return m;
    }

    // ------------------------------------------------------------------ 시설물 · 점검 일정 (fac::api)

    @GetMapping("/buildings")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> buildings() {
        return facilities.buildings(access.require());
    }

    @GetMapping("/buildings/{id}")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    Map<String, Object> building(@PathVariable UUID id) {
        return facilities.building(access.require(), id);
    }

    @GetMapping("/buildings/{id}/grades")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> grades(@PathVariable UUID id) {
        return facilities.grades(access.require(), id);
    }

    @GetMapping("/buildings/{id}/inspection-schedule")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> buildingSchedule(@PathVariable UUID id) {
        return facilities.schedule(access.require(), id);
    }

    @GetMapping("/inspection-schedule")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> schedule() {
        return facilities.scheduleAll(access.require());
    }

    // ------------------------------------------------------------------ 납품 보고서 (rpt::api)

    @GetMapping("/reports")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> reports() {
        return reports.list(access.require());
    }

    @GetMapping("/reports/{id}")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    Map<String, Object> report(@PathVariable UUID id) {
        return reports.get(access.require(), id);
    }

    /** 다운로드마다 워터마크(고객 id · 시각 · 다운로드 id) — 이번 단계는 동기 200(설계 202 Job 은 후속) */
    @PostMapping("/reports/{id}:download")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    ResponseEntity<byte[]> download(@PathVariable UUID id, HttpServletRequest request) {
        PortalReportService.Download d = reports.download(access.require(), id, request.getRemoteAddr());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("Content-Disposition", ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .header("X-Download-Id", d.downloadId().toString())
                .header("X-Content-SHA256", d.sha256())
                .header("Cache-Control", "no-store")
                .body(d.pdf());
    }

    @GetMapping("/downloads")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> downloads() {
        return reports.downloads(access.require());
    }

    // ------------------------------------------------------------------ 서비스 요청 (→ SAL)

    @PostMapping("/service-requests")
    @RequirePermission(value = "ptl:share:create", scopeCheckedInService = true)
    ResponseEntity<Map<String, Object>> submit(@Valid @RequestBody ServiceRequestBody b) {
        Map<String, Object> v = requests.submit(access.require(), new ServiceRequestService.Submit(b.kind(),
                b.facilityId(), b.serviceType(), b.reasonCode(), b.description(), b.contactName()));
        return ResponseEntity.created(URI.create("/api/v1/ptl/service-requests/" + v.get("id"))).body(v);
    }

    @GetMapping("/service-requests")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> serviceRequests() {
        return requests.list(access.require());
    }

    @GetMapping("/service-requests/{id}")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    Map<String, Object> serviceRequest(@PathVariable UUID id) {
        return requests.get(access.require(), id);
    }

    // ------------------------------------------------------------------ 알림 · 공유 영업 문서

    @GetMapping("/notifications")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> notifications(@RequestParam(defaultValue = "false") boolean unread) {
        return notifications.list(access.require(), unread);
    }

    @PostMapping("/notifications/{id}:read")
    @RequirePermission(value = "ptl:share:update", scopeCheckedInService = true)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void markRead(@PathVariable UUID id) {
        notifications.markRead(access.require(), id);
    }

    @GetMapping("/sales-documents")
    @RequirePermission(value = "ptl:share:read", scopeCheckedInService = true)
    List<Map<String, Object>> salesDocuments() {
        return notifications.salesDocuments(access.require());
    }
}
