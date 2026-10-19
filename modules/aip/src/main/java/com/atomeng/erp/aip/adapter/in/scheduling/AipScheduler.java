package com.atomeng.erp.aip.adapter.in.scheduling;

import com.atomeng.erp.aip.application.AipProperties;
import com.atomeng.erp.aip.application.AsyncJobProcessor;
import com.atomeng.erp.aip.application.InferenceAuditService;
import com.atomeng.erp.aip.application.JobService;
import com.atomeng.erp.aip.application.SuggestionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * AIP 스케줄러 — 비동기 큐 처리(워커 활성일 때만, 한 번에 최대 4건) · 리퍼(15초) · 감사 파티션 선생성(기동 + 매월 25일) ·
 * 감사 누락 탐지(5분) · 미확인 제안 만료(매일). atom.aip.scheduler.enabled=false 면 끈다(통합 테스트는 직접 호출).
 * 다중 인스턴스는 SKIP LOCKED 라 안전(ShedLock 은 후속).
 */
@Component
@ConditionalOnProperty(prefix = "atom.aip.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
class AipScheduler {

    private static final Logger log = LoggerFactory.getLogger(AipScheduler.class);

    private final AsyncJobProcessor processor;
    private final JobService jobs;
    private final InferenceAuditService audit;
    private final SuggestionService suggestions;
    private final AipProperties props;

    AipScheduler(AsyncJobProcessor processor, JobService jobs, InferenceAuditService audit, SuggestionService suggestions,
                 AipProperties props) {
        this.processor = processor;
        this.jobs = jobs;
        this.audit = audit;
        this.suggestions = suggestions;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onReady() {
        partitions();
    }

    @Scheduled(fixedDelayString = "${atom.aip.scheduler.poll-interval:2s}")
    void work() {
        try {
            for (int i = 0; i < 4 && processor.runNext().isPresent(); i++) {
                // 처리할 Job 이 있는 동안 연속 처리
            }
        } catch (RuntimeException e) {
            log.warn("AIP 큐 처리 오류: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${atom.aip.scheduler.reap-interval:15s}")
    void reap() {
        try {
            JobService.ReapSummary s = jobs.reap();
            if (s.requeued() + s.dead() + s.preempted() + s.deadlineExceeded() > 0) {
                log.info("AIP 리퍼: 재큐 {} · DEAD {} · 선점 복귀 {} · 마감 초과 {}", s.requeued(), s.dead(), s.preempted(),
                        s.deadlineExceeded());
            }
        } catch (RuntimeException e) {
            log.warn("AIP 리퍼 오류: {}", e.toString());
        }
    }

    @Scheduled(cron = "${atom.aip.scheduler.partition-cron:0 0 3 25 * *}", zone = "Asia/Seoul")
    void partitions() {
        try {
            int n = audit.ensurePartitions(3);
            if (n > 0) {
                log.info("AIP 감사 파티션 {}개 생성(현재월 + 3개월)", n);
            }
        } catch (RuntimeException e) {
            log.warn("AIP 감사 파티션 생성 오류: {}", e.toString());
        }
    }

    @Scheduled(fixedDelayString = "${atom.aip.scheduler.audit-gap-interval:5m}")
    void auditGaps() {
        try {
            audit.detectGaps(props.audit().gapGrace());
        } catch (RuntimeException e) {
            log.warn("AIP 감사 누락 탐지 오류: {}", e.toString());
        }
    }

    @Scheduled(cron = "${atom.aip.scheduler.expire-cron:0 30 2 * * *}", zone = "Asia/Seoul")
    void expire() {
        try {
            int n = suggestions.expireStale();
            if (n > 0) {
                log.info("AIP 미확인 제안 만료 {}건", n);
            }
        } catch (RuntimeException e) {
            log.warn("AIP 제안 만료 오류: {}", e.toString());
        }
    }
}
