package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CST 청구 · 수금 · 통장 대사 통합 시험 (설계 08 Step 12·13, docs/CST_BILLING_COLLECTION_DESIGN.md).
 * 청구 발행 → 입금 등록(멱등) → 짝 후보 점수 → 제안 → 두 사람 규칙 → 확정 → 미수 · aging → 역분개 → 취소. 전부 합성값.
 */
class CstBillingIntegrationTest extends IntegrationTestBase {

    private UUID project(UUID pm, UUID client) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 청구 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    @Test
    @DisplayName("청구 → 입금 → 제안 → 확정(제안자 ≠ 확정자) → 미수 0 → 역분개 → 미수 복귀 → 취소")
    void billingHappyPathAndFourEyes() throws Exception {
        UUID fin = actor();
        UUID admin = actor();
        UUID pm = actor();
        String clientName = "합성빌링건설-" + UUID.randomUUID().toString().substring(0, 8);
        UUID client = createClient(actor(), clientName);
        UUID project = project(pm, client);

        // 1) 청구 발행: 재무만
        String issue = """
                {"projectId":"%s","clientId":"%s","issueDate":"2026-09-01","supplyKrw":10000000,"vatKrw":1000000,
                 "memo":"합성 1차 기성"}""".formatted(project, client);
        mvc.perform(as(postJson("/api/v1/cst/invoices", issue), actor(), "ENG")).andExpect(status().isForbidden());
        JsonNode inv = json(mvc.perform(as(postJson("/api/v1/cst/invoices", issue), fin, "FIN"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("ISSUED"))
                .andExpect(jsonPath("$.totalKrw").value(11000000)).andReturn());
        String invoiceId = inv.get("id").asText();
        assertThat(inv.get("invoiceNo").asText()).startsWith("INV-2026-");

        // 2) 입금 등록: 같은 명세서 줄은 한 번만(두 번째는 200 + 같은 id)
        String key = "syn-file:" + UUID.randomUUID();
        String rcp = """
                {"bankAccount":"SYN-운영","valueDate":"2026-09-20","amountKrw":11000000,"payerText":"%s","statementKey":"%s"}"""
                .formatted(clientName, key);
        JsonNode r1 = json(mvc.perform(as(postJson("/api/v1/cst/receipts", rcp), fin, "FIN"))
                .andExpect(status().isCreated()).andReturn());
        JsonNode r2 = json(mvc.perform(as(postJson("/api/v1/cst/receipts", rcp), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(r2.get("id").asText()).isEqualTo(r1.get("id").asText());
        String receiptId = r1.get("id").asText();

        // 3) 짝 후보: 금액 50 + 이름 30 + 날짜 20 = 100
        JsonNode cands = json(mvc.perform(as(get("/api/v1/cst/allocations/candidates").param("asOf", "2026-10-31")
                .param("minScore", "90"), fin, "FIN")).andExpect(status().isOk()).andReturn());
        boolean found = false;
        for (JsonNode c : cands) {
            if (c.get("invoiceId").asText().equals(invoiceId) && c.get("receiptId").asText().equals(receiptId)) {
                assertThat(c.get("score").asInt()).isEqualTo(100);
                assertThat(c.get("autoCandidate").asBoolean()).isTrue();
                found = true;
            }
        }
        assertThat(found).as("후보 목록에 합성 청구 · 입금 짝이 있어야 한다").isTrue();

        // 4) 미수(기준일 2026-10-31): 60일 → B 구간, 11,000,000원
        JsonNode open = json(mvc.perform(as(get("/api/v1/cst/receivables/open-items").param("asOf", "2026-10-31"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        JsonNode mine = null;
        for (JsonNode o : open) {
            if (o.get("invoiceId").asText().equals(invoiceId)) {
                mine = o;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("bucket").asText()).isEqualTo("B_31_60");
        assertThat(mine.get("openKrw").asLong()).isEqualTo(11_000_000L);

        // 5) 제안(재무) → 재무는 확정 권한이 없고, 같은 사람이 ADMIN 역할로 와도 두 사람 규칙으로 거부
        String propose = "{\"invoiceId\":\"%s\",\"receiptId\":\"%s\",\"amountKrw\":11000000}".formatted(invoiceId, receiptId);
        JsonNode prop = json(mvc.perform(as(postJson("/api/v1/cst/allocations:propose", propose), fin, "FIN"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.matchScore").value(100)).andReturn());
        String proposalId = prop.get("id").asText();
        mvc.perform(as(postJson("/api/v1/cst/allocations:propose", propose), fin, "FIN")).andExpect(status().isConflict());
        mvc.perform(as(postJson("/api/v1/cst/allocation-proposals/" + proposalId + ":confirm", "{}"), fin, "FIN"))
                .andExpect(status().isForbidden());
        mvc.perform(as(postJson("/api/v1/cst/allocation-proposals/" + proposalId + ":confirm", "{}"), fin, "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CST-403-010"));

        // 6) 확정(다른 사람, ADMIN) → 미수 목록에서 빠짐
        JsonNode alloc = json(mvc.perform(as(postJson("/api/v1/cst/allocation-proposals/" + proposalId + ":confirm", "{}"),
                admin, "ADMIN")).andExpect(status().isOk()).andReturn());
        String allocationId = alloc.get("id").asText();
        mvc.perform(as(postJson("/api/v1/cst/allocation-proposals/" + proposalId + ":confirm", "{}"), admin, "ADMIN"))
                .andExpect(status().isConflict());
        JsonNode after = json(mvc.perform(as(get("/api/v1/cst/receivables/open-items").param("asOf", "2026-10-31"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        for (JsonNode o : after) {
            assertThat(o.get("invoiceId").asText()).isNotEqualTo(invoiceId);
        }
        // 받은 돈이 남아 있으면 취소 불가
        mvc.perform(as(postJson("/api/v1/cst/invoices/" + invoiceId + ":void", "{\"reason\":\"합성 취소 시도\"}"), fin, "FIN"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-021"));

        // 7) 역분개(ADMIN) → 미수 복귀, 두 번은 안 됨 → 취소 가능
        mvc.perform(as(postJson("/api/v1/cst/allocations/" + allocationId + ":reverse", "{\"reason\":\"합성 오배분\"}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountKrw").value(-11000000));
        mvc.perform(as(postJson("/api/v1/cst/allocations/" + allocationId + ":reverse", "{\"reason\":\"다시\"}"), admin, "ADMIN"))
                .andExpect(status().isConflict());
        mvc.perform(as(postJson("/api/v1/cst/invoices/" + invoiceId + ":void", "{\"reason\":\"합성 취소\"}"), fin, "FIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VOID"));

        // 8) 프로젝트 청구 목록: 책임기술자가 아닌 PM 은 볼 수 없다(범위 PROJECT), 재무는 본다
        mvc.perform(as(get("/api/v1/cst/projects/" + project + "/invoices"), actor(), "PM")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/cst/projects/" + project + "/invoices"), fin, "FIN")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].invoiceNo").value(inv.get("invoiceNo").asText()));

        // 9) 이벤트(Outbox): issued 1 · allocated 2(확정 + 역분개) · settled 1 · reopened 1 · voided 1 — 같은 트랜잭션에 기록
        for (String[] e : new String[][] {{"cst.invoice.issued", "1"}, {"cst.receipt.allocated", "2"}, {"cst.invoice.settled", "1"},
                {"cst.invoice.reopened", "1"}, {"cst.invoice.voided", "1"}}) {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?::uuid",
                    Integer.class, e[0], invoiceId);
            assertThat(n).as(e[0]).isEqualTo(Integer.parseInt(e[1]));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = 'cst.invoice.issued' "
                + "AND payload::text LIKE '%payer%'", Integer.class)).as("payload 에 입금자 표기 없음").isZero();
    }

    @Test
    @DisplayName("독촉: 기준일로 L0·L1·L2 초안을 한 번씩만 만들고, 보류 청구는 건너뛰고, L2 는 다른 사람 결재 뒤에만 발송 기록, 전액 입금되면 취소")
    void dunningRun() throws Exception {
        UUID fin = actor();
        UUID admin = actor();
        UUID pm = actor();
        UUID client = createClient(actor(), "합성독촉건설-" + UUID.randomUUID().toString().substring(0, 8));
        UUID project = project(pm, client);
        String issue = """
                {"projectId":"%s","clientId":"%s","issueDate":"2026-06-01","supplyKrw":1000000,"vatKrw":100000}""".formatted(project, client);
        JsonNode inv = json(mvc.perform(as(postJson("/api/v1/cst/invoices", issue), fin, "FIN")).andExpect(status().isCreated()).andReturn());
        String invoiceId = inv.get("id").asText();
        String held = json(mvc.perform(as(postJson("/api/v1/cst/invoices", issue.replace("2026-06-01", "2026-05-01")), fin, "FIN"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(as(postJson("/api/v1/cst/dunning/invoices/" + held + ":hold", "{\"reason\":\"합성 하자 협의 중\"}"), fin, "FIN"))
                .andExpect(status().isOk());

        // 기준일 2026-10-31: 152일 → L0 · L1 · L2 세 초안. 두 번 돌려도 새로 생기지 않는다(멱등)
        mvc.perform(as(postJson("/api/v1/cst/dunning:run", "{\"asOf\":\"2026-10-31\"}"), actor(), "ENG")).andExpect(status().isForbidden());
        JsonNode r1 = json(mvc.perform(as(postJson("/api/v1/cst/dunning:run", "{\"asOf\":\"2026-10-31\"}"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(r1.get("skippedOnHold").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode r2 = json(mvc.perform(as(postJson("/api/v1/cst/dunning:run", "{\"asOf\":\"2026-10-31\"}"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(r2.get("created").asInt()).isZero();
        Integer mine = jdbc.queryForObject("SELECT count(*) FROM cst.dunning_notice WHERE invoice_id = ?::uuid", Integer.class, invoiceId);
        assertThat(mine).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.dunning_notice WHERE invoice_id = ?::uuid", Integer.class, held))
                .as("보류 청구는 초안 없음").isZero();
        String l2 = jdbc.queryForObject("SELECT id::text FROM cst.dunning_notice WHERE invoice_id = ?::uuid AND level = 'L2'", String.class, invoiceId);

        // 발송 기록은 결재(APPROVED) 뒤에만. 결재는 초안 작성자(FIN=run 실행자)가 아닌 EXEC · ADMIN
        mvc.perform(as(postJson("/api/v1/cst/dunning/notices/" + l2 + ":sent", "{\"channel\":\"EMAIL\"}"), fin, "FIN"))
                .andExpect(status().isConflict());
        mvc.perform(as(postJson("/api/v1/cst/dunning/notices/" + l2 + ":approve", "{}"), fin, "ADMIN"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CST-403-011"));
        mvc.perform(as(postJson("/api/v1/cst/dunning/notices/" + l2 + ":approve", "{}"), admin, "ADMIN")).andExpect(status().isOk());
        mvc.perform(as(postJson("/api/v1/cst/dunning/notices/" + l2 + ":sent", "{\"channel\":\"EMAIL\"}"), fin, "FIN"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        // 목록(감사 조회 가능)
        mvc.perform(as(get("/api/v1/cst/dunning/notices").param("status", "SENT"), actor(), "AUDITOR")).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.invoiceId == '" + invoiceId + "')].level").value(org.hamcrest.Matchers.hasItem("L2")));

        // 전액 입금 → 다음 run 에서 남은 초안(L0 · L1 DRAFT)은 CANCELLED, SENT 는 그대로
        String key = "syn-dun:" + UUID.randomUUID();
        String rcp = """
                {"bankAccount":"SYN-운영","valueDate":"2026-10-20","amountKrw":1100000,"payerText":"합성독촉건설","statementKey":"%s"}""".formatted(key);
        String receiptId = json(mvc.perform(as(postJson("/api/v1/cst/receipts", rcp), fin, "FIN")).andExpect(status().isCreated()).andReturn()).get("id").asText();
        String proposalId = json(mvc.perform(as(postJson("/api/v1/cst/allocations:propose",
                "{\"invoiceId\":\"%s\",\"receiptId\":\"%s\",\"amountKrw\":1100000}".formatted(invoiceId, receiptId)), fin, "FIN"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText();
        mvc.perform(as(postJson("/api/v1/cst/allocation-proposals/" + proposalId + ":confirm", "{}"), admin, "ADMIN")).andExpect(status().isOk());
        JsonNode r3 = json(mvc.perform(as(postJson("/api/v1/cst/dunning:run", "{\"asOf\":\"2026-10-31\"}"), fin, "FIN"))
                .andExpect(status().isOk()).andReturn());
        assertThat(r3.get("cancelled").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.dunning_notice WHERE invoice_id = ?::uuid AND status = 'CANCELLED'",
                Integer.class, invoiceId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM cst.dunning_notice WHERE id = ?::uuid", String.class, l2)).isEqualTo("SENT");
    }
}
