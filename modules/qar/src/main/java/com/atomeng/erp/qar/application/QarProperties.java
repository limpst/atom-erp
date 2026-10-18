package com.atomeng.erp.qar.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * atom.qar.* —
 * ai.baseUrl 이 비어 있으면 AI 검토 비활성(제출 후 AI 단계 즉시 스킵, "AI 미수행" 배지),
 * ai.deadline = 작업 마감(요청 + 10분, NFR-QAR-02), ai.httpTimeout = vLLM 호출 읽기 타임아웃,
 * ai.autoStart = 제출 직후 AI_START(AUTO) 자동 실행, worker.enabled = 작업 · 타임아웃 스케줄러.
 */
@ConfigurationProperties(prefix = "atom.qar")
public record QarProperties(Ai ai, Worker worker) {

    public QarProperties {
        ai = ai == null ? new Ai(null, null, null, null, null) : ai;
        worker = worker == null ? new Worker(null, null) : worker;
    }

    public record Ai(String baseUrl, String model, Duration deadline, Duration httpTimeout, Boolean autoStart) {
        public Ai {
            model = model == null || model.isBlank() ? "atom-qar-review" : model;
            deadline = deadline == null ? Duration.ofMinutes(10) : deadline;
            httpTimeout = httpTimeout == null ? Duration.ofMinutes(9) : httpTimeout;
            autoStart = autoStart == null || autoStart;
        }

        public boolean enabled() {
            return baseUrl != null && !baseUrl.isBlank();
        }
    }

    public record Worker(Boolean enabled, Duration pollInterval) {
        public Worker {
            enabled = enabled == null || enabled;
            pollInterval = pollInterval == null ? Duration.ofSeconds(5) : pollInterval;
        }
    }
}
