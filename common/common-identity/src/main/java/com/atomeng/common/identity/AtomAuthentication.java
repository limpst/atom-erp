package com.atomeng.common.identity;

import java.util.Collection;
import java.util.Optional;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** AtomPrincipal 을 담는 인증 토큰. credentials 는 JWT(운영) 또는 null(개발용 헤더 인증). */
public class AtomAuthentication extends AbstractAuthenticationToken {

    private static final long serialVersionUID = 1L;

    private final transient AtomPrincipal principal;
    private final transient Object credentials;

    public AtomAuthentication(AtomPrincipal principal, Object credentials,
                              Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.principal = principal;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    @Override
    public AtomPrincipal getPrincipal() {
        return principal;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public String getName() {
        return principal.subject();
    }

    public static Optional<AtomPrincipal> currentPrincipal() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof AtomPrincipal p) {
            return Optional.of(p);
        }
        return Optional.empty();
    }
}
