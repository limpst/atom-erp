package com.atomeng.erp.fac.application.port;

import com.atomeng.erp.fac.domain.register.RegisterModels.NormalizedAddress;
import com.atomeng.erp.fac.domain.register.RegisterModels.Pnu;
import com.atomeng.erp.fac.domain.register.RegisterModels.RegisterLookupResult;
import java.util.Optional;

/** 외부 공공 API 포트 (구현: adapter.out.external — ACL) */
public final class RegisterPorts {

    private RegisterPorts() {}

    public interface AddressPort {
        /** 도로명/지번 → 정규화 + PNU. 결과 없음 = empty, 장애 = {@link ExternalUnavailable} */
        Optional<NormalizedAddress> normalize(String keyword);
    }

    public interface BuildingRegisterPort {
        /** 장애·쿼터 초과 시 예외 대신 {@code available=false} 결과(수기 모드 진행) */
        RegisterLookupResult lookup(Pnu pnu);
    }

    public static class ExternalUnavailable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public ExternalUnavailable(String reason, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
