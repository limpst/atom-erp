package com.atomeng.erp.qar.application;

import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.qar.adapter.out.ai.AipAtomAiCoreAdapter;
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

    /**
     * AI 검토 경로: atom.qar.ai.base-url 이 있으면 기존 직접 vLLM 어댑터(호환), 없으면 AIP 모듈 동기 Job(aip::api, 설계 11 v0.3 B6).
     * AIP 워커가 미설정(기본)이면 AIP 가 DISABLED 를 돌려 AI 단계를 스킵한다 — 기본값으로는 vLLM 을 호출하지 않는다.
     */
    @Bean
    AtomAiCorePort qarAtomAiCore(QarProperties p, AipJobApi aip) {
        if (p.ai().enabled()) {
            return new VllmAtomAiCoreClient(p.ai().baseUrl(), p.ai().model(), p.ai().httpTimeout());
        }
        log.info("QAR AI 검토 → AIP 동기 Job(REPORT_REVIEW){}", aip.enabled() ? "" : " — AIP 워커 비활성(제출 후 AI 단계 스킵)");
        return new AipAtomAiCoreAdapter(aip, p.ai().httpTimeout());
    }
}
