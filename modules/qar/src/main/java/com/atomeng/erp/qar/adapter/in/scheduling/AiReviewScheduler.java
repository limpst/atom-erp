package com.atomeng.erp.qar.adapter.in.scheduling;

import com.atomeng.erp.qar.application.AiReviewService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AI 검토 워커 · 타임아웃 스캐너 (07-IMPL Step 9). atom.qar.worker.enabled=false 면 끈다(통합 테스트는 직접 호출).
 * 다중 인스턴스는 작업 선점이 SKIP LOCKED 라 안전(ShedLock 은 후속).
 */
@Component
@ConditionalOnProperty(prefix = "atom.qar.worker", name = "enabled", havingValue = "true", matchIfMissing = true)
class AiReviewScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiReviewScheduler.class);

    private final AiReviewService ai;

    AiReviewScheduler(AiReviewService ai) {
        this.ai = ai;
    }

    @Scheduled(fixedDelayString = "${atom.qar.worker.poll-interval:5s}")
    void tick() {
        try {
            int timedOut = ai.scanTimeouts();
            AiReviewService.RunSummary s = ai.runPending(2);   // 동시 2(GPU 보호, B8 Bulkhead)
            if (timedOut + s.processed() > 0) {
                log.info("QAR AI 작업: 처리 {} · 완료 {} · 스킵 {} · 타임아웃 {}", s.processed(), s.done(), s.skipped(), timedOut);
            }
        } catch (RuntimeException e) {
            log.warn("QAR AI 워커 오류: {}", e.toString());
        }
    }
}
