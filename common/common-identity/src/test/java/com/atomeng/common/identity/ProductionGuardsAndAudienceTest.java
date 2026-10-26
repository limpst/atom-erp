package com.atomeng.common.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jwt.Jwt;

/** 보안 검토 CMN-04(운영 프로필 헤더 인증 금지) · CMN-06(audience 검증) — 합성 토큰만 */
class ProductionGuardsAndAudienceTest {

    private static MockEnvironment env(String... profiles) {
        MockEnvironment e = new MockEnvironment();
        e.setActiveProfiles(profiles);
        return e;
    }

    @Test
    @DisplayName("CMN-04: prod/production 프로필 + atom.security.enabled=false → 기동 거부, 개발 프로필은 허용")
    void refuseHeaderAuthInProd() {
        var off = new AtomSecurityProperties(false, List.of(), List.of());
        assertThatThrownBy(() -> ProductionGuards.check(env("prod"), off)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CMN-04");
        assertThatThrownBy(() -> ProductionGuards.check(env("postgis", "PRODUCTION"), off))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ProductionGuards.check(env("local-noauth"), off)).doesNotThrowAnyException();
        assertThatCode(() -> ProductionGuards.check(env(), off)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CMN-06: OIDC 모드에서 허용 audience 가 비어 있으면 기동 거부")
    void oidcRequiresAudiences() {
        assertThatThrownBy(() -> ProductionGuards.check(env("prod"), new AtomSecurityProperties(true, List.of(), List.of())))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CMN-06");
        assertThatCode(() -> ProductionGuards.check(env("prod"),
                new AtomSecurityProperties(true, List.of(), List.of("atom-erp-api")))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CMN-04: 운영 배포 표식(atom.deploy.environment=prod)인데 prod 프로필이 없으면 기동 거부, 표식 없는 개발 기동은 허용")
    void deployMarkerRequiresProdProfile() {
        MockEnvironment marked = env("local-noauth");
        marked.setProperty("atom.deploy.environment", "prod");
        assertThatThrownBy(() -> ProductionGuards.checkDeployment(marked)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CMN-04");
        MockEnvironment ok = env("prod");
        ok.setProperty("atom.deploy.environment", "prod");
        assertThatCode(() -> ProductionGuards.checkDeployment(ok)).doesNotThrowAnyException();
        assertThatCode(() -> ProductionGuards.checkDeployment(env())).doesNotThrowAnyException();
        assertThatCode(() -> ProductionGuards.checkDeployment(env("local-noauth"))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CMN-13: prod 프로필 + DB 비밀번호 미설정 · 빈 값 · 개발 기본값(atom_app_local) → 기동 거부, 개발 프로필은 기본값 허용")
    void prodRefusesDefaultDbPassword() {
        MockEnvironment missing = env("prod");
        assertThatThrownBy(() -> ProductionGuards.checkDatasource(missing)).hasMessageContaining("CMN-13");
        MockEnvironment blank = env("production");
        blank.setProperty("spring.datasource.password", " ");
        assertThatThrownBy(() -> ProductionGuards.checkDatasource(blank)).hasMessageContaining("CMN-13");
        MockEnvironment dflt = env("prod");
        dflt.setProperty("spring.datasource.password", ProductionGuards.DEV_DB_PASSWORD);
        assertThatThrownBy(() -> ProductionGuards.checkDatasource(dflt)).hasMessageContaining("CMN-13");
        MockEnvironment unresolved = env("prod");
        unresolved.setProperty("spring.datasource.password", "${PG_APP_PASSWORD}");
        assertThatThrownBy(() -> ProductionGuards.checkDatasource(unresolved)).isInstanceOf(IllegalStateException.class);
        MockEnvironment real = env("prod");
        real.setProperty("spring.datasource.password", "synthetic-Strong-pw-0001");
        assertThatCode(() -> ProductionGuards.checkDatasource(real)).doesNotThrowAnyException();
        MockEnvironment dev = env("local-noauth");
        dev.setProperty("spring.datasource.password", ProductionGuards.DEV_DB_PASSWORD);
        assertThatCode(() -> ProductionGuards.checkDatasource(dev)).doesNotThrowAnyException();
    }

    private static Jwt jwt(List<String> aud) {
        var b = Jwt.withTokenValue("synthetic").header("alg", "RS256").subject("s").issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(60));
        if (aud != null) {
            b.audience(aud);
        }
        return b.build();
    }

    @Test
    @DisplayName("CMN-06: aud 에 허용 audience 가 있어야 통과(다른 서비스용 토큰 · aud 없음 거부)")
    void audience() {
        var v = new AudienceValidator(List.of("atom-erp-api"));
        assertThat(v.validate(jwt(List.of("account", "atom-erp-api"))).hasErrors()).isFalse();
        assertThat(v.validate(jwt(List.of("account"))).hasErrors()).isTrue();
        assertThat(v.validate(jwt(null)).hasErrors()).isTrue();
    }
}
