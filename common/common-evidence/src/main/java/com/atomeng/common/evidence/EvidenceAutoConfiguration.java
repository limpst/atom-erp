package com.atomeng.common.evidence;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * ObjectStorage 선택 — {@code atom.evidence.store}:
 * <ul>
 *   <li>{@code local}(기본 · 개발 · 시험): 로컬 파일시스템(atom.evidence.local-root, WORM 모사)</li>
 *   <li>{@code s3}(운영 prod 프로필 기본): S3 호환 Object Lock(COMPLIANCE) — {@link S3ObjectLockStorage}. 엔드포인트 등은
 *       {@code atom.evidence.s3.*}(환경변수 ATOM_EVIDENCE_S3_ENDPOINT · _REGION 도 됨), <b>자격 증명은 환경변수에서만</b>
 *       (ATOM_EVIDENCE_S3_ACCESS_KEY · ATOM_EVIDENCE_S3_SECRET_KEY — 설정 파일 · 속성으로 주면 기동 거부)</li>
 * </ul>
 * 보안 검토 CMN-07: 운영 프로필은 s3 만 허용. 로컬 FS 는 {@code atom.evidence.allow-local-in-prod=true}(위험 인지 재정의)일 때만,
 * 그 경우에도 tmpdir 은 금지({@link #requireDurableRootInProd}).
 */
@AutoConfiguration
public class EvidenceAutoConfiguration {

    static final String ACCESS_KEY_ENV = "ATOM_EVIDENCE_S3_ACCESS_KEY";
    static final String SECRET_KEY_ENV = "ATOM_EVIDENCE_S3_SECRET_KEY";

    @Bean
    @ConditionalOnMissingBean
    ObjectStorage objectStorage(@Value("${atom.evidence.local-root:${java.io.tmpdir}/atom-evidence}") String root,
                                ObjectProvider<Clock> clock, org.springframework.core.env.Environment env) {
        Clock c = clock.getIfAvailable(Clock::systemUTC);
        String store = env.getProperty("atom.evidence.store", "local").trim().toLowerCase(java.util.Locale.ROOT);
        requireObjectLockStoreInProd(env, store);
        if ("s3".equals(store)) {
            return s3(env, System.getenv(), c);
        }
        if (!"local".equals(store)) {
            throw new IllegalStateException("atom.evidence.store 는 local 또는 s3: " + store);
        }
        requireDurableRootInProd(env, root, System.getProperty("java.io.tmpdir"));
        return new LocalFsObjectStorage(Path.of(root), Buckets.WORM, c);
    }

    /** S3 Object Lock 저장소 — 자격 증명은 OS 환경변수 맵에서만 읽는다(CMN-07) */
    static S3ObjectLockStorage s3(org.springframework.core.env.Environment env, java.util.Map<String, String> osEnv, Clock clock) {
        for (String p : java.util.List.of("atom.evidence.s3.access-key", "atom.evidence.s3.secret-key",
                "atom.evidence.s3.accessKey", "atom.evidence.s3.secretKey")) {
            if (configuredOutsideOsEnv(env, p)) {
                throw new IllegalStateException(p + " 를 설정 파일 · 속성으로 줄 수 없습니다 — 환경변수 "
                        + ACCESS_KEY_ENV + " · " + SECRET_KEY_ENV + " 만 사용(CMN-07)");
            }
        }
        String endpoint = env.getProperty("atom.evidence.s3.endpoint", env.getProperty("ATOM_EVIDENCE_S3_ENDPOINT", ""));
        if (endpoint.isBlank()) {
            throw new IllegalStateException("atom.evidence.store=s3 인데 atom.evidence.s3.endpoint(ATOM_EVIDENCE_S3_ENDPOINT)가 없습니다");
        }
        var cfg = new S3ObjectLockStorage.Config(java.net.URI.create(endpoint.trim()),
                env.getProperty("atom.evidence.s3.region", env.getProperty("ATOM_EVIDENCE_S3_REGION", "us-east-1")),
                env.getProperty("atom.evidence.s3.bucket-prefix", ""),
                env.getProperty("atom.evidence.s3.retention", java.time.Duration.class, java.time.Duration.ofDays(3650)),
                env.getProperty("atom.evidence.s3.timeout", java.time.Duration.class, java.time.Duration.ofSeconds(60)));
        String ak = osEnv.get(ACCESS_KEY_ENV);
        String sk = osEnv.get(SECRET_KEY_ENV);
        if (ak == null || ak.isBlank() || sk == null || sk.isBlank()) {
            throw new IllegalStateException("S3 Object Lock 저장소 자격 증명 환경변수(" + ACCESS_KEY_ENV + " · " + SECRET_KEY_ENV
                    + ")가 없습니다(CMN-07)");
        }
        return new S3ObjectLockStorage(cfg, ak, sk, Buckets.WORM, clock);
    }

    /** OS 환경변수 말고 설정 파일 · 시스템 속성 · 명령줄 등으로 준 속성인지(Spring 의 환경변수 느슨한 매핑은 제외) */
    static boolean configuredOutsideOsEnv(org.springframework.core.env.Environment env, String name) {
        if (env instanceof org.springframework.core.env.ConfigurableEnvironment ce) {
            for (var ps : ce.getPropertySources()) {
                if (ps instanceof org.springframework.core.env.SystemEnvironmentPropertySource
                        || "configurationProperties".equals(ps.getName())) {
                    continue;
                }
                if (ps.containsProperty(name)) {
                    return true;
                }
            }
            return false;
        }
        return env.containsProperty(name);
    }

    /**
     * 보안 검토 CMN-07: 운영 프로필은 S3 Object Lock 저장소만. 로컬 FS 는 {@code atom.evidence.allow-local-in-prod=true}
     * (위험을 인지한 명시 재정의)일 때만 허용.
     */
    static void requireObjectLockStoreInProd(org.springframework.core.env.Environment env, String store) {
        boolean prod = java.util.Arrays.stream(env.getActiveProfiles())
                .map(p -> p.toLowerCase(java.util.Locale.ROOT)).anyMatch(PROD_PROFILES::contains);
        if (prod && !"s3".equals(store)
                && !env.getProperty("atom.evidence.allow-local-in-prod", Boolean.class, false)) {
            throw new IllegalStateException("운영 프로필의 증거 저장소는 S3 Object Lock(atom.evidence.store=s3)이어야 합니다 — "
                    + "로컬 파일시스템은 atom.evidence.allow-local-in-prod=true 로 위험을 명시 인정할 때만(CMN-07): " + store);
        }
    }

    /** 운영으로 보는 Spring 프로필 (common-identity ProductionGuards 와 같은 목록) */
    static final java.util.Set<String> PROD_PROFILES = java.util.Set.of("prod", "production");

    /**
     * 보안 검토 CMN-07: 운영 프로필에서 증거 · 발행본을 OS 임시 폴더에 두면 정리 작업 · 로컬 사용자가 지우거나 바꿀 수 있다 —
     * atom.evidence.local-root 미설정(기본 tmpdir) 또는 tmpdir 아래 경로면 기동 거부.
     */
    static void requireDurableRootInProd(org.springframework.core.env.Environment env, String root, String tmpdir) {
        boolean prod = java.util.Arrays.stream(env.getActiveProfiles())
                .map(p -> p.toLowerCase(java.util.Locale.ROOT)).anyMatch(PROD_PROFILES::contains);
        if (!prod) {
            return;
        }
        boolean configured = env.containsProperty("atom.evidence.local-root")
                && !env.getProperty("atom.evidence.local-root", "").isBlank();
        Path r = Path.of(root).toAbsolutePath().normalize();
        boolean underTmp = tmpdir != null && !tmpdir.isBlank()
                && r.startsWith(Path.of(tmpdir).toAbsolutePath().normalize());
        if (!configured || underTmp) {
            throw new IllegalStateException("운영 프로필에서는 증거 저장소를 임시 폴더에 둘 수 없습니다 — "
                    + "atom.evidence.local-root 를 영구 경로로 설정하거나 Object Lock 저장소 어댑터를 등록하세요(CMN-07): " + r);
        }
    }

    @Bean
    @ConditionalOnMissingBean
    Thumbnailer thumbnailer() {
        return new Thumbnailer();
    }
}
