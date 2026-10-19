package com.atomeng.erp.dem.application;

/**
 * DEM 오류 코드 (설계 10 B4 · 10-IMPL) — HTTP 상태는 코드 가운데 숫자. 설계 문자열 코드(DEM_IMMUTABLE 등)는 problem 의
 * {@code reason} 속성으로 함께 내보낸다.
 */
public final class DemErrors {
    private DemErrors() {}

    /** 지정 감리자가 아닌 사용자의 일지 작성 (DEM_NOT_ASSIGNED_SUPERVISOR) */
    public static final String NOT_ASSIGNED_SUPERVISOR = "DEM-403-001";
    public static final String NOT_FOUND = "DEM-404-001";
    /** BR-DEM-008 · BR-DEM-006 잠금 · 발행 객체 수정 · 삭제 (DEM_IMMUTABLE) */
    public static final String IMMUTABLE = "DEM-409-001";
    /** BR-DEM-009 / TC-DEM-007 미결 검토의견 존재 (DEM_OPEN_COMMENTS_EXIST) */
    public static final String OPEN_COMMENTS_EXIST = "DEM-409-002";
    /** TC-DEM-003 책임감리자 기간 중복 (DEM_CHIEF_PERIOD_OVERLAP) */
    public static final String CHIEF_PERIOD_OVERLAP = "DEM-409-003";
    /** A6.1 · A6.2 · A6.3 상태 전이 표에 없는 이동 (DEM_INVALID_TRANSITION) */
    public static final String INVALID_TRANSITION = "DEM-409-004";
    /** BR-DEM-001 감리 패키지가 있는데 책임감리자 없이 착공 (DEM_SUPERVISOR_REQUIRED) */
    public static final String SUPERVISOR_REQUIRED = "DEM-409-005";
    /** A8 감리자 공백(퇴사 · 자격 만료) — 대체 감리자 지정 전 일지 작성 차단 (DEM_SUPERVISOR_GAP) */
    public static final String SUPERVISOR_GAP = "DEM-409-006";
    /** 증거 사진 해시 불일치 (EVIDENCE_HASH_MISMATCH) */
    public static final String EVIDENCE_HASH_MISMATCH = "DEM-409-007";
    /** 같은 증거 사진(sha256)이 다른 업무 패키지에 이미 등록됨 — 보안 검토 2026-10-19 */
    public static final String EVIDENCE_DUPLICATE_OTHER_PACKAGE = "DEM-409-008";
    /** 일지 · 리비전 버전 불일치(If-Match) 는 공통 CORE-412-001 */
    public static final String INVALID = "DEM-422-001";
    /** BR-DEM-002 / TC-DEM-001 자격 · 퇴사 (DEM_SUPERVISOR_NOT_QUALIFIED) */
    public static final String SUPERVISOR_NOT_QUALIFIED = "DEM-422-002";
    /** BR-DEM-003 / TC-DEM-002 동시 감리 한도 (DEM_SUPERVISOR_OVER_LIMIT) */
    public static final String SUPERVISOR_OVER_LIMIT = "DEM-422-003";
    /** BR-DEM-005 / TC-DEM-004 필수 사진 누락 (DEM_PHOTO_REQUIRED) */
    public static final String PHOTO_REQUIRED = "DEM-422-004";
    /** 효력일 규칙 · 점검표 템플릿 없음 (DEM_RULE_MISSING · DEM_TEMPLATE_MISSING) */
    public static final String RULE_MISSING = "DEM-422-005";
}
