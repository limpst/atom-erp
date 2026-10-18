package com.atomeng.common.identity;

/**
 * 권한 범위 (core.role_permission.scope). 설계 8.2 기호: ●=ALL, ◐=PROJECT/SELF/CLIENT.
 * 여러 역할이 같은 권한을 가지면 가장 넓은 범위를 쓴다(ALL > PROJECT > CLIENT > SELF).
 */
public enum PermissionScope {
    SELF(0), CLIENT(1), PROJECT(2), ALL(3);

    private final int breadth;

    PermissionScope(int breadth) {
        this.breadth = breadth;
    }

    public PermissionScope widest(PermissionScope other) {
        return other == null || breadth >= other.breadth ? this : other;
    }
}
