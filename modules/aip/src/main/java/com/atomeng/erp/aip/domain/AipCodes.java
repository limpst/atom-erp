package com.atomeng.erp.aip.domain;

/** AIP 오류 코드 (설계 11 B4 · 11-IMPL) — HTTP 상태는 코드 가운데 숫자 */
public final class AipCodes {
    private AipCodes() {}

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
