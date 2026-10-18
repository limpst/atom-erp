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
                                ObjectProvider<Clock> clock) {
        return new LocalFsObjectStorage(Path.of(root), Buckets.WORM, clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    @ConditionalOnMissingBean
    Thumbnailer thumbnailer() {
        return new Thumbnailer();
    }
}
