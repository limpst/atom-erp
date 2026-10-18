package com.atomeng.common.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * atom.security.enabled=false 일 때만 등록되는 개발/테스트용 인증 스텁.
 * Keycloak 없이도 RBAC·행 수준 권한·멱등 키(actor 네임스페이스)를 검증할 수 있게 한다.
 * 헤더: X-Actor-Id(UUID, 필수) · X-Roles(쉼표) · X-Client-Id · X-Client-Org-Id.
 */
public class DevHeaderAuthenticationFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String actor = req.getHeader("X-Actor-Id");
        if (actor != null && !actor.isBlank()) {
            Set<Role> roles = EnumSet.noneOf(Role.class);
            String rolesHeader = req.getHeader("X-Roles");
            if (rolesHeader != null) {
                Arrays.stream(rolesHeader.split(",")).forEach(r -> Role.parse(r).ifPresent(roles::add));
            }
            String org = req.getHeader("X-Client-Org-Id");
            boolean portal = roles.contains(Role.CUSTOMER);
            var principal = new AtomPrincipal("dev:" + actor,
                    portal ? null : UUID.fromString(actor.trim()),
                    org == null || org.isBlank() ? null : UUID.fromString(org.trim()),
                    roles, portal ? Role.Realm.PORTAL : Role.Realm.INTERNAL,
                    req.getHeader("X-Client-Id"));
            var auth = new AtomAuthentication(principal, null,
                    roles.stream().map(r -> new SimpleGrantedAuthority(r.authority())).toList());
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(req, res);
    }
}
