package com.atomeng.common.identity;

import com.atomeng.common.core.actor.CurrentActor;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.AuditorAware;

/** SecurityContext 기반 CurrentActor 구현 + JPA 감사자(created_by/updated_by) 제공. */
public class SecurityCurrentActor implements CurrentActor, AuditorAware<UUID> {

    @Override
    public Optional<Actor> current() {
        return AtomAuthentication.currentPrincipal().map(p -> new Actor(p.actorId(), p.clientId()));
    }

    /** 배치·이벤트 리스너처럼 인증 주체가 없으면 SYSTEM (00-IMPL Step 3 SecurityAuditorAware). */
    @Override
    public Optional<UUID> getCurrentAuditor() {
        return Optional.of(actorIdOrSystem());
    }
}
