package com.atomeng.common.evidence;

import java.nio.file.Path;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * 기본 ObjectStorage = 로컬 파일시스템(atom.evidence.local-root). 운영에서는 MinIO 어댑터 빈을 등록하면 대체된다.
 */
@AutoConfiguration
public class EvidenceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    ObjectStorage objectStorage(@Value("${atom.evidence.local-root:${java.io.tmpdir}/atom-evidence}") String root,
                                ObjectProvider<Clock> clock, org.springframework.core.env.Environment env) {
        requireDurableRootInProd(env, root, System.getProperty("java.io.tmpdir"));
        return new LocalFsObjectStorage(Path.of(root), Buckets.WORM, clock.getIfAvailable(Clock::systemUTC));
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
