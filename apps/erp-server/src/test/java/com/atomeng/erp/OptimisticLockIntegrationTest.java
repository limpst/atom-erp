package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.erp.support.IntegrationTestBase;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** 설계 9.1 동시성(ETag/If-Match) — TC-CORE-003 */
class OptimisticLockIntegrationTest extends IntegrationTestBase {

    @Autowired
    AuditLog auditLog;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchClient(
            UUID id, UUID actor, String ifMatch, String json) {
        var b = as(patch("/api/v1/core/clients/" + id), actor, "SALES")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return ifMatch == null ? b : b.header("If-Match", ifMatch);
    }

    @Test
    @DisplayName("TC-CORE-003: 두 사용자가 같은 고객을 같은 버전(v0)으로 수정 → 한 명 200, 다른 한 명 412 CORE-412-001")
    void tcCore003_secondWriterGets412() throws Exception {
        UUID userA = actor();
        UUID userB = actor();
        UUID id = createClient(userA, "합성고객-TC003-" + UUID.randomUUID());

        mvc.perform(as(get("/api/v1/core/clients/" + id), userA, "SALES"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"v0\""));

        mvc.perform(patchClient(id, userA, "\"v0\"", "{\"address\":\"서울시 합성구 1\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v1\""))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patchClient(id, userB, "\"v0\"", "{\"address\":\"서울시 합성구 2\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("CORE-412-001"));

        assertThat(jdbc.queryForObject("SELECT address FROM core.client WHERE id = ?", String.class, id))
                .isEqualTo("서울시 합성구 1");
        assertThat(jdbc.queryForObject("SELECT updated_by FROM core.client WHERE id = ?", UUID.class, id))
                .isEqualTo(userA);
    }

    @Test
    @DisplayName("TC-CORE-003(동시): 같은 v0 PATCH 2건을 동시에 → 정확히 1건 200, 1건 412 (@Version 경합 포함)")
    void tcCore003_concurrentWriters() throws Exception {
        UUID id = createClient(actor(), "합성고객-TC003c-" + UUID.randomUUID());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    start.await();
                    return mvc.perform(patchClient(id, actor(), "\"v0\"", "{\"note\":\"동시수정-" + n + "\"}"))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (var f : results) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(200, 412);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT version FROM core.client WHERE id = ?", Long.class, id)).isEqualTo(1L);
    }

    @Test
    @DisplayName("설계 9.1: If-Match 없이 수정 → 428 CORE-428-002")
    void ifMatchRequired() throws Exception {
        UUID id = createClient(actor(), "합성고객-ifm-" + UUID.randomUUID());
        mvc.perform(patchClient(id, actor(), null, "{\"note\":\"x\"}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("CORE-428-002"));
    }

    @Test
    @DisplayName("NFR-CORE-07: 생성·수정이 감사 로그(before/after)에 남고 해시 체인이 유지된다")
    void auditTrailForWrites() throws Exception {
        UUID actor = actor();
        UUID id = createClient(actor, "합성고객-audit-" + UUID.randomUUID());
        mvc.perform(patchClient(id, actor, "\"v0\"", "{\"address\":\"부산시 합성구 3\"}")).andExpect(status().isOk());
        var rows = jdbc.queryForList("""
                SELECT action, actor_id, before_json->>'address' AS b, after_json->>'address' AS a
                  FROM core.audit_log WHERE target_id = ? ORDER BY id""", id);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("action")).isEqualTo("CREATE");
        assertThat(rows.get(1).get("action")).isEqualTo("UPDATE");
        assertThat(rows.get(1).get("a")).isEqualTo("부산시 합성구 3");
        assertThat(rows.get(1).get("actor_id")).isEqualTo(actor);
        assertThat(auditLog.verifyChain()).isEmpty();
    }
}
