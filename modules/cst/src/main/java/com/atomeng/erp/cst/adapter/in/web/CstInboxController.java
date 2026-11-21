package com.atomeng.erp.cst.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.cst.application.AllocationInboxService;
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
    private final AllocationInboxService allocations;

    CstInboxController(BudgetInboxService inbox, AllocationInboxService allocations) {
        this.inbox = inbox;
        this.allocations = allocations;
    }

    @GetMapping("/budget-approvals")
    @RequirePermission("cst:budget:approve")
    public List<Map<String, Object>> budgetApprovals() {
        return inbox.pendingApprovals();
    }

    /** 확정 대기 배분 제안(제안자 본인 제외) — 경영지원 · 임원 */
    @GetMapping("/allocation-proposals")
    @RequirePermission("cst:allocation:confirm")
    public List<Map<String, Object>> allocationProposals() {
        return allocations.pendingProposals();
    }
}
