package com.atomeng.erp.ptl.application;

/** PTL 오류 코드 (설계 09 B4 · 09-IMPL) — HTTP 상태는 코드 가운데 숫자 */
public final class PtlErrors {
    private PtlErrors() {}

    /** 포털 고객(CUSTOMER 역할 + cid 클레임)이 아닌 호출 — 내부 역할 토큰 · cid 없는 토큰 */
    public static final String NOT_CUSTOMER = "PTL-403-001";
    /** 없음 또는 다른 고객사 자원(존재 은닉, TC-PTL-01 · 05) */
    public static final String NOT_FOUND = "PTL-404-001";
    /** BR-PTL-012 정정본으로 대체된 보고서는 열람만 — 다운로드 불가 */
    public static final String REPORT_SUPERSEDED = "PTL-409-001";
    /** 회수(REVOKED)된 공개본 */
    public static final String REPORT_REVOKED = "PTL-409-002";
    /** 발행본 원본 해시가 공개 시점 해시와 다름(무결성) */
    public static final String SOURCE_INTEGRITY = "PTL-409-003";
    public static final String INVALID = "PTL-422-001";
    /** PTL-03 고객사별 다운로드 속도 제한 초과(atom.ptl.download.max-per-minute) */
    public static final String DOWNLOAD_RATE_LIMITED = "PTL-429-001";
}
