package com.atomeng.erp.aip.application;

import com.atomeng.common.identity.authz.ScopeResolver;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Job id → 범위 판정 대상 (보안 검토 CMN-05). 프로젝트가 있으면 투입 인원, 없으면 요청자 본인만(AIP-05 와 같은 규칙).
 * {@code @RequirePermission(scopeResolver = AipJobScopeResolver.class, resourceParam = "id")}
 */
@Component
public class AipJobScopeResolver implements ScopeResolver {

    private final AipJdbc db;

    public AipJobScopeResolver(AipJdbc db) {
        this.db = db;
    }

    @Override
    public Optional<ScopeTarget> resolve(Object resourceKey) {
        if (!(resourceKey instanceof UUID id)) {
            return Optional.empty();
        }
        return db.jdbc().query("SELECT project_id, requested_by FROM aip.job WHERE id = ?",
                (rs, n) -> new ScopeTarget(rs.getObject("project_id", UUID.class),
                        rs.getObject("requested_by", UUID.class), null), id).stream().findFirst();
    }
}
