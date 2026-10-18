package com.atomeng.erp.qar.application;

import com.atomeng.erp.qar.adapter.out.ai.DisabledAtomAiCore;
import com.atomeng.erp.qar.adapter.out.ai.VllmAtomAiCoreClient;
import com.atomeng.erp.qar.application.port.AtomAiCorePort;
import com.atomeng.erp.qar.domain.autocheck.AutoCheckEngine;
import com.atomeng.erp.qar.domain.autocheck.rules.AutoCheckRules;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QarProperties.class)
class QarConfig {

    private static final Logger log = LoggerFactory.getLogger(QarConfig.class);

    /** 규칙 15종 · 규칙별 타임아웃 10초(07-IMPL Step 4) */
    @Bean
    AutoCheckEngine qarAutoCheckEngine() {
        return new AutoCheckEngine(AutoCheckRules.all(), Duration.ofSeconds(10));
    }

    /** atom.qar.ai.base-url 이 있으면 로컬 vLLM(Atom AI Core), 없으면 비활성(AI 단계 스킵) */
    @Bean
    AtomAiCorePort qarAtomAiCore(QarProperties p) {
        if (p.ai().enabled()) {
            return new VllmAtomAiCoreClient(p.ai().baseUrl(), p.ai().model(), p.ai().httpTimeout());
        }
        log.info("atom.qar.ai.base-url 미설정 — AI 검토 비활성(제출 후 AI 단계 스킵)");
        return new DisabledAtomAiCore();
    }
}
