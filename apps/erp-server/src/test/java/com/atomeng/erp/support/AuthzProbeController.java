package com.atomeng.erp.support;

import com.atomeng.common.identity.authz.RequirePermission;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 권한 매트릭스 HTTP 프로브 — 아직 구현되지 않은 모듈(fld·qar·cst·hrq·aip)의 권한 문자열을 대표 엔드포인트로 검증 */
@RestController
public class AuthzProbeController {

    private static final Map<String, Object> OK = Map.of("ok", true);

    @PostMapping("/api/v1/test/fld/projects/{projectId}/defects")
    @RequirePermission(value = "fld:defect:create", projectIdParam = "projectId")
    public Map<String, Object> createDefect(@PathVariable UUID projectId) {
        return OK;
    }

    @PostMapping("/api/v1/test/qar/reviews/{id}:approve")
    @RequirePermission("qar:review:approve")
    public Map<String, Object> approveReview(@PathVariable String id) {
        return OK;
    }

    @GetMapping("/api/v1/test/cst/projects/{projectId}/budget")
    @RequirePermission(value = "cst:budget:read", projectIdParam = "projectId")
    public Map<String, Object> budget(@PathVariable UUID projectId) {
        return OK;
    }

    @GetMapping("/api/v1/test/hrq/engineers")
    @RequirePermission("hrq:engineer:read")
    public Map<String, Object> engineers() {
        return OK;
    }

    @GetMapping("/api/v1/test/hrq/availability")
    @RequirePermission("hrq:availability:read")
    public Map<String, Object> availability() {
        return OK;
    }

    @PostMapping("/api/v1/test/cst/expenses")
    @RequirePermission(value = "cst:expense:create", scopeCheckedInService = true)   // 역할 프로브(EMPLOYEE=SELF)
    public Map<String, Object> expense() {
        return OK;
    }

    @GetMapping("/api/v1/test/aip/audit-logs")
    @RequirePermission("aip:audit-log:read")
    public Map<String, Object> aipAuditLogs() {
        return OK;
    }

    @PostMapping("/api/v1/test/aip/models/{id}:deploy")
    @RequirePermission("aip:model:deploy")
    public Map<String, Object> deployModel(@PathVariable String id) {
        return OK;
    }

    @PostMapping("/api/v1/test/aip/label-tasks")
    @RequirePermission(value = "aip:label-task:create", scopeCheckedInService = true)   // 역할 프로브(LABELER=SELF)
    public Map<String, Object> labelTask() {
        return OK;
    }

    // ---------------------------------------------------------------- CMN-05 실패하는 쪽으로 닫힘

    /** 범위 판정 수단 없음 — PROJECT/CLIENT 범위 호출자는 403, ALL 만 통과 */
    @GetMapping("/api/v1/test/cmn05/undeclared/{id}")
    @RequirePermission("rpt:report:read")
    public Map<String, Object> undeclared(@PathVariable UUID id) {
        return OK;
    }

    @GetMapping("/api/v1/test/cmn05/reports/{id}")
    @RequirePermission(value = "rpt:report:read", scopeResolver = ProbeScopeResolver.class, resourceParam = "id")
    public Map<String, Object> resolvedReport(@PathVariable UUID id) {
        return OK;
    }

    @GetMapping("/api/v1/test/cmn05/timesheets/{id}")
    @RequirePermission(value = "hrq:timesheet:read", scopeResolver = ProbeScopeResolver.class, resourceParam = "id")
    public Map<String, Object> resolvedTimesheet(@PathVariable UUID id) {
        return OK;
    }

    /** SELF 범위는 프로젝트로 판정할 수 없다 → projectIdParam 만 있으면 거부 */
    @GetMapping("/api/v1/test/cmn05/projects/{projectId}/timesheets")
    @RequirePermission(value = "hrq:timesheet:read", projectIdParam = "projectId")
    public Map<String, Object> selfByProject(@PathVariable UUID projectId) {
        return OK;
    }

    @GetMapping("/api/v1/test/cmn05/service-checked/{id}")
    @RequirePermission(value = "rpt:report:read", scopeCheckedInService = true)
    public Map<String, Object> serviceChecked(@PathVariable UUID id) {
        return OK;
    }
}
