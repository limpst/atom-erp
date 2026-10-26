package com.atomeng.erp.aip.domain;

/** AIP 오류 코드 (설계 11 B4 · 11-IMPL) — HTTP 상태는 코드 가운데 숫자 */
public final class AipCodes {
    private AipCodes() {}

    /** 보안 검토 AIP-02: canary 트래픽 25% 초과(또는 전체 트래픽)는 EXEC 최종 승인이 먼저 기록되어야 함 */
    public static final String CANARY_TRAFFIC_EXEC_REQUIRED = "AIP-403-001";
    /** 보안 검토 AIP-01b · AIP-08: 직무 분리 — 같은 사람이 QA · EXEC 승인을 모두 하거나, 라벨러가 자기 라벨을 판정 */
    public static final String SEGREGATION_OF_DUTIES = "AIP-403-002";
    public static final String NOT_FOUND = "AIP-404-001";
    /** 상태 전이 표에 없는 요청(예: 끝난 Job 취소, 이미 결정한 제안) */
    public static final String INVALID_TRANSITION = "AIP-409-001";
    /** 모델 승격 단계 · 승인 요건 불충족 */
    public static final String PROMOTION_DENIED = "AIP-409-002";
    /** 같은 Idempotency-Key 로 다른 입력 */
    public static final String IDEMPOTENCY_MISMATCH = "AIP-409-003";
    public static final String INVALID = "AIP-422-001";
    /** 이번 단계 미지원 서비스(STT · 드론 · 위험요소) */
    public static final String UNSUPPORTED_SERVICE = "AIP-422-002";
    /** 워커 미설정 · 장애 */
    public static final String WORKER_UNAVAILABLE = "AIP-503-001";
    public static final String WORKER_TIMEOUT = "AIP-504-001";
}
