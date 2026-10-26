package com.atomeng.erp.fcd.application;

/** FCD 오류 코드 (설계 12 · 12-IMPL) — HTTP 상태는 코드 가운데 숫자 */
public final class FcdErrors {
    private FcdErrors() {}

    public static final String NOT_FOUND = "FCD-404-001";
    public static final String APPEND_ONLY = "FCD-409-001";
    /** BR-FCD-004 동일 파일(SHA-256) 재업로드 차단 (TC-FCD-013) */
    public static final String DUPLICATE_FILE = "FCD-409-002";
    public static final String STATE = "FCD-409-003";
    public static final String ASSIGNMENT_OVERLAP = "FCD-409-004";
    /** BR-FCD-011 미소명 · 기한초과 케이스가 있으면 정산 상신 불가 (TC-FCD-026) */
    public static final String EXPLANATION_PENDING = "FCD-409-005";
    /** BR-FCD-016 미판정 케이스 거래는 전표 생성 불가 */
    public static final String CASE_UNRESOLVED = "FCD-409-006";
    /** BR-FCD-018 이해충돌 (TC-FCD-025) */
    public static final String CONFLICT_OF_INTEREST = "FCD-403-001";
    /** BR-FCD-009 4-eyes (TC-FCD-024) */
    public static final String FOUR_EYES = "FCD-403-002";
    public static final String NOT_OWNER = "FCD-403-003";
    public static final String INVALID = "FCD-422-001";
    public static final String INVALID_PAN = "FCD-422-002";
    public static final String NEEDS_MAPPING = "FCD-422-003";
    public static final String RULESET_INVALID = "FCD-422-004";
    public static final String FILE_TOO_LARGE = "FCD-413-001";

    // ---- 보안 검토 2026-10-19 §2 정책 결정(권장안)
    /** FCD-11 중복 확인 큐: 결정자 = 두 거래 중 하나의 카드 소지자 · 사용자(직무 분리) */
    public static final String DEDUP_SELF = "FCD-403-004";
    /** FCD-10 정산 라인 프로젝트에 (거래일 기준) 투입되지 않은 사람 */
    public static final String PROJECT_NOT_ASSIGNED = "FCD-403-005";
    /** FCD-13 검토 실행 파라미터 재정의는 AUDITOR 만(사유 필수) */
    public static final String REVIEW_OVERRIDE_FORBIDDEN = "FCD-403-006";
    /** FCD-17 그 정산서 거래의 케이스를 판정한 사람은 정산서를 결재할 수 없다 */
    public static final String ADJUDICATOR_APPROVAL = "FCD-403-007";
    /** FCD-11 미결 케이스 · 정산 라인이 있는 거래는 병합 불가 */
    public static final String MERGE_BLOCKED = "FCD-409-007";
    /** FCD-12 롤백 요청 상태 오류(대기 요청 없음 · 이미 대기 중) */
    public static final String ROLLBACK_REQUEST_STATE = "FCD-409-008";
    /** FCD-14 ACTIVE 가 아닌 카드는 대여 불가 */
    public static final String CARD_NOT_ACTIVE = "FCD-409-009";
    /** FCD-10 WBS 는 프로젝트와 함께만 지정 */
    public static final String WBS_WITHOUT_PROJECT = "FCD-422-005";
    /** FCD-13 검토 실행 파라미터 범위 · policy enum 위반 */
    public static final String REVIEW_PARAM_RANGE = "FCD-422-006";
    /** FCD-14 SELF 공용카드 대여는 오늘 이후 시작만 — 소급은 FIN 등록(승인) */
    public static final String BACKDATED_ASSIGNMENT = "FCD-422-007";
}
