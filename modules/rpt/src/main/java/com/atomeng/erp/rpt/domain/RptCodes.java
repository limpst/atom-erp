package com.atomeng.erp.rpt.domain;

/** RPT 오류 코드 (설계 06 · 06-IMPL) — HTTP 상태는 코드 가운데 숫자. 도메인 · 애플리케이션 공용. */
public final class RptCodes {
    private RptCodes() {}

    public static final String NOT_FOUND = "RPT-404-001";
    /** A6 리비전 상태 전이 불가 (설계 RPT_STATE) */
    public static final String STATE = "RPT-409-001";
    /** TC-RPT-003 BLOCKING 사전검증 이슈가 있어 compose 불가 (RPT_BLOCKING_ISSUES) */
    public static final String BLOCKING_ISSUES = "RPT-409-002";
    /** BR-RPT-005 책임기술자 등급 확정 전 검토 요청 · 발행 불가 */
    public static final String GRADE_NOT_DECIDED = "RPT-409-003";
    /** 최신 본문으로 렌더된 PDF 없음(편집 후 재렌더 필요) */
    public static final String RENDER_STALE = "RPT-409-004";
    /** v0.2 납품 확인 중복 */
    public static final String ALREADY_DELIVERED = "RPT-409-005";
    /** 정정본은 발행(납품)된 현재 리비전에서만 */
    public static final String CORRECTION_NOT_ALLOWED = "RPT-409-006";
    /** BR-RPT-008 발행본 불변(DB 트리거 RPT-409-010) */
    public static final String IMMUTABLE = "RPT-409-010";
    public static final String INVALID = "RPT-422-001";
    /** BR-RPT-001 스냅샷 파생(잠금) 블록 수정 · 삭제 */
    public static final String LOCKED_BLOCK = "RPT-422-002";
    /** BR-RPT-005 override 사유 100자 · 근거 결함 1건 이상 */
    public static final String OVERRIDE_REASON = "RPT-422-003";
    /** BR-RPT-002 기준일에 DEPLOYED rule table 없음 */
    public static final String RULE_NOT_FOUND = "RPT-422-004";
    /** rule table 구조 · 의미 오류 */
    public static final String RULE_INVALID = "RPT-422-005";
    /** HWPX 템플릿 · 생성 결과 검증 실패 */
    public static final String HWPX_INVALID = "RPT-422-006";
    /** 기준일에 DEPLOYED 템플릿 버전 없음 */
    public static final String TEMPLATE_NOT_FOUND = "RPT-422-007";
    /** PDF 변환기(Gotenberg) 장애 */
    public static final String PDF_UNAVAILABLE = "RPT-503-001";
}
