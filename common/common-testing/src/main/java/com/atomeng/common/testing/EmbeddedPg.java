package com.atomeng.common.testing;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 테스트 JVM 당 1개의 내장 PostgreSQL 16 인스턴스 (Docker/Testcontainers 대체).
 *
 * <p>제약: zonky 바이너리에는 PostGIS·pgvector가 없다. 그래서 V0001__core_schema.sql 은 확장 없이 동작하도록
 * 작성했고, PostGIS 의존 DDL은 {@code db/migration-ext} 위치(프로필 {@code postgis})로 분리했다.
 */
public final class EmbeddedPg {

    private static volatile EmbeddedPostgres instance;
    private static final AtomicInteger DB_SEQ = new AtomicInteger();

    private EmbeddedPg() {}

    public static synchronized EmbeddedPostgres get() {
        if (instance == null) {
            try {
                instance = EmbeddedPostgres.builder()
                        .setPGStartupWait(java.time.Duration.ofSeconds(90))   // 느린 PC·병렬 빌드 대비
                        .setServerConfig("timezone", "UTC")
                        .setServerConfig("max_connections", "200")
                        .setServerConfig("lc_messages", "C")   // 오류 메시지 영어 고정(OS 로캘 무관)
                        .start();
            } catch (IOException e) {
                throw new UncheckedIOException("embedded PostgreSQL 기동 실패", e);
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    instance.close();
                } catch (IOException ignored) {
                    // 종료 중
                }
            }));
        }
        return instance;
    }

    /** 테스트 격리를 위해 새 빈 데이터베이스를 만들고 JDBC URL을 돌려준다. */
    public static String newDatabaseUrl(String prefix) {
        String name = prefix + "_" + DB_SEQ.incrementAndGet() + "_" + ProcessHandle.current().pid();
        var pg = get();
        try (Connection c = DriverManager.getConnection(pg.getJdbcUrl("postgres", "postgres"));
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE " + name);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return pg.getJdbcUrl("postgres", name);
    }

    public static String user() {
        return "postgres";
    }

    public static String password() {
        return "postgres";
    }
}
