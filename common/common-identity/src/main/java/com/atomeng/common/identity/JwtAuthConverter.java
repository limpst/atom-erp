package com.atomeng.common.identity;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Keycloak JWT → AtomAuthentication (00-IMPL Step 4). 클레임 규약:
 * roles(realm 역할 목록), eid(직원 ID), cid(고객사 ID, 포털), azp(클라이언트), iss(…/atom-portal 이면 PORTAL).
 */
public class JwtAuthConverter implements Converter<Jwt, AtomAuthentication> {

    @Override
    public AtomAuthentication convert(Jwt jwt) {
        List<String> rawRoles = Optional.ofNullable(jwt.getClaimAsStringList("roles")).orElse(List.of());
        Set<Role> roles = EnumSet.noneOf(Role.class);
        rawRoles.forEach(r -> Role.parse(r).ifPresent(roles::add));
        String issuer = jwt.getIssuer() == null ? "" : jwt.getIssuer().toString();
        Role.Realm realm = issuer.endsWith("/atom-portal") ? Role.Realm.PORTAL : Role.Realm.INTERNAL;
        // 포털 토큰에 내부 역할이 섞여 와도 무시한다(포털 토큰으로 내부 API 호출 시 403 — 00-IMPL Step 4 DoD)
        roles.removeIf(r -> r.realm() != realm);
        var principal = new AtomPrincipal(
                jwt.getSubject(),
                uuidClaim(jwt, "eid"),
                uuidClaim(jwt, "cid"),
                roles,
                realm,
                jwt.getClaimAsString("azp"));
        var authorities = roles.stream().map(r -> new SimpleGrantedAuthority(r.authority())).toList();
        return new AtomAuthentication(principal, jwt, authorities);
    }

    private static UUID uuidClaim(Jwt jwt, String name) {
        String v = jwt.getClaimAsString(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(v);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
