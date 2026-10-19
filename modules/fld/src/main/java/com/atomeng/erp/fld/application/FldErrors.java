package com.atomeng.erp.fld.application;

/** FLD 오류 코드 (설계 05 B4 · 05-IMPL) — HTTP 상태는 코드 가운데 숫자, 설계 문자열 코드는 problem 의 reason 속성 */
public final class FldErrors {
    private FldErrors() {}

    /** ENG 가 본인 충돌이 아닌 충돌 해결 · PM 전용 작업 */
    public static final String FORBIDDEN = "FLD-403-001";
    public static final String NOT_FOUND = "FLD-404-001";
    public static final String IMMUTABLE = "FLD-409-001";
    /** 이어 올리기 Upload-Offset 이 서버 수신 바이트와 다름 (tus 409) */
    public static final String OFFSET_MISMATCH = "FLD-409-002";
    public static final String UPLOAD_NOT_OPEN = "FLD-409-003";
    /** BR-FLD-002 / TC-FLD-006 원본 해시 불일치 → REJECTED · 재업로드 */
    public static final String PHOTO_HASH_MISMATCH = "FLD-409-004";
    public static final String PHOTO_EXISTS = "FLD-409-005";
    public static final String CONFLICT_RESOLVED = "FLD-409-006";
    public static final String ROUND_EXISTS = "FLD-409-007";
    public static final String CHUNK_TOO_LARGE = "FLD-413-001";
    /** 보안 검토 FLD-02: 신고 원본 크기가 상한(atom.fld.upload.max-file-bytes) 초과 */
    public static final String FILE_TOO_LARGE = "FLD-413-002";
    /** 보안 검토 FLD-02: 업로드 세션 만료(expires_at 경과) — 새 세션으로 다시 올린다 */
    public static final String UPLOAD_EXPIRED = "FLD-410-001";
    /** 보안 검토 FLD-02: 사용자당 열린 업로드 세션 상한(atom.fld.upload.max-open-sessions-per-user) 초과 */
    public static final String TOO_MANY_SESSIONS = "FLD-429-001";
    /** 보안 검토 FLD-02/05: 허용되지 않는 MIME, 또는 매직 바이트로 판별한 실제 형식 ≠ 신고 MIME */
    public static final String MEDIA_TYPE = "FLD-422-004";
    public static final String INVALID = "FLD-422-001";
    /** FR-FLD-019 / TC-FLD-011 구역 잠금 검증 실패 (FLD_ZONE_NOT_READY) */
    public static final String ZONE_NOT_READY = "FLD-422-002";
    public static final String CHUNK_CHECKSUM = "FLD-422-003";
}
