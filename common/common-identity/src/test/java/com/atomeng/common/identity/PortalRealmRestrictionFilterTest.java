package com.atomeng.common.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.web.problem.ProblemFactory;
import com.atomeng.common.web.problem.ProblemWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** PTL-01 — 포털 realm(OIDC atom-portal 토큰 · 헤더 모드 CUSTOMER) 주체는 /api/v1/ptl/** 만 */
class PortalRealmRestrictionFilterTest {

    private final ObjectMapper om = new ObjectMapper().addMixIn(org.springframework.http.ProblemDetail.class,
            org.springframework.http.converter.json.ProblemDetailJacksonMixin.class);
    private final PortalRealmRestrictionFilter filter =
            new PortalRealmRestrictionFilter(new ProblemWriter(new ProblemFactory(new StaticMessageSource()), om));

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static Jwt.Builder jwt(String issuer) {
        return Jwt.withTokenValue("synthetic").header("alg", "none").subject("user-synthetic-ptl")
                .issuer(issuer).issuedAt(Instant.parse("2026-10-26T00:00:00Z"))
                .expiresAt(Instant.parse("2026-10-26T00:10:00Z"));
    }

    private MockHttpServletResponse run(String uri, AtomicBoolean passed) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (rq, rs) -> passed.set(true));
        return res;
    }

    @Test
    @DisplayName("PTL-01: 포털(atom-portal) JWT 주체 — /api/v1/rpt · core · actuator 는 403 CORE-403-010, /api/v1/ptl/** 는 통과")
    void portalJwtLimitedToPtl() throws Exception {
        Jwt token = jwt("http://localhost:8180/realms/atom-portal").claim("roles", List.of("CUSTOMER"))
                .claim("cid", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthConverter().convert(token));
        for (String uri : List.of("/api/v1/rpt/revisions/" + UUID.randomUUID() + "/files/report.pdf",
                "/api/v1/core/projects", "/api/v1/ptlx/anything", "/actuator/metrics")) {
            AtomicBoolean passed = new AtomicBoolean();
            MockHttpServletResponse res = run(uri, passed);
            assertThat(passed).as(uri).isFalse();
            assertThat(res.getStatus()).as(uri).isEqualTo(403);
            assertThat(res.getContentType()).startsWith("application/problem+json");
            assertThat(om.readTree(res.getContentAsByteArray()).get("code").asText()).isEqualTo("CORE-403-010");
        }
        for (String uri : List.of("/api/v1/ptl/me", "/api/v1/ptl/reports/x:download", "/actuator/health")) {
            AtomicBoolean passed = new AtomicBoolean();
            run(uri, passed);
            assertThat(passed).as(uri).isTrue();
        }
    }

    @Test
    @DisplayName("PTL-01: 내부 realm JWT · 인증 없음은 필터가 건드리지 않는다")
    void internalAndAnonymousUntouched() throws Exception {
        AtomicBoolean anon = new AtomicBoolean();
        run("/api/v1/rpt/reports", anon);
        assertThat(anon).isTrue();
        Jwt token = jwt("http://localhost:8180/realms/atom-internal").claim("roles", List.of("PM"))
                .claim("eid", UUID.randomUUID().toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthConverter().convert(token));
        AtomicBoolean internal = new AtomicBoolean();
        run("/api/v1/rpt/reports", internal);
        assertThat(internal).isTrue();
    }
}
