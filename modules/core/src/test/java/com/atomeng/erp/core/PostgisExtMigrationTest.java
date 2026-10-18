package com.atomeng.erp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * PostGIS·btree_gist 확장 의존 마이그레이션(db/migration-ext) 검증.
 *
 * <p>내장 PostgreSQL(zonky)에는 PostGIS 가 없어 기본 빌드에서는 건너뛴다. 확장이 설치된 빈 DB 를 주면 실행된다:
 * {@code ATOM_PG_POSTGIS_URL=jdbc:postgresql://host:5432/atom_test?user=..&password=..}
 * (예: docker postgis/postgis:16-3.4 또는 CloudNativePG PostGIS 이미지)
 */
@EnabledIfEnvironmentVariable(named = "ATOM_PG_POSTGIS_URL", matches = ".+")
class PostgisExtMigrationTest {

    private static final String URL = System.getenv("ATOM_PG_POSTGIS_URL");

    @Test
    @DisplayName("TC-CORE-008: rule_set 같은 유형 ACTIVE 기간 중복 → EXCLUDE 제약 위반")
    void tcCore008_overlappingActiveRuleRejected() throws Exception {
        var flyway = Flyway.configure().dataSource(URL, null, null).schemas("core").createSchemas(true)
                .locations("classpath:db/migration/core", "classpath:db/migration-ext").load();
        assertThat(flyway.migrate().success).isTrue();
        try (var c = DriverManager.getConnection(URL); var st = c.createStatement()) {
            st.execute("""
                    INSERT INTO core.rule_set(rule_type, legal_basis, effective_from, effective_to, body, status)
                    VALUES ('INSPECTION_CYCLE', '합성 근거 v1', DATE '2026-01-01', DATE '2026-12-31', '{}', 'ACTIVE')""");
            assertThatThrownBy(() -> st.execute("""
                    INSERT INTO core.rule_set(rule_type, legal_basis, effective_from, effective_to, body, status)
                    VALUES ('INSPECTION_CYCLE', '합성 근거 v2', DATE '2026-06-01', DATE '2027-05-31', '{}', 'ACTIVE')"""))
                    .hasMessageContaining("exclusion constraint");
        }
    }
}
