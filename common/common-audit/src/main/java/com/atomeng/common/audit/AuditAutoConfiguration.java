package com.atomeng.common.audit;

import com.atomeng.common.core.actor.CurrentActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

@AutoConfiguration
public class AuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    AuditLog auditLog(JdbcTemplate jdbc, ObjectMapper om, ObjectProvider<CurrentActor> actor) {
        return new AuditLog(jdbc, om, actor.getIfAvailable(() -> Optional::empty));
    }

    @Bean
    AuditAspect auditAspect(AuditLog auditLog) {
        return new AuditAspect(auditLog);
    }

    @Bean
    @ConditionalOnProperty(prefix = "atom.audit", name = "partition-maintenance-enabled", havingValue = "true",
            matchIfMissing = true)
    AuditPartitionMaintenance auditPartitionMaintenance(JdbcTemplate jdbc) {
        return new AuditPartitionMaintenance(jdbc);
    }
}
