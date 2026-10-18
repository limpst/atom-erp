package com.atomeng.erp.core.adapter.in.web;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.audit.Audited;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.pii.Pii;
import com.atomeng.common.identity.authz.RequirePermission;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 직원 민감정보 조회 (설계 8.3-4: 장애 여부 등 민감정보 조회는 VIEW_PII 감사 필수) — TC-CORE-010.
 * 응답은 권한 있는 사용자에게 원문으로 주되, 감사 로그(after_json)에는 @Pii 마스킹 값만 남는다.
 */
@RestController
@RequestMapping("/api/v1/core/employees")
class EmployeeController {

    record EmployeeSensitiveResponse(UUID id, String empNo, @Pii(Pii.Kind.NAME) String name,
                                     @Pii boolean disabledWorker, String status) {}

    private final JdbcTemplate jdbc;

    EmployeeController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/{id}/sensitive")
    @RequirePermission("core:employee-sensitive:read")
    @Audited(module = "CORE", action = AuditLog.Action.VIEW_PII, targetType = "Employee", targetIdExpr = "#id")
    public EmployeeSensitiveResponse sensitive(@PathVariable UUID id) {
        return jdbc.query("""
                SELECT id, emp_no, name, is_disabled_worker, status FROM core.employee
                 WHERE id = ? AND deleted_at IS NULL""",
                (rs, i) -> new EmployeeSensitiveResponse(rs.getObject("id", UUID.class), rs.getString("emp_no"),
                        rs.getString("name"), rs.getBoolean("is_disabled_worker"), rs.getString("status")), id)
                .stream().findFirst()
                .orElseThrow(() -> new DomainException(CoreErrors.NOT_FOUND, "직원을 찾을 수 없습니다: " + id));
    }
}
