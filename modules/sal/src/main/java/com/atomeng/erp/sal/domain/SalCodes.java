package com.atomeng.erp.sal.domain;

/**
 * SAL 오류 코드 (설계 01 · 01-IMPL) — HTTP 상태는 코드 가운데 숫자. 도메인 · 애플리케이션 공용(도메인은 application 에 의존 금지).
 */
public final class SalCodes {
    private SalCodes() {}

    public static final String NOT_FOUND = "SAL-404-001";
    /** A6.1 기회 단계 전이 불가 */
    public static final String STAGE_TRANSITION = "SAL-409-001";
    /** A6.2 견적 상태 전이 불가 */
    public static final String ESTIMATE_STATE = "SAL-409-002";
    /** BR-SAL-011 / TC-SAL-030 기술자 투입 중복 */
    public static final String ASSIGNMENT_CONFLICT = "SAL-409-003";
    /** BR-SAL-009 품의 승인 전 투찰금액 입력 불가 */
    public static final String BID_NOT_APPROVED = "SAL-409-004";
    /** 계약 상태 전이 불가 */
    public static final String CONTRACT_STATE = "SAL-409-005";
    /** 수집 작업이 이미 실행 중 */
    public static final String COLLECT_RUNNING = "SAL-409-006";
    /** NFR-SAL-05 / TC-SAL-014 발행 견적 불변(DB 트리거) */
    public static final String ESTIMATE_IMMUTABLE = "SAL-409-010";
    public static final String INVALID = "SAL-422-001";
    /** BR-SAL-004 / TC-SAL-011 요율 범위 밖 */
    public static final String RATE_OUT_OF_RANGE = "SAL-422-002";
    /** 기회 단계가 견적 작성 단계가 아님 */
    public static final String STAGE_NOT_ALLOWED = "SAL-422-003";
    /** A8-5 기준일 ACTIVE 규칙 없음 */
    public static final String RULE_NOT_FOUND = "SAL-422-004";
    /** A8-6 / TC-SAL-012 면적 구간 밖 → 수동 산정 필요 */
    public static final String MANUAL_ESTIMATION_REQUIRED = "SAL-422-005";
    /** BR-SAL-006 할인율 범위(0 ≤ r < 0.5) 또는 승인자 한도 초과 */
    public static final String DISCOUNT_RANGE = "SAL-422-006";
    /** FR-SAL-012 / TC-SAL-013 미승인(미발행) 견적 발송 차단 */
    public static final String NOT_ISSUED = "SAL-422-007";
    /** 규칙 본문 구조 · 의미 오류 */
    public static final String RULE_INVALID = "SAL-422-008";
    /** 시설물 프로필(연면적 · 준공연도 등) 누락 */
    public static final String FACILITY_PROFILE = "SAL-422-009";
    /** BR-SAL-010 Lost 사유 코드 필수 */
    public static final String LOST_REASON_REQUIRED = "SAL-422-010";
    /** FR-SAL-020 / TC-SAL-032 수의계약 사유 필수 */
    public static final String NEGOTIATED_REASON_REQUIRED = "SAL-422-011";
    /** A8-8 계약 금액 ≠ 최종 견적(1% 이상) 사유 필수 */
    public static final String AMOUNT_DIFF_REASON_REQUIRED = "SAL-422-012";
    /** 공고 수집 외부 API 장애 · 미설정 */
    public static final String G2B_UNAVAILABLE = "SAL-503-001";
    /** 지원하지 않는 가격점수 유형 */
    public static final String PRICE_FORMULA = "SAL-422-020";
}
