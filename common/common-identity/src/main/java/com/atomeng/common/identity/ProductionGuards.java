package com.atomeng.common.identity;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import org.springframework.core.env.Environment;

/**
 * 운영 프로필 안전장치 (보안 검토 CMN-04 · CMN-06).
 * <ul>
 *   <li>운영 프로필(prod · production)에서 {@code atom.security.enabled=false}(헤더 위조 인증)면 기동 거부</li>
 *   <li>OIDC 모드에서 허용 audience({@code atom.security.audiences})가 비어 있으면 기동 거부</li>
 *   <li>CMN-04(배포): 운영 배포 표식 {@code atom.deploy.environment=prod}(deploy/prod/run-erp.* 가 설정)인데 prod 프로필이
 *       꺼져 있으면 기동 거부 — 배포 환경에서 SPRING_PROFILES_ACTIVE 가 다른 값으로 덮여 운영 가드가 모두 꺼지는 것을 막는다.
 *       표식이 없는 개발 · 시험 기동에는 영향 없음</li>
 *   <li>CMN-13: 운영 프로필에서 DB 비밀번호가 비었거나 개발 기본값({@value #DEV_DB_PASSWORD})이면 기동 거부</li>
 * </ul>
 */
public final class ProductionGuards {

    /** 운영으로 보는 Spring 프로필 */
    public static final Set<String> PROD_PROFILES = Set.of("prod", "production");

    private ProductionGuards() {}

    public static boolean isProd(Environment env) {
        return Arrays.stream(env.getActiveProfiles()).map(p -> p.toLowerCase(Locale.ROOT)).anyMatch(PROD_PROFILES::contains);
    }

    /** application.yml 의 로컬 개발용 DB 비밀번호 기본값 — 운영에서 금지(CMN-13) */
    public static final String DEV_DB_PASSWORD = "atom_app_local";

    /** 배포 기동기(deploy/prod/run-erp.sh · .ps1)가 설정하는 운영 배포 표식(환경변수 ATOM_DEPLOY_ENVIRONMENT) */
    public static final String DEPLOY_ENVIRONMENT_PROPERTY = "atom.deploy.environment";

    /** CMN-04(배포 절차): 운영 배포 표식이 있으면 prod 프로필이 켜져 있어야 한다 */
    public static void checkDeployment(Environment env) {
        String marker = env.getProperty(DEPLOY_ENVIRONMENT_PROPERTY, "").trim().toLowerCase(Locale.ROOT);
        if (PROD_PROFILES.contains(marker) && !isProd(env)) {
            throw new IllegalStateException("운영 배포(" + DEPLOY_ENVIRONMENT_PROPERTY + "=" + marker
                    + ")인데 prod 프로필이 꺼져 있습니다 — SPRING_PROFILES_ACTIVE=prod 로 기동하세요(deploy/prod/run-erp.*, CMN-04). 현재: "
                    + String.join(",", env.getActiveProfiles()));
        }
    }

    /** CMN-13: 운영 프로필 + DB 비밀번호 미설정 · 개발 기본값이면 기동 거부 */
    public static void checkDatasource(Environment env) {
        if (!isProd(env)) {
            return;
        }
        String pw;
        try {
            pw = env.getProperty("spring.datasource.password");
        } catch (IllegalArgumentException unresolved) {   // ${PG_APP_PASSWORD} 자리표시자 미해결
            pw = null;
        }
        if (pw == null || pw.isBlank() || DEV_DB_PASSWORD.equals(pw.trim())) {
            throw new IllegalStateException("운영 프로필에서는 DB 비밀번호(PG_APP_PASSWORD)를 반드시 설정해야 하고 개발 기본값은 쓸 수 없습니다(CMN-13)");
        }
    }

    public static void check(Environment env, AtomSecurityProperties props) {
        if (isProd(env) && !props.enabled()) {
            throw new IllegalStateException("운영 프로필(" + String.join(",", env.getActiveProfiles())
                    + ")에서 atom.security.enabled=false(헤더 인증 스텁)는 허용되지 않습니다(CMN-04)");
        }
        if (props.enabled() && props.audiences().isEmpty()) {
            throw new IllegalStateException("atom.security.audiences(허용 토큰 audience)가 비어 있습니다(CMN-06)");
        }
    }
}
