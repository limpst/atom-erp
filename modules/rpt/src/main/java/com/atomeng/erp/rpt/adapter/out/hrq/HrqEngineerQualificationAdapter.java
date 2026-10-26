package com.atomeng.erp.rpt.adapter.out.hrq;

import com.atomeng.erp.hrq.api.HrqQueryApi;
import com.atomeng.erp.hrq.api.HrqQueryApi.CredentialView;
import com.atomeng.erp.rpt.application.port.EngineerQualificationPort;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * RPT-07 책임기술자 자격 — hrq::api 자격 기록 중 아래 유형이 기준일에 유효(VALID · EXPIRING, 만료 전)하면 적격.
 * ⚠️ 예시 자격 유형(HRQ credential_type 시드와 같은 코드) — 시특법 책임기술자 요건 확인 후 교체 필요.
 */
@Component
class HrqEngineerQualificationAdapter implements EngineerQualificationPort {

    static final Set<String> RESPONSIBLE_ENGINEER_CODES = Set.of("ARCH_STRUCT_PE", "ARCH_ENG", "SAFETY_DIAG_CERT");

    private final HrqQueryApi hrq;

    HrqEngineerQualificationAdapter(HrqQueryApi hrq) {
        this.hrq = hrq;
    }

    @Override
    public Result responsibleEngineer(UUID employeeId, LocalDate on) {
        for (CredentialView c : hrq.credentialsOf(employeeId)) {
            if (RESPONSIBLE_ENGINEER_CODES.contains(c.typeCode()) && c.validOn(on)) {
                return new Result(true, c.typeCode());
            }
        }
        return new Result(false, "NO_VALID_QUALIFICATION");
    }
}
