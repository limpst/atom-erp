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
}
