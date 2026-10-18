package com.atomeng.erp.fcd.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.erp.fcd.api.FcdSubscriptionContracts.CstVoucherSynced;
import com.atomeng.erp.fcd.application.EmployeeLifecycleService;
import com.atomeng.erp.fcd.application.ExpenseService;
import com.atomeng.erp.hrq.api.HrqEvents.EmployeeTerminated;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** FCD 구독: hrq.employee.terminated(hrq::api 공개 타입) · cst.voucher.synced(FCD 쪽 계약) — 컨슈머 멱등 */
@Component
public class FcdEventListeners {

    private final EmployeeLifecycleService employees;
    private final ExpenseService expenses;
    private final ObjectMapper om;

    public FcdEventListeners(EmployeeLifecycleService employees, ExpenseService expenses, ObjectMapper om) {
        this.employees = employees;
        this.expenses = expenses;
        this.om = om;
    }

    @EventListener(condition = "#env.eventType == 'hrq.employee.terminated'")
    @IdempotentListener(consumer = "fcd.employee-terminated")
    public void onEmployeeTerminated(EventEnvelope env) {
        employees.onTerminated(om.convertValue(env.payload(), EmployeeTerminated.class));
    }

    @EventListener(condition = "#env.eventType == 'cst.voucher.synced'")
    @IdempotentListener(consumer = "fcd.voucher-synced")
    public void onVoucherSynced(EventEnvelope env) {
        expenses.onVoucherSynced(om.convertValue(env.payload(), CstVoucherSynced.class));
    }
}
