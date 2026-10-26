package com.atomeng.erp.eqp.application;

import java.util.Map;

/** EQP 오류 코드 (설계 04 · 04-IMPL) — HTTP 상태는 코드 가운데 숫자. 설계의 문자열 사유 코드(EQP_CAL_EXPIRED 등)는 problem 의 reason 속성 */
public final class EqpErrors {
    private EqpErrors() {}

    public static final String QR_FORMAT = "EQP-400-001";
    /** TC-EQP-008 Override 유효기간 7일 초과 (설계 EQP_OVERRIDE_TOO_LONG) */
    public static final String OVERRIDE_TOO_LONG = "EQP-400-002";
    /** 위조 · 회전된 옛 라벨 (EQP_QR_SIGNATURE) */
    public static final String QR_SIGNATURE = "EQP-403-001";
    /** EQP-01 예약 취소는 신청자 본인 또는 그 프로젝트 승인 권한자만 */
    public static final String RESERVATION_CANCEL_DENIED = "EQP-403-002";
    /** EQP-01 본인이 신청한 예약은 본인이 승인할 수 없음 */
    public static final String SELF_APPROVAL = "EQP-403-003";
    /** EQP-03 비행기록 조종자 = 요청자(ADMIN 대리 등록만 예외) */
    public static final String PILOT_NOT_REQUESTER = "EQP-403-004";
    /** EQP-04 검교정 예외 승인자 = 요청자(4-eyes 위반) */
    public static final String OVERRIDE_SELF_APPROVAL = "EQP-403-005";
    public static final String NOT_FOUND = "EQP-404-001";
    /** FR-EQP-005 / TC-EQP-001 검교정 만료 장비 배정 차단 (EQP_CAL_EXPIRED) */
    public static final String CAL_EXPIRED = "EQP-409-001";
    /** TC-EQP-002 같은 장비 기간 중복 예약 (EQP_DOUBLE_BOOKING) */
    public static final String DOUBLE_BOOKING = "EQP-409-002";
    /** 폐기 · 분실 · 고장 · 수리 중 (EQP_NOT_USABLE) */
    public static final String NOT_USABLE = "EQP-409-003";
    /** TC-EQP-006 키트 자식 무효 (EQP_KIT_CHILD_INVALID) */
    public static final String KIT_CHILD_INVALID = "EQP-409-004";
    /** 유효 교정 이후 FAIL 이력 (EQP_CAL_FAILED_AFTER) */
    public static final String CAL_FAILED_AFTER = "EQP-409-005";
    public static final String STATE = "EQP-409-006";
    /** FR-EQP-001 같은 유형 시리얼 중복 (EQP_SERIAL_DUPLICATE) */
    public static final String SERIAL_DUPLICATE = "EQP-409-007";
    public static final String ALREADY_RETURNED = "EQP-409-008";
    /** BR-EQP-002 예약 기간 중 검교정 만료 (EQP_CAL_EXPIRES_IN_PERIOD) */
    public static final String CAL_EXPIRES_IN_PERIOD = "EQP-409-009";
    public static final String APPEND_ONLY = "EQP-409-010";
    /** 다른 프로젝트 예약 기간에 예약 없이 반출 */
    public static final String RESERVED_BY_OTHER = "EQP-409-011";
    /** EQP-04 같은 장비 · 프로젝트에 아직 끝나지 않은 예외 승인이 있음(연쇄 금지) */
    public static final String OVERRIDE_ACTIVE_EXISTS = "EQP-409-012";
    public static final String INVALID = "EQP-422-001";
    /** BR-EQP-006 / TC-EQP-007 앤빌값 기준 이탈 (EQP_ANVIL_OUT_OF_RANGE) */
    public static final String ANVIL_OUT_OF_RANGE = "EQP-422-002";
    /** FR-EQP-016 보험 만료 기체 비행 등록 차단 */
    public static final String DRONE_INSURANCE_EXPIRED = "EQP-422-003";
    /** BR-EQP-007 / TC-EQP-011 조종자 자격 무효 (설계 HRQ_LICENSE_INVALID) */
    public static final String PILOT_LICENSE_INVALID = "EQP-422-004";
    /** HRQ 조회 장애 → Fail-closed (설계 HRQ_UNAVAILABLE_FAIL_CLOSED) */
    public static final String PILOT_CHECK_UNAVAILABLE = "EQP-422-005";
    /** EQP-02 반출 · 반납 시각(오프라인 기록)이 미래이거나 72시간보다 과거 */
    public static final String CLIENT_TIME_OUT_OF_RANGE = "EQP-422-006";
    /** EQP-04 검교정 예외는 오늘 이후 시작만(소급 금지) */
    public static final String OVERRIDE_RETROACTIVE = "EQP-422-007";

    /** 유효성 판정 사유(설계 문자열) → 오류 코드 */
    public static final Map<String, String> BY_REASON = Map.of(
            "EQP_CAL_EXPIRED", CAL_EXPIRED,
            "EQP_NOT_USABLE", NOT_USABLE,
            "EQP_KIT_CHILD_INVALID", KIT_CHILD_INVALID,
            "EQP_CAL_FAILED_AFTER", CAL_FAILED_AFTER,
            "EQP_KIT_CYCLE", KIT_CHILD_INVALID);
}
