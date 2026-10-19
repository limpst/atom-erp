package com.atomeng.erp.aip.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * atom.aip.* —
 * worker.baseUrl 이 비어 있으면 워커 비활성(기본): 동기 호출은 DISABLED, 비동기 Job 은 QUEUED 로 대기(NFR-AIP-03 — ERP 기능 영향 없음).
 * 워커는 사내 로컬 주소만(예 http://127.0.0.1:8090) — 외부 송신 금지(BR-AIP-011).
 * scheduler.enabled = 큐 처리 · 리퍼 · 감사 파티션 · 누락 탐지 스케줄러, lease = 워커 임대 시간(B7.4 120초),
 * suggestion.acceptSampleRate = 수락 약한 양성 표본 비율(BR-AIP-012 ①), expireDays = 미확인 제안 만료(A6.2 30일),
 * audit.gapGrace = 성공 후 감사 행이 이 시간 안에 없으면 누락으로 본다(TC-AIP-017).
 */
@ConfigurationProperties(prefix = "atom.aip")
public record AipProperties(Worker worker, Scheduler scheduler, Suggestion suggestion, Audit audit) {

    public AipProperties {
        worker = worker == null ? new Worker(null, null, null) : worker;
        scheduler = scheduler == null ? new Scheduler(null, null, null) : scheduler;
        suggestion = suggestion == null ? new Suggestion(null, null, null) : suggestion;
        audit = audit == null ? new Audit(null) : audit;
    }

    public record Worker(String baseUrl, Duration connectTimeout, Duration defaultTimeout) {
        public Worker {
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
            defaultTimeout = defaultTimeout == null ? Duration.ofMinutes(2) : defaultTimeout;
        }

        public boolean enabled() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record Scheduler(Boolean enabled, Duration pollInterval, Duration lease) {
        public Scheduler {
            enabled = enabled == null || enabled;
            pollInterval = pollInterval == null ? Duration.ofSeconds(2) : pollInterval;
            lease = lease == null ? Duration.ofSeconds(120) : lease;
        }
    }

    public record Suggestion(Double acceptSampleRate, Integer expireDays, Double minConfidence) {
        public Suggestion {
            acceptSampleRate = acceptSampleRate == null ? 0.2 : acceptSampleRate;
            expireDays = expireDays == null ? 30 : expireDays;
            minConfidence = minConfidence == null ? 0.35 : minConfidence;
        }
    }

    public record Audit(Duration gapGrace) {
        public Audit {
            gapGrace = gapGrace == null ? Duration.ofMinutes(10) : gapGrace;
        }
    }
}
