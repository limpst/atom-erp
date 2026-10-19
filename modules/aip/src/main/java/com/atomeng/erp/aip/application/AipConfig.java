package com.atomeng.erp.aip.application;

import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.adapter.out.worker.DisabledAipWorker;
import com.atomeng.erp.aip.adapter.out.worker.HttpAipWorkerClient;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AipProperties.class)
class AipConfig {

    private static final Logger log = LoggerFactory.getLogger(AipConfig.class);

    /** atom.aip.worker.base-url 이 있으면 Python 워커 HTTP, 없으면 비활성(기본 — vLLM 도 호출하지 않음) */
    @Bean
    AipWorkerPort aipWorkerPort(AipProperties p, java.time.Clock clock) {
        if (p.worker().enabled()) {
            log.info("AIP 워커 연결: {} (HMAC 서명)", p.worker().baseUrl());
            String secret = p.worker().sharedSecret();
            return new HttpAipWorkerClient(p.worker().baseUrl(), p.worker().connectTimeout(), AipJdbc.MAPPER,
                    secret == null ? null : secret.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    p.worker().allowedHosts(), clock);
        }
        log.info("atom.aip.worker.base-url 미설정 — AIP 워커 비활성(동기 호출 DISABLED, 비동기 Job 대기)");
        return new DisabledAipWorker();
    }
}
