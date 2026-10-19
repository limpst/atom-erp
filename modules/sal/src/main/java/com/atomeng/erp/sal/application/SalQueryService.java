package com.atomeng.erp.sal.application;

import com.atomeng.erp.sal.api.SalQueryApi;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link SalQueryApi} 구현 */
@Service
class SalQueryService implements SalQueryApi {

    private final JdbcTemplate jdbc;

    SalQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> clientOfOpportunity(UUID opportunityId) {
        return jdbc.queryForList("SELECT client_id FROM sal.opportunity WHERE id = ? AND client_id IS NOT NULL",
                UUID.class, opportunityId).stream().findFirst();
    }
}
