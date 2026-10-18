package com.atomeng.common.identity.authz;

import com.atomeng.common.identity.PermissionScope;
import com.atomeng.common.identity.Role;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 역할 → 권한 매핑 캐시 (core.role_permission, 설계 8.3-1). 10분 TTL, refresh() 로 즉시 무효화.
 */
public class PermissionRegistry {

    private static final Duration TTL = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private volatile Map<Role, Map<String, PermissionScope>> cache = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public PermissionRegistry(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 주어진 역할들이 permission 을 가지면 가장 넓은 범위를 반환. */
    public Optional<PermissionScope> scopeFor(Collection<Role> roles, String permission) {
        var snapshot = snapshot();
        PermissionScope best = null;
        for (Role r : roles) {
            PermissionScope s = snapshot.getOrDefault(r, Map.of()).get(permission);
            if (s != null) {
                best = s.widest(best);
            }
        }
        return Optional.ofNullable(best);
    }

    public Map<String, PermissionScope> permissionsOf(Role role) {
        return snapshot().getOrDefault(role, Map.of());
    }

    public synchronized void refresh() {
        Map<Role, Map<String, PermissionScope>> m = new EnumMap<>(Role.class);
        jdbc.query("SELECT role, permission, scope FROM core.role_permission", rs -> {
            String permission = rs.getString("permission");
            PermissionScope scope = PermissionScope.valueOf(rs.getString("scope"));
            Role.parse(rs.getString("role")).ifPresent(role -> m.computeIfAbsent(role, k -> new HashMap<>())
                    .put(permission, scope));
        });
        cache = m;
        loadedAt = clock.instant();
    }

    private Map<Role, Map<String, PermissionScope>> snapshot() {
        if (loadedAt.plus(TTL).isBefore(clock.instant()) || cache.isEmpty()) {
            refresh();
        }
        return cache;
    }
}
