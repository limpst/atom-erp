package com.atomeng.erp.sal.application.port;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * BR-SAL-011 계약 승인 시 HRQ 기술자 투입 가능성 사전 검사 포트(설계 B5.2). SAL 은 hrq 모듈에 의존하지 않으므로(allowedDependencies =
 * core::api · fac::api) 포트 + 스텁 어댑터(항상 OK)로 두고, hrq::api 조회 포트 공개 후 어댑터를 교체한다(01-IMPL 리스크 표).
 */
public interface AssignmentFeasibilityPort {

    record Conflict(UUID employeeId, String reason) {}

    record Result(boolean ok, List<Conflict> conflicts) {
        public static Result okResult() {
            return new Result(true, List.of());
        }
    }

    Result check(UUID contractId, UUID estimateId, LocalDate from, LocalDate to);
}
