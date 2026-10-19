package com.atomeng.erp.eqp.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.application.EquipmentService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 교정 현황(웹 장비 목록 칩 · 교정 만료 목록). calStatus:
 * NOT_REQUIRED(교정 불필요 유형) · NONE(유효 교정 이력 없음) · FAILED(유효 교정 이후 FAIL) · EXPIRED(차기 교정일 경과) ·
 * DUE_SOON(dueSoonDays 이내, 기본 60일 = 일일 스캔 첫 임계) · VALID.
 * 판정 경계는 CalibrationValidityService 와 같다(차기 교정일 당일까지 유효).
 */
@RestController
@RequestMapping("/api/v1/eqp/calibration-status")
class CalibrationStatusController {

    record Item(UUID equipmentId, String code, String typeCode, String typeName, String status,
                boolean calibrationRequired, LocalDate nextDueOn, Long daysLeft, String calStatus) {}

    private final EqpJdbc jdbc;
    private final Clock clock;

    CalibrationStatusController(EqpJdbc jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** withinDays 를 주면 교정 필요 장비 중 만료 · 미교정 · N일 이내 도래만(교정 만료 목록) */
    @GetMapping
    @RequirePermission("eqp:calibration:read")
    public List<Item> list(@RequestParam(required = false) Integer withinDays,
                           @RequestParam(defaultValue = "60") int dueSoonDays) {
        LocalDate today = LocalDate.now(clock.withZone(EquipmentService.SEOUL));
        return jdbc.calibrationStatus(today).stream().map(r -> {
            Long left = r.nextDueOn() == null ? null : ChronoUnit.DAYS.between(today, r.nextDueOn());
            String st = !r.calibrationRequired() ? "NOT_REQUIRED"
                    : r.failedAfter() && left != null ? "FAILED"
                    : left == null ? "NONE"
                    : left < 0 ? "EXPIRED"
                    : left <= dueSoonDays ? "DUE_SOON" : "VALID";
            return new Item(r.equipmentId(), r.code(), r.typeCode(), r.typeName(), r.status(),
                    r.calibrationRequired(), r.nextDueOn(), left, st);
        }).filter(i -> withinDays == null || (i.calibrationRequired()
                && (i.daysLeft() == null || i.daysLeft() <= withinDays || "FAILED".equals(i.calStatus()))))
                .toList();
    }
}
