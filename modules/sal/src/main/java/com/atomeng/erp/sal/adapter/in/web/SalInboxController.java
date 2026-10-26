package com.atomeng.erp.sal.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.sal.application.DiscountInboxService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 웹 "내 할 일" 수신함(읽기 전용) — SAL 몫: 할인 승인 대기 견적(작성자 · 발행자 본인 제외). 기존 승인 권한 그대로(EXEC ALL). */
@RestController
@RequestMapping("/api/v1/sal/inbox")
class SalInboxController {

    private final DiscountInboxService inbox;

    SalInboxController(DiscountInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/discount-approvals")
    @RequirePermission("sal:discount:approve")
    public List<Map<String, Object>> discountApprovals() {
        return inbox.pendingApprovals();
    }
}
