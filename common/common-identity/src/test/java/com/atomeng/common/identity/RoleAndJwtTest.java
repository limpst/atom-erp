package com.atomeng.common.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

class RoleAndJwtTest {

    @Test
    @DisplayName("설계 8.2 v0.3: 역할 12종 — EMPLOYEE·FIN·AUDITOR 신설 포함")
    void roleCatalog() {
        assertThat(Arrays.stream(Role.values()).map(Enum::name)).containsExactlyInAnyOrder(
                "EXEC", "SALES", "PM", "ENG", "QA", "ADMIN", "LABELER", "CUSTOMER", "SYSADMIN",
                "EMPLOYEE", "FIN", "AUDITOR");
        assertThat(Role.CUSTOMER.realm()).isEqualTo(Role.Realm.PORTAL);
        assertThat(Role.AUDITOR.realm()).isEqualTo(Role.Realm.INTERNAL);
    }

    @Test
    @DisplayName("설계 8.1: MFA 필수 대상은 EXEC·ADMIN·SYSADMIN·QA")
    void mfaRoles() {
        assertThat(Arrays.stream(Role.values()).filter(Role::mfaRequired))
                .containsExactlyInAnyOrder(Role.EXEC, Role.ADMIN, Role.SYSADMIN, Role.QA);
    }

    @Test
    @DisplayName("권한 범위: 여러 역할이면 가장 넓은 범위 (ALL > PROJECT > CLIENT > SELF)")
    void widestScope() {
        assertThat(PermissionScope.PROJECT.widest(PermissionScope.ALL)).isEqualTo(PermissionScope.ALL);
        assertThat(PermissionScope.SELF.widest(PermissionScope.PROJECT)).isEqualTo(PermissionScope.PROJECT);
        assertThat(PermissionScope.CLIENT.widest(null)).isEqualTo(PermissionScope.CLIENT);
    }

    @Test
    @DisplayName("Step4: Keycloak JWT(roles·eid·azp) → AtomPrincipal, 알 수 없는 역할은 무시")
    void internalJwt() {
        UUID eid = UUID.fromString("0192f000-0000-7000-8000-000000000001");
        Jwt jwt = jwt("http://localhost:8180/realms/atom-internal")
                .claim("roles", List.of("PM", "FIN", "offline_access", "CUSTOMER"))
                .claim("eid", eid.toString())
                .claim("azp", "field-app")
                .build();
        var auth = new JwtAuthConverter().convert(jwt);
        var p = auth.getPrincipal();
        assertThat(p.roles()).containsExactlyInAnyOrder(Role.PM, Role.FIN); // CUSTOMER 는 포털 역할 → 제외
        assertThat(p.realm()).isEqualTo(Role.Realm.INTERNAL);
        assertThat(p.actorId()).isEqualTo(eid);
        assertThat(p.clientId()).isEqualTo("field-app");
        assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_PM", "ROLE_FIN");
    }

    @Test
    @DisplayName("Step4 DoD: 포털 토큰은 PORTAL Realm, 내부 역할이 섞여 와도 무시")
    void portalJwt() {
        UUID cid = UUID.fromString("0192f000-0000-7000-8000-0000000000c1");
        Jwt jwt = jwt("http://localhost:8180/realms/atom-portal")
                .claim("roles", List.of("CUSTOMER", "EXEC"))
                .claim("cid", cid.toString())
                .build();
        var p = new JwtAuthConverter().convert(jwt).getPrincipal();
        assertThat(p.realm()).isEqualTo(Role.Realm.PORTAL);
        assertThat(p.roles()).containsExactly(Role.CUSTOMER);
        assertThat(p.clientOrgId()).isEqualTo(cid);
        assertThat(p.employeeId()).isNull();
        // 직원 ID 가 없으면 sub 기반 결정적 actorId (멱등 키 네임스페이스 안정)
        assertThat(p.actorId()).isEqualTo(new JwtAuthConverter().convert(jwt).getPrincipal().actorId());
    }

    private static Jwt.Builder jwt(String issuer) {
        return Jwt.withTokenValue("synthetic").header("alg", "none").subject("user-synthetic-1")
                .issuer(issuer).issuedAt(Instant.parse("2026-10-19T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-19T00:10:00Z"));
    }
}
