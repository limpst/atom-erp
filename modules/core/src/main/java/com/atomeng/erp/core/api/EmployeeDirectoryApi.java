package com.atomeng.erp.core.api;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 직원 대장(core.employee) 공개 포트. 더존 인사 동기화(HRQ Step 2) 전까지는 HRQ 수동 등록이 직원 대장 입력을 겸한다 —
 * HRQ 는 core 테이블에 직접 쓰지 않고 이 포트로 행을 보장한다(같은 트랜잭션 참여).
 */
public interface EmployeeDirectoryApi {

    record EmployeeRecord(UUID id, String empNo, String name, LocalDate hireDate) {}

    /** 없으면 만들고, 이미 있으면(같은 id) 그대로 둔다. @return 새로 만들었으면 true */
    boolean ensureEmployee(EmployeeRecord e);
}
