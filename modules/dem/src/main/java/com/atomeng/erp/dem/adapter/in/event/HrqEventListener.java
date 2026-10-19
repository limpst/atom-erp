package com.atomeng.erp.dem.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.dem.application.SupervisorService;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * HRQ 이벤트 구독 (설계 10 v0.3 B6 구독 · A8 감리자 공백) — hrq::api 이벤트 레코드로만 연결.
 * <ul>
 *   <li>{@code hrq.credential.expired} → 만료 자격 차단(이후 감리자 지정 거부) + 해당 감리 패키지 공백 경고</li>
 *   <li>{@code hrq.employee.terminated} → 퇴사자 지정 차단 + 진행 중 지정 해제 + 공백 경고</li>
 * </ul>
 * core.processed_event 로 멱등(소비자 이름별).
 */
@Component
public class HrqEventListener {

    public static final String CREDENTIAL_CONSUMER = "dem.credential-expired";
    public static final String TERMINATED_CONSUMER = "dem.employee-terminated";

    private final SupervisorService supervisors;
    private final ObjectMapper om;

    public HrqEventListener(SupervisorService supervisors, ObjectMapper om) {
        this.supervisors = supervisors;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'hrq.credential.expired'")
    @IdempotentListener(consumer = CREDENTIAL_CONSUMER)
    public void onCredentialExpired(EventEnvelope env) {
        supervisors.onCredentialExpired(om.convertValue(env.payload(), HrqEvents.CredentialExpired.class));
    }

    @EventListener(condition = "#env.eventType == 'hrq.employee.terminated'")
    @IdempotentListener(consumer = TERMINATED_CONSUMER)
    public void onEmployeeTerminated(EventEnvelope env) {
        supervisors.onEmployeeTerminated(om.convertValue(env.payload(), HrqEvents.EmployeeTerminated.class));
    }
}
