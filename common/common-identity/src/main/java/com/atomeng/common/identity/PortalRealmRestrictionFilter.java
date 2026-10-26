package com.atomeng.common.identity;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.web.problem.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 보안 검토 PTL-01 — 포털 realm(atom-portal issuer · CUSTOMER 주체)은 고객 포털 API {@code /api/v1/ptl/**} 만 쓸 수 있다.
 * 내부 API(/api/v1/rpt · core · dem · fac · sal …)는 권한 매트릭스와 무관하게 403 {@code CORE-403-010}(problem+json).
 * OIDC 체인(JWT 인증 뒤)과 개발용 헤더 체인(DevHeaderAuthenticationFilter 뒤) 모두에 같은 필터를 건다.
 * 인증 없는 요청 · 내부 realm 주체는 건드리지 않는다(각 체인 규칙 · @RequirePermission 이 판정).
 */
public class PortalRealmRestrictionFilter extends OncePerRequestFilter {

    /** 포털 주체에게 열린 경로 접두(끝 슬래시 포함) */
    public static final String PORTAL_PREFIX = "/api/v1/ptl/";

    private final ProblemWriter writer;

    public PortalRealmRestrictionFilter(ProblemWriter writer) {
        this.writer = writer;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        AtomPrincipal p = AtomAuthentication.currentPrincipal().orElse(null);
        if (p != null && isPortal(p) && !allowed(pathOf(req))) {
            writer.write(req, res, CoreErrors.PORTAL_REALM_DENIED,
                    "고객 포털 계정은 고객 포털 API(/api/v1/ptl)만 사용할 수 있습니다");
            return;
        }
        chain.doFilter(req, res);
    }

    static boolean isPortal(AtomPrincipal p) {
        return p.realm() == Role.Realm.PORTAL || p.has(Role.CUSTOMER);
    }

    static boolean allowed(String path) {
        return path.startsWith(PORTAL_PREFIX) || "/api/v1/ptl".equals(path) || path.startsWith("/actuator/health");
    }

    private static String pathOf(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        return ctx != null && !ctx.isEmpty() && uri.startsWith(ctx) ? uri.substring(ctx.length()) : uri;
    }
}
