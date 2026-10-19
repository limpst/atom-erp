package com.atomeng.common.identity;

import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.identity.authz.PermissionRegistry;
import com.atomeng.common.identity.authz.ProjectScopeResolver;
import com.atomeng.common.identity.authz.RequirePermissionAspect;
import com.atomeng.common.web.problem.ProblemWriter;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.authentication.JwtIssuerAuthenticationManagerResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * common-identity 자동 구성 (설계 8장, 00-IMPL Step 4).
 * <ul>
 *   <li>atom.security.enabled=true(기본): Keycloak OIDC 리소스 서버 — 다중 issuer(atom-internal, atom-portal),
 *       issuer 메타데이터는 첫 요청 시 지연 조회(기동 시 Keycloak 불필요)</li>
 *   <li>atom.security.enabled=false: 헤더 기반 개발/테스트 인증 스텁 (테스트 기본값)</li>
 * </ul>
 */
@AutoConfiguration(before = {SecurityAutoConfiguration.class, OAuth2ResourceServerAutoConfiguration.class})
@EnableConfigurationProperties(AtomSecurityProperties.class)
public class IdentityAutoConfiguration {

    @Bean(name = "atomAuditorAware")
    @ConditionalOnMissingBean
    SecurityCurrentActor securityCurrentActor() {
        return new SecurityCurrentActor();
    }

    @Bean
    @ConditionalOnMissingBean
    PermissionRegistry permissionRegistry(JdbcTemplate jdbc, Clock clock) {
        return new PermissionRegistry(jdbc, clock);
    }

    @Bean
    RequirePermissionAspect requirePermissionAspect(PermissionRegistry registry,
            ObjectProvider<ProjectScopeResolver> scopes, org.springframework.beans.factory.BeanFactory beans,
            Clock clock) {
        return new RequirePermissionAspect(registry, scopes, beans, clock);
    }

    /** CMN-04 · CMN-06: 운영 프로필에서 헤더 인증 금지, OIDC 모드는 audience 필수 — 어기면 기동 실패 */
    @Bean
    Object atomProductionSecurityGuard(org.springframework.core.env.Environment env, AtomSecurityProperties props) {
        ProductionGuards.check(env, props);
        return new Object();
    }

    @Bean
    JwtAuthConverter jwtAuthConverter() {
        return new JwtAuthConverter();
    }

    static AuthenticationEntryPoint entryPoint(ProblemWriter writer) {
        return (req, res, ex) -> writer.write(req, res, CoreErrors.UNAUTHENTICATED, "유효한 액세스 토큰이 필요합니다");
    }

    static AccessDeniedHandler deniedHandler(ProblemWriter writer) {
        return (req, res, ex) -> writer.write(req, res, CoreErrors.PERMISSION_DENIED, "접근이 거부되었습니다");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @ConditionalOnProperty(prefix = "atom.security", name = "enabled", havingValue = "true", matchIfMissing = true)
    static class OidcResourceServer {

        @Bean
        SecurityFilterChain atomOidcFilterChain(HttpSecurity http, AtomSecurityProperties props,
                                                JwtAuthConverter converter, ProblemWriter writer) throws Exception {
            Map<String, AuthenticationManager> managers = new ConcurrentHashMap<>();
            AuthenticationManagerResolver<String> byIssuer = issuer -> {
                if (!props.issuers().contains(issuer)) {
                    return null; // 신뢰하지 않는 issuer → 401
                }
                return managers.computeIfAbsent(issuer, i -> {
                    // CMN-06: issuer 기본 검증(서명 · 만료 · iss) + audience
                    var decoder = (org.springframework.security.oauth2.jwt.NimbusJwtDecoder) JwtDecoders.fromIssuerLocation(i);
                    decoder.setJwtValidator(new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                            org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer(i),
                            new AudienceValidator(props.audiences())));
                    var provider = new JwtAuthenticationProvider(decoder);
                    provider.setJwtAuthenticationConverter(converter);
                    return provider::authenticate;
                });
            };
            http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                    .requestMatchers("/actuator/health/**", "/v3/api-docs/**").permitAll()
                    .requestMatchers("/api/v1/ptl/**").hasRole(Role.CUSTOMER.name())
                    .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                    .authenticationManagerResolver(new JwtIssuerAuthenticationManagerResolver(byIssuer))
                    .authenticationEntryPoint(entryPoint(writer))
                    .accessDeniedHandler(deniedHandler(writer)))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint(writer))
                    .accessDeniedHandler(deniedHandler(writer)));
            return http.build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity
    @ConditionalOnProperty(prefix = "atom.security", name = "enabled", havingValue = "false")
    static class DevHeaderSecurity {

        @Bean
        SecurityFilterChain atomDevFilterChain(HttpSecurity http) throws Exception {
            http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .addFilterBefore(new DevHeaderAuthenticationFilter(), AnonymousAuthenticationFilter.class);
            return http.build();
        }
    }
}
