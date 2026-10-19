package com.atomeng.common.identity;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param enabled true(기본) = Keycloak OIDC 리소스 서버. false = 개발/테스트용 헤더 인증
 *                (X-Actor-Id, X-Roles, X-Client-Id, X-Client-Org-Id). 운영 프로필에서 false 금지.
 * @param issuers 신뢰 issuer 목록 (atom-internal, atom-portal Realm, 설계 8.1)
 * @param audiences 허용 토큰 audience(보안 검토 CMN-06). OIDC 모드에서 비어 있으면 기동 거부
 */
@ConfigurationProperties("atom.security")
public record AtomSecurityProperties(Boolean enabled, List<String> issuers, List<String> audiences) {

    public AtomSecurityProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        issuers = issuers == null ? List.of() : List.copyOf(issuers);
        audiences = audiences == null ? List.of() : List.copyOf(audiences);
    }
}
