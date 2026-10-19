package com.atomeng.erp.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.migration.Migrations.BaselineEntry;
import com.atomeng.erp.migration.Migrations.Script;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flyway 마이그레이션 번호 규칙 — 빌드 시점 검사(DB 불필요). README "마이그레이션 규칙".
 *
 * <ol>
 *   <li>이름: {@code V<4자리 전역 번호>__<모듈>_<설명>.sql}, 모듈 접두 = 놓인 폴더의 모듈</li>
 *   <li>번호는 모든 모듈 · ext 를 통틀어 유일</li>
 *   <li>기준선({@code db/migration-baseline.txt})에 있는 파일은 그대로 존재하고 내용(sha256)이 같다 — 적용된 마이그레이션 수정 금지</li>
 *   <li>기준선에 없는 새 파일은 번호가 기준선 최대 번호보다 커야 한다 — 이미 적용된 DB 에서 건너뛰거나 out-of-order 가 되는 추가 금지</li>
 * </ol>
 *
 * <p>기준선 갱신(릴리스 직전 · 새 마이그레이션 확정 시): {@code -PupdateMigrationBaseline} 으로 이 시험을 실행하면 파일을 다시 쓴다.
 */
class MigrationBaselineTest {

    static List<Script> scripts;

    @BeforeAll
    static void scan() {
        scripts = Migrations.scanClasspath();
        if (Boolean.getBoolean("atom.migration.baseline.update")) {
            Migrations.writeBaseline(scripts);
        }
    }

    @Test
    @DisplayName("모든 모듈의 마이그레이션이 클래스패스에 있다(모듈 13개 + ext)")
    void allModulesPresent() {
        assertThat(scripts).isNotEmpty();
        assertThat(scripts.stream().filter(s -> !s.ext()).map(Script::module).distinct())
                .containsExactlyInAnyOrderElementsOf(Migrations.MODULE_ORDER);
    }

    @Test
    @DisplayName("이름 규칙: V<4자리>__<모듈>_<설명>.sql, 모듈 접두 = 폴더 모듈(ext 는 알려진 모듈)")
    void namingConvention() {
        var bad = new ArrayList<String>();
        for (Script s : scripts) {
            if (s.version() < 0) {
                bad.add(s.path() + " — 이름 형식 위반(예: V0042__fac_add_column.sql)");
                continue;
            }
            String[] parts = s.path().split("/");
            if (s.ext()) {
                if (!Migrations.MODULE_ORDER.contains(s.module())) {
                    bad.add(s.path() + " — ext 마이그레이션 접두가 알려진 모듈이 아님");
                }
            } else if (!parts[2].equals(s.module())) {
                bad.add(s.path() + " — 접두 '" + s.module() + "' ≠ 폴더 '" + parts[2] + "'");
            }
        }
        assertThat(bad).as("마이그레이션 이름 규칙 위반").isEmpty();
    }

    @Test
    @DisplayName("번호는 전역 유일(모듈 · ext 통틀어)")
    void versionsUnique() {
        Map<Integer, List<String>> byVersion = scripts.stream().filter(s -> s.version() >= 0)
                .collect(Collectors.groupingBy(Script::version, Collectors.mapping(Script::path, Collectors.toList())));
        assertThat(byVersion.entrySet().stream().filter(e -> e.getValue().size() > 1).map(Map.Entry::toString))
                .as("같은 번호를 쓰는 마이그레이션").isEmpty();
    }

    @Test
    @DisplayName("기준선 파일은 그대로 존재하고 내용이 바뀌지 않았다(적용된 마이그레이션 수정 금지)")
    void baselineScriptsUnchanged() {
        List<BaselineEntry> baseline = Migrations.readBaseline();
        assertThat(baseline).as("db/migration-baseline.txt 비어 있음").isNotEmpty();
        Map<String, Script> byPath = scripts.stream().collect(Collectors.toMap(Script::path, Function.identity()));
        var problems = new ArrayList<String>();
        for (BaselineEntry b : baseline) {
            Script s = byPath.get(b.path());
            if (s == null) {
                problems.add(b.path() + " — 기준선에 있으나 클래스패스에 없음(삭제 · 이름 변경 금지)");
            } else if (s.version() != b.version() || !s.module().equals(b.module())) {
                problems.add(b.path() + " — 번호/모듈이 기준선과 다름");
            } else if (!s.sha256().equals(b.sha256())) {
                problems.add(b.path() + " — 내용이 기준선과 다름(이미 배포된 마이그레이션은 고치지 말고 새 번호로 추가)");
            }
        }
        assertThat(problems).as("기준선 위반").isEmpty();
    }

    @Test
    @DisplayName("새 마이그레이션 번호 > 기준선 최대 번호(out-of-order 추가 금지)")
    void newMigrationsAboveBaseline() {
        List<BaselineEntry> baseline = Migrations.readBaseline();
        int max = Migrations.maxBaselineVersion(baseline);
        var known = baseline.stream().map(BaselineEntry::path).collect(Collectors.toSet());
        var low = scripts.stream().filter(s -> !known.contains(s.path()) && s.version() <= max)
                .map(s -> s.path() + " (V" + s.versionText() + " ≤ 기준선 최대 V%04d — 다음 번호 V%04d 를 쓸 것)"
                        .formatted(max, max + 1))
                .toList();
        assertThat(low).as("기준선보다 낮은 번호의 새 마이그레이션").isEmpty();
    }

    @Test
    @DisplayName("기준선 순서 = 모듈 의존 순서(core → fac → hrq → eqp → sal → fld → rpt → qar → cst → dem → ptl → aip → fcd)")
    void baselineFollowsModuleDependencyOrder() {
        // 기준선(최초 일괄 재번호) 안에서는 모듈 순서가 단조여야 한다. 기준선 이후 추가분은 어느 모듈이든 다음 번호.
        List<BaselineEntry> baseline = Migrations.readBaseline();
        var rank = new HashMap<String, Integer>();
        for (int i = 0; i < Migrations.MODULE_ORDER.size(); i++) {
            rank.put(Migrations.MODULE_ORDER.get(i), i);
        }
        // 최초 재번호 범위(V0001~V0034)만 검사 — 이후 기준선 갱신분은 시간순
        var initial = baseline.stream().filter(b -> b.version() <= INITIAL_RENUMBER_MAX).toList();
        int[] ranks = initial.stream().mapToInt(b -> rank.getOrDefault(b.module(), -1)).toArray();
        int[] sorted = ranks.clone();
        Arrays.sort(sorted);
        assertThat(ranks).as("최초 재번호 기준선의 모듈 순서").containsExactly(sorted);
        assertThat(initial).hasSize(INITIAL_RENUMBER_MAX);
    }

    @Test
    @DisplayName("application.yml: out-of-order 금지, validate-on-migrate, postgis 프로필도 out-of-order 없음")
    void flywayConfigForbidsOutOfOrder() {
        var s = Migrations.appFlywaySettings();
        assertThat(s.outOfOrder()).isFalse();
        assertThat(s.validateOnMigrate()).isTrue();
        assertThat(Migrations.postgisProfileFlyway()).doesNotContainKey("out-of-order");
        // 모든 모듈 위치가 기본 locations 에 있다
        var locs = Arrays.stream(s.locations()).map(String::strip).toList();
        for (String m : Migrations.MODULE_ORDER) {
            assertThat(locs).contains("classpath:db/migration/" + m);
        }
    }

    /** 2026-10 최초 일괄 재번호(V0001~V0034) */
    static final int INITIAL_RENUMBER_MAX = 34;
}
