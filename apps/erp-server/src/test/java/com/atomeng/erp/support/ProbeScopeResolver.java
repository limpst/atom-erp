package com.atomeng.erp.support;

import com.atomeng.common.identity.authz.ScopeResolver;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** CMN-05 시험용 해석기 — 시험이 자원 id → 판정 대상을 직접 등록한다(합성 값). */
@Component
public class ProbeScopeResolver implements ScopeResolver {

    public static final Map<UUID, ScopeTarget> TARGETS = new ConcurrentHashMap<>();

    @Override
    public Optional<ScopeTarget> resolve(Object resourceKey) {
        return Optional.ofNullable(TARGETS.get((UUID) resourceKey));
    }
}
