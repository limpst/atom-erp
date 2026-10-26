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
 * <p>보안 검토 SAL-08: 운영 프로필(prod · production)에서 이 스텁이 만들어지면 기동 실패(BR-SAL-011 미검증 방지).
 */
@Component
public class StubAssignmentFeasibilityAdapter implements AssignmentFeasibilityPort {

    private static final Logger log = LoggerFactory.getLogger(StubAssignmentFeasibilityAdapter.class);

    public StubAssignmentFeasibilityAdapter(org.springframework.core.env.Environment env) {
        if (com.atomeng.common.identity.ProductionGuards.isProd(env)) {
            throw new IllegalStateException("운영 프로필(" + String.join(",", env.getActiveProfiles())
                    + ")에서 투입 가능성 검사 스텁(StubAssignmentFeasibilityAdapter)은 허용되지 않습니다 — "
                    + "실제 HRQ 투입 가능성 어댑터가 필요합니다(SAL-08)");
        }
    }

    @Override
    public Result check(UUID contractId, UUID estimateId, LocalDate from, LocalDate to) {
        log.warn("BR-SAL-011 HRQ 투입 가능성 검사 스텁(항상 OK): contract={} {}~{}", contractId, from, to);
        return Result.okResult();
    }
}
