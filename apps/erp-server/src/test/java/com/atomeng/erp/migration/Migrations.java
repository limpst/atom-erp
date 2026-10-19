package com.atomeng.erp.migration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.Yaml;

/**
 * Flyway 마이그레이션 규칙 시험 공용 도구 (README "마이그레이션 규칙").
 *
 * <ul>
 *   <li>클래스패스의 모든 모듈 마이그레이션(db/migration/{모듈}, db/migration-ext) 수집</li>
 *   <li>커밋된 기준선 {@code db/migration-baseline.txt} 읽기 · 다시 쓰기</li>
 *   <li>application.yml 의 Flyway 위치 · 스키마 · out-of-order 설정 읽기(시험이 실제 설정을 쓰도록)</li>
 *   <li>스키마 덤프(pg_catalog · information_schema) — 업그레이드 경로 = 새 설치 비교용</li>
 * </ul>
 */
final class Migrations {

    /** 모듈 접두 설명이 붙은 전역 단조 번호: V0001__core_schema.sql */
    static final Pattern NAME = Pattern.compile("^V(\\d{4})__([a-z]+)_[a-z0-9_]+\\.sql$");

    /** 의존 순서(외래키 · 읽기 모델 방향) — 새 설치 순서가 이 순서와 맞아야 한다 */
    static final List<String> MODULE_ORDER =
            List.of("core", "fac", "hrq", "eqp", "sal", "fld", "rpt", "qar", "cst", "dem", "ptl", "aip", "fcd");

    static final String EXT_DIR = "migration-ext";

    private Migrations() {}

    /** 클래스패스 마이그레이션 1개 */
    record Script(int version, String module, String path, String fileName, String sha256, boolean ext) {
        String versionText() {
            return "%04d".formatted(version);
        }
    }

    /** 기준선 1행 */
    record BaselineEntry(int version, String module, String path, String sha256) {}

    // ------------------------------------------------------------------ 수집

    static List<Script> scanClasspath() {
        var resolver = new PathMatchingResourcePatternResolver(Migrations.class.getClassLoader());
        var out = new ArrayList<Script>();
        try {
            for (String pattern : List.of("classpath*:db/migration/*/*.sql", "classpath*:db/" + EXT_DIR + "/*.sql")) {
                for (Resource r : resolver.getResources(pattern)) {
                    out.add(toScript(r));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        out.sort((a, b) -> Integer.compare(a.version(), b.version()));
        return out;
    }

    private static Script toScript(Resource r) throws IOException {
        String url = r.getURL().toString().replace('\\', '/');
        int idx = url.lastIndexOf("/db/");
        if (idx < 0) {
            throw new IllegalStateException("마이그레이션 경로 해석 실패: " + url);
        }
        String path = url.substring(idx + 1);                 // db/migration/core/V0001__core_schema.sql
        String[] parts = path.split("/");
        String fileName = parts[parts.length - 1];
        boolean ext = EXT_DIR.equals(parts[1]);
        Matcher m = NAME.matcher(fileName);
        if (!m.matches()) {
            // 이름 규칙 위반도 Script 로 돌려주고 시험이 메시지로 실패시킨다
            return new Script(-1, ext ? "?" : parts[2], path, fileName, sha256(r), ext);
        }
        return new Script(Integer.parseInt(m.group(1)), m.group(2), path, fileName, sha256(r), ext);
    }

    /** 줄바꿈(CRLF/LF) · BOM 무관 내용 해시 — Windows autocrlf 체크아웃에서도 같은 값 */
    static String sha256(Resource r) throws IOException {
        try (InputStream in = r.getInputStream()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s.startsWith("﻿")) {
                s = s.substring(1);
            }
            s = s.replace("\r\n", "\n");
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 기준선

    static Path repoRoot() {
        String prop = System.getProperty("atom.repo.root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop);
        }
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.exists(p.resolve("settings.gradle.kts"))) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IllegalStateException("저장소 루트(settings.gradle.kts)를 찾지 못함");
        }
        return p;
    }

    static Path baselineFile() {
        return repoRoot().resolve("db").resolve("migration-baseline.txt");
    }

    static List<BaselineEntry> readBaseline() {
        try {
            var out = new ArrayList<BaselineEntry>();
            for (String line : Files.readAllLines(baselineFile(), StandardCharsets.UTF_8)) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                String[] f = Arrays.stream(t.split("\\|")).map(String::strip).toArray(String[]::new);
                if (f.length != 4) {
                    throw new IllegalStateException("기준선 형식 오류(버전|모듈|경로|sha256): " + line);
                }
                out.add(new BaselineEntry(Integer.parseInt(f[0]), f[1], f[2], f[3]));
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static int maxBaselineVersion(List<BaselineEntry> baseline) {
        return baseline.stream().mapToInt(BaselineEntry::version).max().orElse(0);
    }

    static void writeBaseline(List<Script> scripts) {
        var sb = new StringBuilder();
        sb.append("""
                # Flyway 마이그레이션 기준선 (README "마이그레이션 규칙") — 손으로 고치지 말 것.
                # 배포(릴리스)에 포함된 마이그레이션 목록. 이 목록의 파일은 내용을 바꿀 수 없고(sha256 고정),
                # 새 마이그레이션 버전은 이 목록의 최대 버전보다 커야 한다(MigrationBaselineTest).
                # 다시 쓰기: ./gradlew :apps:erp-server:test --tests '*MigrationBaselineTest*' -PupdateMigrationBaseline
                # 형식: 버전 | 모듈 | 클래스패스 경로 | sha256(UTF-8, LF 정규화)
                """);
        for (Script s : scripts) {
            sb.append(s.versionText()).append(" | ").append(s.module()).append(" | ")
                    .append(s.path()).append(" | ").append(s.sha256()).append('\n');
        }
        try {
            Files.createDirectories(baselineFile().getParent());
            Files.writeString(baselineFile(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------ application.yml

    /** application.yml 첫 문서의 spring.flyway 설정 */
    record FlywaySettings(String[] schemas, String[] locations, boolean outOfOrder, boolean validateOnMigrate) {}

    static FlywaySettings appFlywaySettings() {
        try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream("application.yml")) {
            if (in == null) {
                throw new IllegalStateException("application.yml 없음");
            }
            Object first = new Yaml().loadAll(in).iterator().next();
            Object flyway = child(child(first, "spring"), "flyway");
            return new FlywaySettings(
                    String.valueOf(child(flyway, "schemas")).split(","),
                    String.valueOf(child(flyway, "locations")).split(","),
                    Boolean.TRUE.equals(child(flyway, "out-of-order")),
                    !Boolean.FALSE.equals(child(flyway, "validate-on-migrate")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** postgis 프로필 문서의 spring.flyway 값(키 → 값). 없으면 빈 맵 */
    static Map<String, Object> postgisProfileFlyway() {
        try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream("application.yml")) {
            for (Object doc : new Yaml().loadAll(in)) {
                Object spring = child(doc, "spring");
                Object profile = child(child(child(spring, "config"), "activate"), "on-profile");
                if ("postgis".equals(profile) && child(spring, "flyway") instanceof Map<?, ?> m) {
                    var out = new LinkedHashMap<String, Object>();
                    m.forEach((k, v) -> out.put(String.valueOf(k), v));
                    return out;
                }
            }
            return Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Object child(Object node, String key) {
        return node instanceof Map<?, ?> m ? m.get(key) : null;
    }

    // ------------------------------------------------------------------ 스키마 덤프

    /**
     * 비교용 스키마 덤프. 이름 · 정의만 담고 OID · 시각 · DB 이름처럼 설치마다 다른 값은 뺀다.
     * 항목: 테이블/뷰/물리뷰 · 컬럼 · 제약 · 인덱스 · 트리거 · RLS 정책/플래그 · 함수 본문 · 뷰 정의 · 시퀀스 ·
     * 파티션 상속 · 테이블 권한 · 행 수(시드 동일성) · Flyway 이력(버전 · 스크립트 · 체크섬).
     */
    static Map<String, List<String>> dumpSchema(String url, String user, String password, Set<String> schemas)
            throws SQLException {
        String in = schemas.stream().map(s -> "'" + s + "'").reduce((a, b) -> a + "," + b).orElseThrow();
        var q = new LinkedHashMap<String, String>();
        q.put("relations", """
                SELECT n.nspname, c.relname, c.relkind, c.relpersistence, c.relrowsecurity, c.relforcerowsecurity,
                       c.relispartition, pg_get_partkeydef(c.oid)
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN (%s) AND c.relkind IN ('r','p','v','m','f','S')""".formatted(in));
        q.put("columns", """
                SELECT table_schema, table_name, ordinal_position, column_name, data_type, udt_schema, udt_name,
                       is_nullable, column_default, character_maximum_length, numeric_precision, numeric_scale,
                       datetime_precision, is_identity, identity_generation, is_generated, generation_expression,
                       collation_name
                FROM information_schema.columns WHERE table_schema IN (%s)""".formatted(in));
        q.put("constraints", """
                SELECT n.nspname, c.conname, c.contype, c.conrelid::regclass::text, c.contypid::regtype::text,
                       pg_get_constraintdef(c.oid), c.condeferrable, c.condeferred, c.convalidated
                FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                WHERE n.nspname IN (%s)""".formatted(in));
        q.put("indexes", "SELECT schemaname, tablename, indexname, indexdef FROM pg_indexes WHERE schemaname IN (%s)"
                .formatted(in));
        q.put("triggers", """
                SELECT n.nspname, c.relname, t.tgname, t.tgenabled, pg_get_triggerdef(t.oid)
                FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE NOT t.tgisinternal AND n.nspname IN (%s)""".formatted(in));
        q.put("policies", """
                SELECT schemaname, tablename, policyname, permissive, roles::text, cmd, qual, with_check
                FROM pg_policies WHERE schemaname IN (%s)""".formatted(in));
        q.put("functions", """
                SELECT n.nspname, p.proname, pg_get_function_identity_arguments(p.oid), pg_get_function_result(p.oid),
                       p.prokind, p.provolatile, p.prosecdef, md5(p.prosrc), p.proconfig::text
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname IN (%s)""".formatted(in));
        q.put("views", """
                SELECT n.nspname, c.relname, pg_get_viewdef(c.oid, true)
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname IN (%s) AND c.relkind IN ('v','m')""".formatted(in));
        q.put("sequences", """
                SELECT sequence_schema, sequence_name, data_type, start_value, minimum_value, maximum_value, increment,
                       cycle_option FROM information_schema.sequences WHERE sequence_schema IN (%s)""".formatted(in));
        q.put("inherits", """
                SELECT pc.relnamespace::regnamespace::text, pc.relname, cc.relname, pg_get_expr(cc.relpartbound, cc.oid)
                FROM pg_inherits i JOIN pg_class pc ON pc.oid = i.inhparent JOIN pg_class cc ON cc.oid = i.inhrelid
                WHERE pc.relnamespace::regnamespace::text IN (%s)""".formatted(in));
        q.put("types", """
                SELECT n.nspname, t.typname, t.typtype,
                       (SELECT string_agg(e.enumlabel, ',' ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid = t.oid)
                FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
                WHERE n.nspname IN (%s) AND t.typtype IN ('e','d','c') AND NOT EXISTS
                      (SELECT 1 FROM pg_class c WHERE c.oid = t.typrelid AND c.relkind <> 'c')""".formatted(in));
        q.put("grants", """
                SELECT grantee, table_schema, table_name, privilege_type, is_grantable
                FROM information_schema.role_table_grants WHERE table_schema IN (%s)""".formatted(in));
        q.put("history", """
                SELECT installed_rank, version, description, type, script, checksum, success
                FROM core.flyway_schema_history ORDER BY installed_rank""");

        var dump = new TreeMap<String, List<String>>();
        try (Connection c = DriverManager.getConnection(url, user, password)) {
            for (var e : q.entrySet()) {
                dump.put(e.getKey(), rows(c, e.getValue(), !"history".equals(e.getKey())));
            }
            // 행 수: 시드가 새 설치와 같은지(값은 uuid · 시각이 달라 비교하지 않음). 이력 표 제외
            var counts = new ArrayList<String>();
            for (String rel : rows(c, """
                    SELECT quote_ident(table_schema) || '.' || quote_ident(table_name) FROM information_schema.tables
                    WHERE table_schema IN (%s) AND table_type = 'BASE TABLE' AND table_name <> 'flyway_schema_history'
                    """.formatted(in), true)) {
                counts.add(rel + " = " + rows(c, "SELECT count(*) FROM ONLY " + rel, false).getFirst());
            }
            dump.put("rowCounts", counts);
        }
        return dump;
    }

    private static List<String> rows(Connection c, String sql, boolean sort) throws SQLException {
        var out = new ArrayList<String>();
        try (var st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                var sb = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) {
                        sb.append(" | ");
                    }
                    sb.append(rs.getString(i));
                }
                out.add(sb.toString());
            }
        }
        if (sort) {
            out.sort(null);
        }
        return out;
    }
}
