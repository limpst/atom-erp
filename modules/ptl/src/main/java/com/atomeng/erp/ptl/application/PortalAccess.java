package com.atomeng.erp.ptl.application;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.Role;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 포털 호출자 판정 (09-IMPL Step 3 TenantContext 의 이번 단계 구현). 고객 컨텍스트는 <b>토큰 클레임에서만</b> 만든다 —
 * 요청 파라미터 · 본문의 고객사 값은 절대 쓰지 않는다. CUSTOMER 역할 + PORTAL realm + cid(core.client.id) 클레임이 모두
 * 있어야 하며, 내부 직원 토큰(SALES · PM … 이 ptl:share 권한을 가져도)은 403.
 */
@Component
public class PortalAccess {

    /** @param customerId cid 클레임(= core.client.id = RLS 키) · actorId 포털 사용자(sub 유래) */
    public record Customer(UUID customerId, UUID actorId) {}

    public Customer require() {
        AtomPrincipal p = AtomAuthentication.currentPrincipal()
                .orElseThrow(() -> new DomainException(PtlErrors.NOT_CUSTOMER, "포털 로그인이 필요합니다"));
        if (!p.has(Role.CUSTOMER) || p.realm() != Role.Realm.PORTAL) {
            throw new DomainException(PtlErrors.NOT_CUSTOMER, "고객 포털 계정(CUSTOMER)만 이용할 수 있습니다");
        }
        if (p.clientOrgId() == null) {
            throw new DomainException(PtlErrors.NOT_CUSTOMER, "고객사(cid) 클레임이 없는 포털 계정입니다");
        }
        return new Customer(p.clientOrgId(), p.actorId());
    }
}
