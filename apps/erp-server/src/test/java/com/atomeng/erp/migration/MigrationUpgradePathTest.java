package com.atomeng.erp.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.testing.EmbeddedPg;
import com.atomeng.erp.migration.Migrations.BaselineEntry;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 업그레이드 경로 시험 — 내장 PostgreSQL 16, 합성 데이터만. README "마이그레이션 규칙".
 *
 * <p>① 기준선(db/migration-baseline.txt 의 스냅숏 목록)까지 마이그레이션한 DB 에 ② 이후 마이그레이션을 적용하면 실패 없이
 * ③ 새 설치와 같은 스키마(테이블 · 컬럼 · 제약 · 인덱스 · 트리거 · RLS 정책 · 함수 · 뷰 · 권한 · 시드 행 수 · Flyway 이력)가 된다.
 * 기준선 파일은 내용 고정(MigrationBaselineTest)이라 현재 클래스패스 파일로 target=기준선 최대 번호까지 올리면 곧 이전 릴리스 상태다.
 * 기준선 이후 마이그레이션이 아직 없을 때도 경로가 실제로 검증되도록 시험 전용 탐침(V9001)을 더한 경우도 확인한다.
 * Flyway 설정(위치 · 스키마 · out-of-order)은 application.yml 값을 그대로 쓴다. PostGIS ext 는 내장 PG 에 없어 제외.
 */
class MigrationUpgradePathTest {

    static final String PROBE = "classpath:db/test-upgrade-probe";
    static final String LATE = "classpath:db/test-out-of-order";

    static Migrations.FlywaySettings settings;
    static List<BaselineEntry> baseline;
    static Set<String> schemas;

    @BeforeAll
    static void load() {
        settings = Migrations.appFlywaySettings();
        baseline = Migrations.readBaseline();
        schemas = new LinkedHashSet<>(Arrays.stream(settings.schemas()).map(String::strip).toList());
    }

    static Flyway flyway(String url, MigrationVersion target, String... extraLocations) {
        String[] locations = Stream.concat(Arrays.stream(settings.locations()).map(String::strip), Arrays.stream(extraLocations))
                .toArray(String[]::new);
        var cfg = Flyway.configure().dataSource(url, EmbeddedPg.user(), EmbeddedPg.password())
                .schemas(settings.schemas()).createSchemas(true)
                .locations(locations)
                .outOfOrder(settings.outOfOrder())
                .validateOnMigrate(settings.validateOnMigrate());
        if (target != null) {
            cfg.target(target);
        }
        return cfg.load();
    }

    static Map<String, List<String>> dump(String url) throws SQLException {
        return Migrations.dumpSchema(url, EmbeddedPg.user(), EmbeddedPg.password(), schemas);
    }

    static String freshInstall(String prefix, String... extra) {
        String url = EmbeddedPg.newDatabaseUrl(prefix);
        assertThat(flyway(url, null, extra).migrate().success).isTrue();
        return url;
    }

    /** 기준선까지 올린 DB — 적용 버전이 기준선 스냅숏 목록(ext 제외)과 정확히 같아야 한다 */
    static String baselineInstall(String prefix) {
        String url = EmbeddedPg.newDatabaseUrl(prefix);
        var target = MigrationVersion.fromVersion("%04d".formatted(Migrations.maxBaselineVersion(baseline)));
        assertThat(flyway(url, target).migrate().success).isTrue();
        var applied = Arrays.stream(flyway(url, null).info().applied())
                .filter(i -> i.getVersion() != null)
                .map(i -> i.getVersion().getVersion()).toList();
        var expected = baseline.stream().filter(b -> !b.path().startsWith("db/" + Migrations.EXT_DIR + "/"))
                .map(b -> "%04d".formatted(b.version())).toList();
        assertThat(applied).as("기준선 스냅숏 목록").containsExactlyElementsOf(expected);
        return url;
    }

    static void assertSameSchema(String upgradedUrl, String freshUrl) throws SQLException {
        var upgraded = dump(upgradedUrl);
        var fresh = dump(freshUrl);
        assertThat(upgraded.keySet()).isEqualTo(fresh.keySet());
        for (String k : fresh.keySet()) {
            if ("history".equals(k)) {
                // installed_rank 는 둘 다 1.. 연속이어야 하고, 버전 · 스크립트 · 체크섬이 같아야 한다
                assertThat(upgraded.get(k)).as("Flyway 이력").containsExactlyElementsOf(fresh.get(k));
            } else {
                assertThat(upgraded.get(k)).as("스키마 덤프 [%s] 업그레이드 = 새 설치", k)
                        .containsExactlyElementsOf(fresh.get(k));
            }
        }
        assertThat(fresh.get("relations")).as("덤프가 실제 스키마를 담는다").hasSizeGreaterThan(100);
        assertThat(fresh.get("policies")).as("RLS 정책 포함(ptl)").isNotEmpty();
        assertThat(fresh.get("triggers")).as("트리거 포함").isNotEmpty();
    }

    @Test
    @DisplayName("기준선 DB → 이후 마이그레이션 적용: 실패 없음, 스키마 = 새 설치")
    void upgradeFromBaselineEqualsFreshInstall() throws Exception {
        String upgraded = baselineInstall("mig_upg");
        var result = flyway(upgraded, null).migrate();
        assertThat(result.success).isTrue();
        assertThat(flyway(upgraded, null).validateWithResult().validationSuccessful).isTrue();
        assertSameSchema(upgraded, freshInstall("mig_fresh"));
    }

    @Test
    @DisplayName("기준선 DB → 새 마이그레이션(시험 탐침 V9001) 적용: 실패 없음, 스키마 = 탐침 포함 새 설치")
    void upgradeWithNewMigrationEqualsFreshInstall() throws Exception {
        String upgraded = baselineInstall("mig_upg_probe");
        var result = flyway(upgraded, null, PROBE).migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isGreaterThanOrEqualTo(1);
        String fresh = freshInstall("mig_fresh_probe", PROBE);
        assertSameSchema(upgraded, fresh);
        assertThat(dump(upgraded).get("relations")).anyMatch(r -> r.startsWith("core | zz_upgrade_probe |"));
    }

    @Test
    @DisplayName("릴리스마다 한 번호씩 올린 DB(V0001 → V0002 → …) = 새 설치")
    void stepwiseUpgradeEqualsFreshInstall() throws Exception {
        String url = EmbeddedPg.newDatabaseUrl("mig_step");
        MigrationInfo[] pending = flyway(url, null).info().pending();
        assertThat(pending).isNotEmpty();
        for (MigrationInfo m : pending) {
            var r = flyway(url, m.getVersion()).migrate();
            assertThat(r.success).as("V%s 단독 적용", m.getVersion()).isTrue();
            assertThat(r.migrationsExecuted).as("V%s 한 개만 적용", m.getVersion()).isEqualTo(1);
        }
        assertSameSchema(url, freshInstall("mig_step_fresh"));
    }

    @Test
    @DisplayName("이미 적용된 최대 번호보다 낮은 번호의 뒤늦은 마이그레이션 → 검증 실패(건너뛰지 않고 막힘), DB 변경 없음")
    void lateLowerVersionRejected() throws Exception {
        String url = freshInstall("mig_late");
        var before = dump(url);
        assertThatThrownBy(() -> flyway(url, null, LATE).migrate())
                .isInstanceOf(FlywayValidateException.class)
                .hasMessageContaining("0001.5");
        assertThat(dump(url)).isEqualTo(before);
    }
}
