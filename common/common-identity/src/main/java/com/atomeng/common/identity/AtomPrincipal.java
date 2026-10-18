package com.atomeng.common.identity;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/**
 * 인증 주체.
 *
 * @param subject     OIDC sub
 * @param employeeId  eid 클레임 (core.employee.id) — 직원 Realm
 * @param clientOrgId cid 클레임 (고객사 core.client.id) — 포털 Realm
 * @param roles       역할 (알 수 없는 역할은 제외)
 * @param realm       INTERNAL / PORTAL
 * @param clientId    azp (erp-web, field-app, portal-web …)
 */
public record AtomPrincipal(String subject, UUID employeeId, UUID clientOrgId, Set<Role> roles,
                            Role.Realm realm, String clientId) {

    public AtomPrincipal {
        roles = Set.copyOf(roles);
    }

    /** 멱등 키·감사 로그의 actor_id: 직원 ID, 없으면 sub 로부터 결정적 UUID(v3). */
    public UUID actorId() {
        if (employeeId != null) {
            return employeeId;
        }
        return UUID.nameUUIDFromBytes(("sub:" + subject).getBytes(StandardCharsets.UTF_8));
    }

    public boolean has(Role role) {
        return roles.contains(role);
    }
}
