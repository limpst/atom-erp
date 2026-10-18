package com.atomeng.common.core.error;

/** 공통(CORE) 오류 코드 카탈로그 (설계 9.3·9.4, 00-IMPL Step 4·5). 메시지는 messages*.properties. */
public final class CoreErrors {
    private CoreErrors() {}

    public static final String MALFORMED_REQUEST = "CORE-400-001";
    public static final String UNAUTHENTICATED = "CORE-401-001";
    public static final String PERMISSION_DENIED = "CORE-403-001";
    public static final String OUT_OF_PROJECT_SCOPE = "CORE-403-002";
    public static final String NOT_FOUND = "CORE-404-001";
    public static final String INVALID_STATE_TRANSITION = "CORE-409-001";
    public static final String DATA_CONFLICT = "CORE-409-002";
    /** 같은 actor·key 요청이 아직 처리 중(동시 중복). 설계 9.4 */
    public static final String IDEMPOTENCY_IN_PROGRESS = "CORE-409-010";
    /** 낙관적 락 불일치(If-Match). 설계 9.1 */
    public static final String VERSION_MISMATCH = "CORE-412-001";
    public static final String VALIDATION_FAILED = "CORE-422-001";
    /** 같은 actor·key 인데 요청 해시가 다름(키 오용). 설계 9.4 */
    public static final String IDEMPOTENCY_KEY_REUSED = "CORE-422-009";
    /** field-app 등 필수 클라이언트가 Idempotency-Key 없이 POST. 설계 9.4 */
    public static final String IDEMPOTENCY_KEY_REQUIRED = "CORE-428-001";
    /** 수정 요청에 If-Match 누락. */
    public static final String IF_MATCH_REQUIRED = "CORE-428-002";
    public static final String INTERNAL_ERROR = "CORE-500-001";
}
