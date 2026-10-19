package com.atomeng.erp.fac.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 점검 도래 목록(전 시설물) — 웹 "점검 도래" 화면. 시설물별 GET /buildings/{id}/dues 와 같은 권한(fac:due:read).
 * daysLeft = 도래일 − 오늘(Asia/Seoul), 음수면 경과.
 */
@RestController
@RequestMapping("/api/v1/fac/dues")
class DueController {

    record DueItem(UUID id, UUID buildingId, String buildingName, String dongName, String roadAddress,
                   String inspectionType, LocalDate baseDate, LocalDate dueDate, String status, long daysLeft) {}

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final FacJdbc jdbc;
    private final Clock clock;

    DueController(FacJdbc jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** withinDays: 오늘부터 N일 안(경과분 포함). 생략하면 전체. 최대 500행 */
    @GetMapping
    @RequirePermission("fac:due:read")
    public List<DueItem> list(@RequestParam(required = false) Integer withinDays,
                              @RequestParam(defaultValue = "200") int limit) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        LocalDate before = withinDays == null ? null : today.plusDays(Math.max(0, Math.min(withinDays, 36_500)));
        return jdbc.dueList(before, Math.min(Math.max(limit, 1), 500)).stream()
                .map(d -> new DueItem(d.id(), d.buildingId(), d.buildingName(), d.dongName(), d.roadAddress(),
                        d.inspectionType(), d.baseDate(), d.dueDate(), d.status(),
                        ChronoUnit.DAYS.between(today, d.dueDate())))
                .toList();
    }
}
