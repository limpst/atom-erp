package com.atomeng.erp.eqp.adapter.out.hrq;

import com.atomeng.erp.eqp.application.port.PilotQualificationPort;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * HRQ 연동 전 스텁 (04-IMPL 0.3: "Stub 구현(항상 VALID) + Feature flag eqp.drone.hrq-check=false").
 * {@code atom.eqp.drone.hrq-check=true} 인데 실제 HRQ 어댑터가 없으면 조회 불가 → EQP 가 Fail-closed(422) 로 처리한다.
 * EQP 는 hrq 모듈 타입에 의존하지 않는다(allowedDependencies = core::api). 실제 연동은 hrq::api 조회 포트 공개 후 어댑터 교체.
 */
@Component
public class StubPilotQualificationAdapter implements PilotQualificationPort {

    private final boolean hrqCheck;

    public StubPilotQualificationAdapter(@Value("${atom.eqp.drone.hrq-check:false}") boolean hrqCheck) {
        this.hrqCheck = hrqCheck;
    }

    @Override
    public Result check(UUID pilotId, BigDecimal mtowKg, LocalDate on) {
        if (hrqCheck) {
            throw new IllegalStateException("HRQ 조종자 자격 조회 어댑터 미연동");
        }
        return new Result(Status.VALID, "STUB(hrq-check=false)");
    }
}
