package com.atomeng.erp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.Role;
import com.atomeng.erp.support.IntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;

/**
 * 운영 모드(atom.security.enabled=true) 보안 체인 스텁 검증 — Keycloak 없이 기동되고(issuer 지연 조회),
 * 토큰 없는 요청은 401 problem+json, 인증된 주체는 통과.
 */
@TestPropertySource(properties = {"atom.security.enabled=true",
        "atom.fcd.token-hmac.v1=test-only-oidc-mode-hmac-key-0123456789abcdef"})
class SecurityOidcModeTest extends IntegrationTestBase {

    @Test
    @DisplayName("Step4: 토큰 없음 → 401 CORE-401-001 (problem+json)")
    void noTokenIs401() throws Exception {
        mvc.perform(get("/api/v1/core/clients/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE-401-001"));
    }

    @Test
    @DisplayName("Step4: 신뢰하지 않는 issuer 의 Bearer 토큰 → 401 (Keycloak 호출 없음)")
    void untrustedIssuerIs401() throws Exception {
        // header.payload(iss=https://evil.example/realms/x).signature — 서명 검증 전에 issuer 로 거부
        String token = "eyJhbGciOiJSUzI1NiJ9."
                + "eyJpc3MiOiJodHRwczovL2V2aWwuZXhhbXBsZS9yZWFsbXMveCIsInN1YiI6InN5bnRoZXRpYyJ9.c2ln";
        mvc.perform(get("/api/v1/core/clients/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Step4: 개발용 헤더(X-Actor-Id)는 운영 모드에서 무시된다")
    void devHeadersIgnored() throws Exception {
        mvc.perform(as(get("/api/v1/core/clients/" + UUID.randomUUID()), actor(), "SYSADMIN"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Step4: 인증된 AtomPrincipal(JWT 변환 결과와 동일) 은 체인 통과 → 권한 검사·404")
    void authenticatedPrincipalPasses() throws Exception {
        var p = new AtomPrincipal("synthetic-sub", UUID.randomUUID(), null, Set.of(Role.SALES),
                Role.Realm.INTERNAL, "erp-web");
        var auth = new AtomAuthentication(p, null, List.of(new SimpleGrantedAuthority("ROLE_SALES")));
        mvc.perform(get("/api/v1/core/clients/" + UUID.randomUUID()).with(authentication(auth)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CORE-404-001"));
    }
}
