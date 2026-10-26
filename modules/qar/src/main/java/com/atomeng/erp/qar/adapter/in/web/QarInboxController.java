package com.atomeng.erp.qar.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.qar.application.ReviewInboxService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 웹 "내 할 일" 수신함(읽기 전용) — QAR 몫: 나에게 배정된 열린 검토 단계. 범위는 서비스가 행마다 검사한다. */
@RestController
@RequestMapping("/api/v1/qar/inbox")
class QarInboxController {

    private final ReviewInboxService inbox;

    QarInboxController(ReviewInboxService inbox) {
        this.inbox = inbox;
    }

    @GetMapping("/assignments")
    @RequirePermission(value = "qar:review:read", scopeCheckedInService = true)
    public List<Map<String, Object>> assignments() {
        return inbox.myAssignments();
    }
}
