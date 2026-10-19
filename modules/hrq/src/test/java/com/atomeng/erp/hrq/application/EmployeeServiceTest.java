package com.atomeng.erp.hrq.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.core.api.EmployeeDirectoryApi;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc.EmployeeRow;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 전체 업무 흐름 시험에서 드러난 연결 누락 회귀 — HRQ 수동 등록이 core 직원 대장 행을 먼저 보장해야 한다
 * (예전: core.employee 를 만들 공개 경로가 없어 hrq.employee FK 위반으로 HTTP 등록 불가).
 */
class EmployeeServiceTest {

    @Test
    @DisplayName("register: core::api EmployeeDirectoryApi.ensureEmployee(id · 사번 · 이름 · 입사일) → hrq.employee INSERT 순서")
    void registerEnsuresCoreEmployeeFirst() {
        HrqJdbc jdbc = mock(HrqJdbc.class);
        EmployeeDirectoryApi directory = mock(EmployeeDirectoryApi.class);
        CurrentActor actor = mock(CurrentActor.class);
        UUID admin = UUID.randomUUID();
        when(actor.actorIdOrSystem()).thenReturn(admin);
        UUID id = UUID.randomUUID();
        LocalDate hired = LocalDate.of(2020, 1, 1);
        when(jdbc.employee(id)).thenReturn(Optional.of(new EmployeeRow(id, "G-1", "합성기술자", "ARCH", "FULLTIME",
                "ACTIVE", false, hired, null, null, 0)));
        var service = new EmployeeService(jdbc, mock(OutboxPublisher.class), mock(AuditLog.class), actor, directory);

        EmployeeRow row = service.register(new EmployeeService.Register(id, "G-1", "합성기술자", "ARCH", "FULLTIME",
                false, hired, "INTER", hired));

        assertThat(row.id()).isEqualTo(id);
        ArgumentCaptor<EmployeeDirectoryApi.EmployeeRecord> rec = ArgumentCaptor.forClass(EmployeeDirectoryApi.EmployeeRecord.class);
        var order = inOrder(directory, jdbc);
        order.verify(directory).ensureEmployee(rec.capture());
        order.verify(jdbc).insertEmployee(any(EmployeeRow.class), eq(admin));
        order.verify(jdbc).insertGrade(id, "INTER", hired, null, admin);
        assertThat(rec.getValue()).isEqualTo(new EmployeeDirectoryApi.EmployeeRecord(id, "G-1", "합성기술자", hired));
    }
}
