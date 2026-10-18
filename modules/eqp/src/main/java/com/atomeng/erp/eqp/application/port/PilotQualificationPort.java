package com.atomeng.erp.eqp.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * 조종자 자격 조회 포트 (BR-EQP-007, 04-IMPL Step 11 HRQ {@code PilotQualificationPort}).
 * 자격 종별(MTOW 구간) 판정은 HRQ 규칙 테이블 몫 — EQP 는 결과만 받는다. 조회 장애는 예외로 알리고 EQP 가 Fail-closed 처리.
 */
public interface PilotQualificationPort {

    enum Status { VALID, INVALID }

    record Result(Status status, String detail) {}

    /** @throws RuntimeException 조회 불가(HRQ 장애 · 미연동) → 호출자는 비행 등록을 보류(422) */
    Result check(UUID pilotId, BigDecimal mtowKg, LocalDate on);
}
