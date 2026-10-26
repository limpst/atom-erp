package com.atomeng.erp.hrq.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.hrq.application.TimesheetInboxService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 웹 "내 할 일" 수신함(읽기 전용) — HRQ 몫. 쓰기 · 마이그레이션 · 권한 시드 변경 없음(기존 승인 권한 그대로).
 * 범위: 서비스가 "내가 RESP 인 프로젝트 · 본인 제외" 로 거른다(scopeCheckedInService).
 */
@RestController
@RequestMapping("/api/v1/hrq/inbox")
class HrqInboxController {

    private final TimesheetInboxService inbox;

    HrqInboxController(TimesheetInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/timesheet-approvals")
    @RequirePermission(value = "hrq:timesheet:approve", scopeCheckedInService = true)
    public List<Map<String, Object>> timesheetApprovals() {
        return inbox.pendingApprovals();
    }
}
