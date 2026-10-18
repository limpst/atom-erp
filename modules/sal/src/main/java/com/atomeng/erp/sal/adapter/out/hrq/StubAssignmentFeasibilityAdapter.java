package com.atomeng.erp.sal.adapter.out.hrq;

import com.atomeng.erp.sal.application.port.AssignmentFeasibilityPort;
import java.time.LocalDate;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HRQ 연동 전 스텁 — 항상 OK + 경고 로그(01-IMPL 리스크 표 "HRQ 검사 포트는 스텁"). SAL 은 hrq 모듈 타입에 의존하지 않는다.
 * 최종 배정 충돌은 HRQ 가 sal.contract.signed 수신 후 확정한다(01-IMPL Step 9 팁).
 */
@Component
public class StubAssignmentFeasibilityAdapter implements AssignmentFeasibilityPort {

    private static final Logger log = LoggerFactory.getLogger(StubAssignmentFeasibilityAdapter.class);

    @Override
    public Result check(UUID contractId, UUID estimateId, LocalDate from, LocalDate to) {
        log.warn("BR-SAL-011 HRQ 투입 가능성 검사 스텁(항상 OK): contract={} {}~{}", contractId, from, to);
        return Result.okResult();
    }
}
