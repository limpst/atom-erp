package com.atomeng.erp.dem.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.dem.application.DemQueryService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 웹 화면용 DEM 목록 조회 (읽기 전용). 단건 조회와 같은 권한을 쓴다. */
@RestController
@RequestMapping("/api/v1/dem")
class DemQueryController {

    private final DemQueryService query;

    DemQueryController(DemQueryService query) {
        this.query = query;
    }

    @GetMapping("/projects")
    @RequirePermission("dem:project:read")
    public List<Map<String, Object>> projects() {
        return query.projects();
    }

    @GetMapping("/work-packages/{workPackageId}/daily-logs")
    @RequirePermission(value = "dem:supervision-log:read", scopeCheckedInService = true)
    public List<Map<String, Object>> dailyLogs(@PathVariable UUID workPackageId) {
        return query.dailyLogs(workPackageId);
    }

    @GetMapping("/work-packages/{workPackageId}/documents")
    @RequirePermission("dem:revision:read")
    public List<Map<String, Object>> documents(@PathVariable UUID workPackageId) {
        return query.documents(workPackageId);
    }

    @GetMapping("/work-packages/{workPackageId}/transmittals")
    @RequirePermission("dem:revision:read")
    public List<Map<String, Object>> transmittals(@PathVariable UUID workPackageId) {
        return query.transmittals(workPackageId);
    }
}
