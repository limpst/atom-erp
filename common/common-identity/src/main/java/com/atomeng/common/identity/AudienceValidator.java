package com.atomeng.common.identity;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 토큰 audience 검증 (보안 검토 CMN-06) — JWT {@code aud} 에 허용 audience 가 하나라도 있어야 한다.
 * Keycloak 은 클라이언트 scope 의 audience 매퍼(deploy/keycloak, {@code atom-erp-api})로 넣는다.
 * 다른 클라이언트 · 다른 서비스용으로 발급된 토큰을 이 API 에 재사용하는 것을 막는다.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID = new OAuth2Error("invalid_token", "허용되지 않은 audience", null);

    private final Set<String> allowed;

    public AudienceValidator(Collection<String> allowed) {
        this.allowed = Set.copyOf(allowed);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        List<String> aud = jwt.getAudience();
        if (aud != null && aud.stream().anyMatch(allowed::contains)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID);
    }
}
