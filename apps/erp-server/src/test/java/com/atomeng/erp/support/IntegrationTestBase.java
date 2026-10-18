package com.atomeng.erp.support;

import com.atomeng.common.testing.EmbeddedPg;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 통합 테스트 공통 기반 — 내장 PostgreSQL 16 + Flyway V1 + 전체 Spring 컨텍스트(MockMvc).
 * 인증은 atom.security.enabled=false (헤더 스텁), Relay 스케줄러는 끄고 테스트가 직접 pollOnce() 호출.
 * 모든 데이터는 합성값.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestClockConfig.class, TestBeans.class})
@TestPropertySource(properties = {
        "atom.security.enabled=false",
        "atom.outbox.relay.enabled=false",
        "atom.fac.due-scan.enabled=false",
        "atom.hrq.expiry.enabled=false",
        "atom.eqp.calibration-scan.enabled=false",
        "atom.sal.collect.enabled=false",
        "spring.jpa.open-in-view=false"
})
public abstract class IntegrationTestBase {

    private static final String DB_URL = EmbeddedPg.newDatabaseUrl("erp_it");
    /** 로컬 파일시스템 객체 저장소 루트(MinIO 대체) — 테스트 JVM 마다 새 임시 폴더 */
    protected static final java.nio.file.Path EVIDENCE_ROOT;

    static {
        try {
            EVIDENCE_ROOT = java.nio.file.Files.createTempDirectory("atom-evidence-it");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 공공 API(도로명주소·건축HUB) 대역 — 합성 응답만. 모든 통합 테스트가 공유(컨텍스트 1개 유지) */
    protected static final com.github.tomakehurst.wiremock.WireMockServer PUBLIC_API =
            new com.github.tomakehurst.wiremock.WireMockServer(
                    com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort());

    static {
        PUBLIC_API.start();
        Runtime.getRuntime().addShutdownHook(new Thread(PUBLIC_API::stop));
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry r) {
        r.add("atom.fac.external.juso.base-url", () -> PUBLIC_API.baseUrl() + "/juso");
        r.add("atom.fac.external.juso.confm-key", () -> "synthetic-test-key");
        r.add("atom.fac.external.building-register.base-url", () -> PUBLIC_API.baseUrl() + "/hub");
        r.add("atom.fac.external.building-register.service-key", () -> "synthetic-test-key");
        r.add("atom.fac.external.building-register.read-timeout", () -> "400ms");
        r.add("atom.fac.external.building-register.initial-backoff", () -> "20ms");
        r.add("atom.sal.g2b.base-url", () -> PUBLIC_API.baseUrl() + "/g2b");
        r.add("atom.sal.g2b.service-key", () -> "synthetic-test-key");
        r.add("atom.sal.g2b.read-timeout", () -> "400ms");
        r.add("atom.sal.g2b.initial-backoff", () -> "20ms");
        r.add("spring.datasource.url", () -> DB_URL);
        r.add("spring.datasource.username", EmbeddedPg::user);
        r.add("spring.datasource.password", EmbeddedPg::password);
        r.add("atom.evidence.local-root", EVIDENCE_ROOT::toString);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected MutableClock clock;

    /** 합성 actor */
    protected static UUID actor() {
        return UUID.randomUUID();
    }

    protected static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder b, UUID actor, String roles) {
        return b.header("X-Actor-Id", actor.toString()).header("X-Roles", roles);
    }

    protected static MockHttpServletRequestBuilder postJson(String url, String json) {
        return MockMvcRequestBuilders.post(url).contentType(MediaType.APPLICATION_JSON).content(json);
    }

    protected JsonNode json(MvcResult r) throws Exception {
        return objectMapper.readTree(r.getResponse().getContentAsByteArray());
    }

    /** SALES 역할로 합성 고객 1건 생성 → id */
    protected UUID createClient(UUID actor, String name) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/core/clients",
                "{\"name\":\"" + name + "\",\"clientType\":\"PRIVATE\"}"), actor, "SALES")).andReturn();
        if (r.getResponse().getStatus() != 201) {
            throw new IllegalStateException("client create failed: " + r.getResponse().getContentAsString());
        }
        return UUID.fromString(json(r).get("id").asText());
    }
}
