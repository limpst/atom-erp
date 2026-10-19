package com.atomeng.erp.aip.domain;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 추론 Job 상태 (설계 A6.1). 허용 전이만 표로 둔다 — 서비스 · 스케줄러는 {@link #canMove} 로 확인한다.
 * <pre>
 * QUEUED → RUNNING(스케줄러 할당) · CANCELLED(사용자 취소) · DEAD(마감 초과 — 구현 추가)
 * RUNNING → SUCCEEDED · FAILED(오류) · PREEMPTED(학습 창 · OOM) · CANCELLED(협조적 취소 — 구현 추가)
 * FAILED → QUEUED(재시도, 최대 max_attempts · 지수 백오프) · DEAD(재시도 소진)
 * PREEMPTED → QUEUED
 * </pre>
 */
public enum JobStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, DEAD, PREEMPTED, CANCELLED;

    private static final Map<JobStatus, Set<JobStatus>> NEXT = Map.of(
            QUEUED, EnumSet.of(RUNNING, CANCELLED, DEAD),
            RUNNING, EnumSet.of(SUCCEEDED, FAILED, PREEMPTED, CANCELLED),
            FAILED, EnumSet.of(QUEUED, DEAD),
            PREEMPTED, EnumSet.of(QUEUED),
            SUCCEEDED, EnumSet.noneOf(JobStatus.class),
            DEAD, EnumSet.noneOf(JobStatus.class),
            CANCELLED, EnumSet.noneOf(JobStatus.class));

    public boolean canMove(JobStatus to) {
        return NEXT.get(this).contains(to);
    }

    public boolean terminal() {
        return NEXT.get(this).isEmpty();
    }

    /** 재시도 백오프(초) = 2^attempts (리퍼 · 실패 처리 공통, 11-IMPL Step 2) */
    public static long backoffSeconds(int attempts) {
        return 1L << Math.min(Math.max(attempts, 0), 20);
    }

    /** 실패 후 다음 상태: 시도 횟수가 남았으면 QUEUED(재시도), 아니면 DEAD */
    public static JobStatus afterFailure(int attempts, int maxAttempts) {
        return attempts >= maxAttempts ? DEAD : QUEUED;
    }
}
