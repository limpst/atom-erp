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
 * </ul>
 */
public final class ProductionGuards {

    /** 운영으로 보는 Spring 프로필 */
    public static final Set<String> PROD_PROFILES = Set.of("prod", "production");

    private ProductionGuards() {}

    public static boolean isProd(Environment env) {
        return Arrays.stream(env.getActiveProfiles()).map(p -> p.toLowerCase(Locale.ROOT)).anyMatch(PROD_PROFILES::contains);
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
