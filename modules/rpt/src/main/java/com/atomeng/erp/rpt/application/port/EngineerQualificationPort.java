package com.atomeng.erp.rpt.application.port;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 책임기술자 자격 확인 포트(보안 검토 2026-10-19 RPT-07). 구현은 hrq::api {@code HrqQueryApi.credentialsOf} 로
 * 승인 · 유효한 자격을 본다(adapter.out.hrq). 실패하는 쪽으로 닫힘: 자격 기록이 없으면 유효하지 않다.
 */
public interface EngineerQualificationPort {

    /** valid = 기준일에 유효한 책임기술자 자격 보유, basis = 판정 근거(자격 코드 또는 사유) */
    record Result(boolean valid, String basis) {}

    Result responsibleEngineer(UUID employeeId, LocalDate on);
}
