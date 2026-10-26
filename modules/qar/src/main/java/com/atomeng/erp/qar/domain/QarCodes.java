package com.atomeng.erp.qar.domain;

/** QAR 오류 코드 (설계 07 · 07-IMPL) — HTTP 상태는 코드 가운데 숫자. 설계 문자열 사유(QAR_SOD_VIOLATION 등)는 problem reason. */
public final class QarCodes {
    private QarCodes() {}

    public static final String NOT_FOUND = "QAR-404-001";
    /** SoD 위반(BR-QAR-002, TC-QAR-002) — QAR_SOD_VIOLATION */
    public static final String SOD_VIOLATION = "QAR-403-001";
    /** 코멘트 작성자만 해결 확인 · 재오픈 · 철회(BR-QAR-004) — QAR_ONLY_COMMENT_AUTHOR */
    public static final String ONLY_COMMENT_AUTHOR = "QAR-403-002";
    /** 해당 단계 담당자가 아님 */
    public static final String NOT_ASSIGNEE = "QAR-403-003";
    /** 보고서 작성 측(PM · ENG)만 할 수 있는 동작 */
    public static final String NOT_AUTHOR_SIDE = "QAR-403-004";
    /** QAR-03b 수동 배정 · 재배정에서 자기 자신을 QA 검토자로 지정 */
    public static final String SELF_ASSIGNMENT = "QAR-403-005";
    /** 전이 표에 없는 (상태, 명령) — QAR_STATE */
    public static final String STATE = "QAR-409-001";
    /** 가드 실패 — 사유 전체 목록(violations) 포함(TC-QAR-001) */
    public static final String GUARD_FAILED = "QAR-409-002";
    /** 보고서당 검토 1건 */
    public static final String DUPLICATE_REVIEW = "QAR-409-003";
    /** 낙관적 잠금(version) 경합 — QAR_VERSION(TC-QAR-012) */
    public static final String VERSION = "QAR-409-004";
    /** waiver 같은 사람 · 이미 입력한 쪽 */
    public static final String WAIVER_CONFLICT = "QAR-409-005";
    /** 코멘트 상태 전이 불가 */
    public static final String COMMENT_STATE = "QAR-409-006";
    /** AI 제안 이미 결정됨 */
    public static final String AI_DECIDED = "QAR-409-007";
    /** 검토자 프로필 중복 등록 */
    public static final String PROFILE_EXISTS = "QAR-409-008";
    public static final String INVALID = "QAR-422-001";
    /** BLOCKING · INFO 결과 waiver 불가(BR-QAR-001 · 006, TC-QAR-009) — QAR_WAIVER_NOT_ALLOWED */
    public static final String WAIVER_NOT_ALLOWED = "QAR-422-002";
    /** 앵커가 블록 원문과 불일치(TC-QAR-014) */
    public static final String ANCHOR_INVALID = "QAR-422-003";
    /** AI 기각 사유 필수 — QAR_DISMISS_REASON_REQUIRED */
    public static final String DISMISS_REASON_REQUIRED = "QAR-422-004";
    /** 기준일에 배포된 AUTO-CHECK 규칙 세트 · 체크리스트 없음 */
    public static final String RULE_NOT_FOUND = "QAR-422-005";
}
