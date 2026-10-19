package com.atomeng.devserver;

import com.atomeng.erp.aip.api.AipJobApi.AipDisabledException;
import com.atomeng.erp.aip.application.port.AipWorkerPort;
import com.atomeng.erp.support.AipSamples;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * dev 서버 전용 AIP 워커 대역(운영 금지 · 테스트 소스셋). <b>평소에는 꺼져 있다(DISABLED)</b> — 기본 dev 서버와 같다.
 * dev 시드(DevSeedRound3)가 AI Job 화면용 합성 데이터를 API 로 만드는 동안에만 {@link #online} 을 켜서
 * CRACK_DETECT 체인(균열 점수 · 소견 · 측정)에 합성 응답(AipSamples — 워커 계약 예시)을 돌려준다.
 * 실제 vLLM · Python 워커 호출 없음. 그 밖의 경로(보고서 초안 · QAR 검토 등)는 언제나 DISABLED.
 */
public final class DevAipWorker implements AipWorkerPort {

    static volatile boolean online = false;

    private static final Set<String> SERVED = Set.of(CRACK_SCORE, FINDINGS, CRACK_MEASURE);

    @Override
    public boolean enabled() {
        return online;
    }

    @Override
    public Response post(String path, JsonNode body, Duration timeout) {
        if (!online || !SERVED.contains(path)) {
            throw new AipDisabledException("DISABLED: dev 워커 대역 — " + path);
        }
        return new Response(200, AipSamples.reply(path, body), 7);
    }

    /** ErpServerApplication 에 함께 등록하는 설정 — 기본 워커 bean 보다 우선(@Primary) */
    @Configuration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        AipWorkerPort devAipWorker() {
            return new DevAipWorker();
        }
    }
}
