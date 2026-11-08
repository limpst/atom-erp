package com.atomeng.erp.cst.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.cst.application.DunningService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/v1/cst/dunning — 독촉 초안 생성(기준일) · 목록 · L2 결재 · 발송 기록 · 보류. 자동 발송은 없다(설계 §9-1). */
@RestController
@RequestMapping("/api/v1/cst")
class DunningController {

    record RunRequest(@NotNull LocalDate asOf) {}
    record ReasonRequest(@NotBlank @Size(max = 300) String reason) {}
    record SentRequest(@NotBlank @Size(max = 20) String channel) {}

    private final DunningService dunning;

    DunningController(DunningService dunning) {
        this.dunning = dunning;
    }

    @PostMapping("/dunning:run")
    @RequirePermission("cst:dunning:write")
    public DunningService.RunResult run(@Valid @RequestBody RunRequest r) {
        return dunning.run(r.asOf());
    }

    @GetMapping("/dunning/notices")
    @RequirePermission("cst:dunning:read")
    public List<Map<String, Object>> notices(@RequestParam(required = false) String status) {
        return dunning.notices(status);
    }

    @PostMapping("/dunning/notices/{id}:approve")
    @RequirePermission("cst:dunning:approve")
    public Map<String, String> approve(@PathVariable UUID id) {
        dunning.approve(id);
        return Map.of("status", "APPROVED");
    }

    @PostMapping("/dunning/notices/{id}:sent")
    @RequirePermission("cst:dunning:write")
    public Map<String, String> sent(@PathVariable UUID id, @Valid @RequestBody SentRequest r) {
        dunning.markSent(id, r.channel());
        return Map.of("status", "SENT");
    }

    @PostMapping("/dunning/invoices/{invoiceId}:hold")
    @RequirePermission("cst:dunning:write")
    public Map<String, String> hold(@PathVariable UUID invoiceId, @Valid @RequestBody ReasonRequest r) {
        dunning.hold(invoiceId, r.reason());
        return Map.of("status", "ON_HOLD");
    }

    @PostMapping("/dunning/invoices/{invoiceId}:release")
    @RequirePermission("cst:dunning:write")
    public Map<String, String> release(@PathVariable UUID invoiceId, @Valid @RequestBody ReasonRequest r) {
        dunning.release(invoiceId, r.reason());
        return Map.of("status", "RELEASED");
    }


}
