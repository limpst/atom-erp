package com.atomeng.common.identity;

import java.util.Arrays;
import java.util.Optional;

/**
 * 역할 (설계 00 v0.3 §8.2). v0.3 에서 EMPLOYEE·FIN·AUDITOR 신설(12 FCD 모듈).
 * Keycloak 토큰에는 역할만 담고, 권한({module}:{resource}:{action})은 core.role_permission 에서 해석한다.
 */
public enum Role {
    EXEC(Realm.INTERNAL, true, "임원"),
    SALES(Realm.INTERNAL, false, "영업"),
    PM(Realm.INTERNAL, false, "책임기술자"),
    ENG(Realm.INTERNAL, false, "참여기술자"),
    QA(Realm.INTERNAL, true, "품질검토자"),
    ADMIN(Realm.INTERNAL, true, "경영지원"),
    LABELER(Realm.INTERNAL, false, "라벨러"),
    CUSTOMER(Realm.PORTAL, false, "고객(건물주·관리주체)"),
    SYSADMIN(Realm.INTERNAL, true, "시스템 관리자"),
    /** v0.3: 전 직원 기본 역할(법인카드 사용자) */
    EMPLOYEE(Realm.INTERNAL, false, "직원"),
    /** v0.3: 재무·회계 */
    FIN(Realm.INTERNAL, false, "재무"),
    /** v0.3: 감사 담당 — 업무 데이터 수정 권한 없음(직무 분리), 모든 조회는 VIEW_PII 감사 대상 */
    AUDITOR(Realm.INTERNAL, false, "감사");

    private final Realm realm;
    private final boolean mfaRequired;
    private final String label;

    Role(Realm realm, boolean mfaRequired, String label) {
        this.realm = realm;
        this.mfaRequired = mfaRequired;
        this.label = label;
    }

    /** atom-internal(직원) / atom-portal(고객) Realm (설계 8.1) */
    public Realm realm() {
        return realm;
    }

    /** MFA(TOTP) 필수 대상: EXEC, ADMIN, SYSADMIN, QA (설계 8.1) */
    public boolean mfaRequired() {
        return mfaRequired;
    }

    public String label() {
        return label;
    }

    public String authority() {
        return "ROLE_" + name();
    }

    /** 알 수 없는 역할 문자열은 무시(토큰에 Keycloak 기본 역할 offline_access 등이 섞여 온다). */
    public static Optional<Role> parse(String s) {
        if (s == null) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(r -> r.name().equalsIgnoreCase(s.trim())).findFirst();
    }

    public enum Realm { INTERNAL, PORTAL }
}
