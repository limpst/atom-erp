package com.atomeng.erp.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.identity.ProductionGuards;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** 보안 검토 CMN-04(배포 절차) · CMN-13 — 운영 기동기가 prod 프로필을 고정하고, 환경 양식에 비밀값 · 프로필이 없는지 */
class DeployLauncherPolicyTest {

    static Path deploy() {
        Path p = Path.of("").toAbsolutePath();
        while (p != null && !Files.isDirectory(p.resolve("deploy/prod"))) {
            p = p.getParent();
        }
        assertThat(p).as("저장소 루트(deploy/prod)").isNotNull();
        return p.resolve("deploy/prod");
    }

    static String read(String name) throws Exception {
        return Files.readString(deploy().resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("CMN-04: run-erp.sh · run-erp.ps1 은 SPRING_PROFILES_ACTIVE=prod · 배포 표식 · --spring.profiles.active=prod 를 고정하고 프로필 인자를 거부")
    void launchersPinProdProfile() throws Exception {
        String sh = read("run-erp.sh");
        assertThat(sh).contains("export SPRING_PROFILES_ACTIVE=prod").contains("export ATOM_DEPLOY_ENVIRONMENT=prod")
                .contains("--spring.profiles.active=prod").contains("*spring.profiles*");
        // 프로필 고정은 환경 파일을 읽은 뒤(덮어쓰기)여야 한다
        assertThat(sh.indexOf("export SPRING_PROFILES_ACTIVE=prod")).isGreaterThan(sh.indexOf(". \"$ENV_FILE\""));
        String ps = read("run-erp.ps1");
        assertThat(ps).contains("$env:SPRING_PROFILES_ACTIVE = 'prod'").contains("$env:ATOM_DEPLOY_ENVIRONMENT = 'prod'")
                .contains("--spring.profiles.active=prod").contains("*spring.profiles*");
        assertThat(ps.indexOf("$env:SPRING_PROFILES_ACTIVE = 'prod'")).isGreaterThan(ps.indexOf("SetEnvironmentVariable"));
    }

    @Test
    @DisplayName("CMN-04 · CMN-13: 환경 양식은 프로필을 정하지 않고 비밀값(비밀번호 · 키 · 시크릿)이 모두 비어 있다")
    void envTemplateHasNoProfileOrSecrets() throws Exception {
        List<String> lines = read("erp.env.example").lines().map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#")).toList();
        assertThat(lines).noneMatch(l -> l.startsWith("SPRING_PROFILES_ACTIVE"));
        Pattern secret = Pattern.compile("^[A-Z0-9_]*(PASSWORD|SECRET|ACCESS_KEY|SERVICE_KEY)[A-Z0-9_]*=(.*)$");
        long secrets = lines.stream().map(secret::matcher).filter(java.util.regex.Matcher::matches)
                .peek(m -> assertThat(m.group(2)).as(m.group(0)).isEmpty()).count();
        assertThat(secrets).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("CMN-04: 배포 표식(ATOM_DEPLOY_ENVIRONMENT=prod) + prod 아닌 프로필 → 기동 거부 / CMN-13: application.yml prod 문서는 DB 비밀번호 기본값이 없다")
    void guardAndProdProfileDocument() throws Exception {
        MockEnvironment overridden = new MockEnvironment();
        overridden.setActiveProfiles("local-noauth");
        overridden.setProperty("atom.deploy.environment", "prod");
        assertThatThrownBy(() -> ProductionGuards.checkDeployment(overridden)).hasMessageContaining("CMN-04");

        List<PropertySource<?>> docs = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        assertThat(String.valueOf(docs.get(0).getProperty("spring.datasource.password"))).isEqualTo("${PG_APP_PASSWORD:atom_app_local}");
        PropertySource<?> prod = docs.stream()
                .filter(d -> String.valueOf(d.getProperty("spring.config.activate.on-profile")).contains("prod"))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(prod.getProperty("spring.datasource.password"))).isEqualTo("${PG_APP_PASSWORD:}");
        assertThat(String.valueOf(prod.getProperty("atom.evidence.store"))).isEqualTo("s3");
        MockEnvironment prodEnv = new MockEnvironment();
        prodEnv.setActiveProfiles("prod");
        prodEnv.setProperty("spring.datasource.password", "");
        assertThatThrownBy(() -> ProductionGuards.checkDatasource(prodEnv)).hasMessageContaining("CMN-13");
    }
}
