package com.atomeng.devserver;

import com.atomeng.common.testing.EmbeddedPg;
import com.atomeng.erp.ErpServerApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 로컬 개발 · 웹 E2E 용 백엔드 — 내장 PostgreSQL 16(zonky, Docker 불필요) + local-noauth(헤더 인증) + 합성 시드.
 *
 * <p>실행: {@code ./gradlew :apps:erp-server:devServer} (기본 127.0.0.1:8081). 운영 금지 — 테스트 소스셋에만 있고
 * 패키지가 com.atomeng.erp 밖이라 통합 테스트 컴포넌트 스캔에도 잡히지 않는다. DB 는 실행마다 새로 만든다.
 *
 * <p>환경변수: ATOM_DEV_PORT(기본 8081) · ATOM_DEV_SEED(기본 true).
 */
public final class DevServer {

    private DevServer() {}

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("ATOM_DEV_PORT", "8081"));
        boolean seed = !"false".equalsIgnoreCase(System.getenv().getOrDefault("ATOM_DEV_SEED", "true"));
        String dbUrl = EmbeddedPg.newDatabaseUrl("erp_dev");
        Path evidence = Path.of("build", "dev-evidence").toAbsolutePath();
        Files.createDirectories(evidence);

        Map<String, Object> props = new HashMap<>();
        props.put("spring.profiles.active", "local-noauth");
        props.put("server.address", "127.0.0.1");
        props.put("server.port", port);
        props.put("spring.datasource.url", dbUrl);
        props.put("spring.datasource.username", EmbeddedPg.user());
        props.put("spring.datasource.password", EmbeddedPg.password());
        props.put("atom.evidence.local-root", evidence.toString());
        props.put("atom.sal.collect.enabled", "false");
        // 공공 API 는 호출하지 않는다(키 없음 → 수동 입력 경로)
        props.put("atom.fac.external.juso.base-url", "http://127.0.0.1:9/juso");
        props.put("atom.fac.external.building-register.base-url", "http://127.0.0.1:9/hub");

        // 시스템 속성은 application.yml 보다 우선한다(기본 속성은 yml 에 덮인다)
        props.forEach((k, val) -> System.setProperty(k, String.valueOf(val)));
        SpringApplication app = new SpringApplication(ErpServerApplication.class);
        ConfigurableApplicationContext ctx = app.run(args);

        if (seed) {
            try {
                new DevSeed("http://127.0.0.1:" + port)
                        .run();
            } catch (Exception e) {
                System.err.println("[dev-seed] 실패: " + e);
                e.printStackTrace();
            }
        }
        System.out.println("[dev-server] READY http://127.0.0.1:" + port + " (local-noauth, embedded PostgreSQL)");
    }
}
