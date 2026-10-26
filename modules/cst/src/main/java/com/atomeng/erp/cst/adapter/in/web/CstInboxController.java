package com.atomeng.erp.cst.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.cst.application.BudgetInboxService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 웹 "내 할 일" 수신함(읽기 전용) — CST 몫: 결재 가능한 실행예산 판(작성 · 수정자 본인 제외). 기존 결재 권한 그대로. */
@RestController
@RequestMapping("/api/v1/cst/inbox")
class CstInboxController {

    private final BudgetInboxService inbox;

    CstInboxController(BudgetInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/budget-approvals")
    @RequirePermission("cst:budget:approve")
    public List<Map<String, Object>> budgetApprovals() {
        return inbox.pendingApprovals();
    }
}
