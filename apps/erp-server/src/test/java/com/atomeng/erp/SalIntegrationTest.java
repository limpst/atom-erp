package com.atomeng.erp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.DomainEvent;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.sal.adapter.out.g2b.G2bApiClient;
import com.atomeng.erp.sal.domain.DiscountPolicy;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.TestBeans.FakeAssignmentFeasibility;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 01 SAL 통합 시험 (01-IMPL Step 1·4–6·8–10·12 일부) — 내장 PostgreSQL + 전체 컨텍스트, 나라장터는 WireMock(합성 응답).
 * 모든 데이터는 합성값. 규칙 · 기준 값은 ⚠️ 예시.
 */
class SalIntegrationTest extends IntegrationTestBase {

    static final String G2B_OP = "/g2b/getBidPblancListInfoServc";

    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxPublisher outbox;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    DiscountPolicy discountPolicy;
    @Autowired
    FakeAssignmentFeasibility feasibility;

    UUID sales;
    UUID lead;
    UUID exec;
    UUID admin;

    @BeforeEach
    void setUp() {
        clock.reset();
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
        sales = employee("영업");
        lead = employee("영업팀장");
        exec = employee("임원");
        admin = employee("경영지원");
        jdbc.update("INSERT INTO sal.sales_group_member(employee_id, group_code) VALUES (?, 'SALES_LEAD')", lead);
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ------------------------------------------------------------------ helpers

    UUID employee(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, ?, DATE '2024-01-01', ?, ?)", id, "S-" + id.toString().substring(0, 12), "합성" + name,
                id, id);
        return id;
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    ResultActions post(String url, Object body, UUID actor, String roles) throws Exception {
        return mvc.perform(as(postJson(url, objectMapper.writeValueAsString(body)), actor, roles));
    }

    ResultActions postIfMatch(String url, Object body, long version, UUID actor, String roles) throws Exception {
        return mvc.perform(as(postJson(url, objectMapper.writeValueAsString(body))
                .header("If-Match", IfMatch.etagOf(version)), actor, roles));
    }

    JsonNode get(String url, UUID actor, String roles) throws Exception {
        return json(mvc.perform(as(MockMvcRequestBuilders.get(url), actor, roles)).andExpect(status().isOk()).andReturn());
    }

    /** G-02 형 시설물(18,420㎡ · 1994 준공) */
    static Map<String, Object> facility() {
        return map("facilityClass", "BUILDING", "grossAreaM2", 18420, "structure", "RC", "basementFloors", 1,
                "floors", 8, "completionYear", 1994, "inUse", false);
    }

    static Map<String, Object> feeRequest(String asOf, Object discount) {
        return map("asOfDate", asOf, "serviceType", "PRECISE_DIAG", "facility", facility(),
                "expenses", List.of(map("item", "TRAVEL", "quantity", 12, "unitPrice", 150000)),
                "discountRate", discount);
    }

    /** SALES 담당 기회 → QUALIFYING → PROPOSING */
    UUID proposingOpportunity(UUID client, String serviceType) throws Exception {
        JsonNode o = json(post("/api/v1/sal/opportunities", map("source", "INQUIRY", "clientId", client, "facilityId",
                UUID.randomUUID(), "serviceType", serviceType, "title", "합성 정밀안전진단 문의", "ownerId", sales),
                sales, "SALES").andExpect(status().isCreated()).andReturn());
        UUID id = UUID.fromString(o.get("id").asText());
        long v = o.get("version").asLong();
        v = json(postIfMatch("/api/v1/sal/opportunities/" + id + ":move", map("toStage", "QUALIFYING"), v, sales,
                "SALES").andExpect(status().isOk()).andReturn()).get("version").asLong();
        postIfMatch("/api/v1/sal/opportunities/" + id + ":move", map("toStage", "PROPOSING"), v, sales, "SALES")
                .andExpect(status().isOk()).andExpect(jsonPath("$.stage").value("PROPOSING"));
        return id;
    }

    JsonNode createEstimate(UUID opp, String discount, UUID actor, String roles) throws Exception {
        return json(post("/api/v1/sal/opportunities/" + opp + "/estimates", feeRequest("2026-10-15",
                new BigDecimal(discount)), actor, roles).andExpect(status().isCreated()).andReturn());
    }

    JsonNode issue(UUID estimateId, UUID actor, String roles) throws Exception {
        return json(post("/api/v1/sal/estimates/" + estimateId + ":issue", Map.of(), actor, roles)
                .andExpect(status().isOk()).andReturn());
    }

    List<JsonNode> events(String type) throws Exception {
        List<String> payloads = jdbc.queryForList("SELECT payload::text FROM core.outbox_event WHERE event_type = ? "
                + "ORDER BY occurred_at, id", String.class, type);
        List<JsonNode> out = new java.util.ArrayList<>();
        for (String p : payloads) {
            out.add(objectMapper.readTree(p));
        }
        return out;
    }

    void publish(DomainEvent e) {
        tx.executeWithoutResult(s -> outbox.publish(e));
    }

    /** 테스트 전용 PTL 이벤트(스텁 계약과 같은 필드) */
    record PtlRequest(UUID serviceRequestId, UUID clientId, UUID facilityId, String requestType, String message)
            implements DomainEvent {
        @Override public String eventType() { return "ptl.service-request.submitted"; }
        @Override public String aggregateType() { return "ServiceRequest"; }
        @Override public UUID aggregateId() { return serviceRequestId; }
    }

    // ------------------------------------------------------------------ 규칙 · 설정

    @Test
    @DisplayName("시드 규칙 본문 = 골든 픽스처(fee-rule-2025/2026 · qual) JSON 동일, 할인 한도 설정 바인딩(SALES 0.05 · SALES_LEAD 0.10 · EXEC 0.49)")
    void seedsAndConfig() throws Exception {
        java.nio.file.Path rules = java.nio.file.Path.of(System.getProperty("atom.repo.root"),
                "modules/sal/src/test/resources/golden/rules");
        for (var e : Map.of("2025.1", "fee-rule-2025.json", "2026.1", "fee-rule-2026.json").entrySet()) {
            JsonNode db = objectMapper.readTree(jdbc.queryForObject(
                    "SELECT body::text FROM sal.fee_rule_set WHERE rule_version = ?", String.class, e.getKey()));
            assertThat(db).as(e.getKey()).isEqualTo(objectMapper.readTree(rules.resolve(e.getValue()).toFile()));
        }
        assertThat(objectMapper.readTree(jdbc.queryForObject("SELECT body::text FROM sal.qual_criteria "
                + "WHERE criteria_code = 'LOCAL_GOV_SERVICE_V2025'", String.class)))
                .isEqualTo(objectMapper.readTree(rules.resolve("qual-local-gov-v2025.json").toFile()));
        assertThat(discountPolicy.limits()).containsOnlyKeys("SALES", "SALES_LEAD", "EXEC");
        assertThat(discountPolicy.limitFor(java.util.Set.of("SALES", "SALES_LEAD"))).isEqualByComparingTo("0.10");
        assertThat(discountPolicy.execLimit()).isEqualByComparingTo("0.49");
    }

    @Test
    @DisplayName("TC-SAL-010 · 011 · 012 · A8-5: 기준일 2025-12-31 → 2025.1 / 2026-01-01 → 2026.1(골든 G-09 · G-10 합계), 요율 · 면적 · 규칙 공백 422")
    void feeCalculationApi() throws Exception {
        JsonNode r25 = json(post("/api/v1/sal/fee-calculations", feeRequest("2025-12-31", null), sales, "SALES")
                .andExpect(status().isOk()).andReturn());
        JsonNode r26 = json(post("/api/v1/sal/fee-calculations", feeRequest("2026-01-01", null), sales, "SALES")
                .andExpect(status().isOk()).andReturn());
        assertThat(r25.get("ruleVersion").asText()).isEqualTo("2025.1");
        assertThat(r26.get("ruleVersion").asText()).isEqualTo("2026.1");
        assertThat(r25.get("subtotal").decimalValue()).isEqualByComparingTo("50069000");   // G-09
        assertThat(r26.get("subtotal").decimalValue()).isEqualByComparingTo("51977000");   // G-10
        assertThat(r25.get("lines").get(0).get("unitPrice").asLong())
                .isNotEqualTo(r26.get("lines").get(0).get("unitPrice").asLong());
        assertThat(r26.get("ruleRanges").get("overhead").get("max").decimalValue()).isEqualByComparingTo("1.2");
        assertThat(r26.get("explanations").toString()).contains("AGE 보정");

        Map<String, Object> bad = feeRequest("2026-03-01", null);
        bad.put("overheadRate", 1.30);
        post("/api/v1/sal/fee-calculations", bad, sales, "SALES").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAL-422-002"));
        Map<String, Object> huge = feeRequest("2026-03-01", null);
        huge.put("facility", map("facilityClass", "BUILDING", "grossAreaM2", 250000));
        post("/api/v1/sal/fee-calculations", huge, sales, "SALES").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAL-422-005"));
        post("/api/v1/sal/fee-calculations", feeRequest("2024-06-30", null), sales, "SALES")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SAL-422-004"));
        // ENG 는 대가 산정 권한 없음
        post("/api/v1/sal/fee-calculations", feeRequest("2026-03-01", null), sales, "ENG")
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ 견적 · 할인 승인 (BR-SAL-006)

    @Test
    @DisplayName("TC-SAL-013 · 015 · 016: SALES 7% → PENDING_APPROVAL · 발송 차단 / SALES_LEAD :approve-discount 403 / EXEC 승인(If-Match) 후 :issue → ISSUED · approved_by · sal.estimate.issued")
    void discountApprovalFlow() throws Exception {
        UUID client = createClient(sales, "합성발주처-sal-" + UUID.randomUUID());
        UUID opp = proposingOpportunity(client, "PRECISE_DIAG");
        JsonNode v1 = createEstimate(opp, "0.07", sales, "SALES");
        UUID e1 = UUID.fromString(v1.get("id").asText());
        assertThat(v1.get("versionNo").asInt()).isEqualTo(1);
        assertThat(v1.get("estimateNo").asText()).matches("EST-\\d{4}-\\d{4}");
        assertThat(v1.get("subtotal").decimalValue()).isEqualByComparingTo("51977000");          // 골든 G-10 과 같은 입력(2026 규칙)
        assertThat(v1.get("offerAmount").decimalValue()).isEqualByComparingTo("48338000");       // 51,977,000 × 0.93 천원 절사
        assertThat(v1.get("vat").decimalValue()).isEqualByComparingTo("4833800");
        assertThat(v1.get("lines")).hasSize(5 * 4 + 1);

        // TC-SAL-013
        JsonNode pending = issue(e1, sales, "SALES");
        assertThat(pending.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.get("discountLimit").decimalValue()).isEqualByComparingTo("0.05");
        post("/api/v1/sal/estimates/" + e1 + ":send", Map.of(), sales, "SALES")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SAL-422-007"));
        assertThat(events("sal.estimate.issued")).isEmpty();

        // TC-SAL-015: 영업팀장(SALES + SALES_LEAD 그룹)은 승인 권한 없음
        long ver = pending.get("version").asLong();
        postIfMatch("/api/v1/sal/estimates/" + e1 + ":approve-discount", map("decision", "APPROVE"), ver, lead, "SALES")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-001"));
        assertThat(get("/api/v1/sal/estimates/" + e1, sales, "SALES").get("status").asText()).isEqualTo("PENDING_APPROVAL");

        // TC-SAL-016: If-Match 없음 428 · 불일치 412 → 맞으면 승인
        post("/api/v1/sal/estimates/" + e1 + ":approve-discount", map("decision", "APPROVE"), exec, "EXEC")
                .andExpect(status().is(428));
        postIfMatch("/api/v1/sal/estimates/" + e1 + ":approve-discount", map("decision", "APPROVE"), ver + 5, exec, "EXEC")
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("CORE-412-001"));
        JsonNode approved = json(postIfMatch("/api/v1/sal/estimates/" + e1 + ":approve-discount",
                map("decision", "APPROVE", "comment", "합성: 전략 고객"), ver, exec, "EXEC")
                .andExpect(status().isOk()).andReturn());
        assertThat(approved.get("approvedBy").asText()).isEqualTo(exec.toString());
        assertThat(approved.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode issued = issue(e1, sales, "SALES");
        assertThat(issued.get("status").asText()).isEqualTo("ISSUED");
        assertThat(issued.get("approvedBy").asText()).isEqualTo(exec.toString());
        var ev = events("sal.estimate.issued");
        assertThat(ev).hasSize(1);
        assertThat(ev.get(0).get("offerAmount").decimalValue()).isEqualByComparingTo("48338000");
        assertThat(ev.get(0).get("version").asInt()).isEqualTo(1);

        // TC-SAL-014(API 경로 밖 직접 SQL): 발행 견적 금액 · 라인 수정 → 트리거 SAL-409-010
        assertThatThrownBy(() -> jdbc.update("UPDATE sal.estimate SET offer_amount = offer_amount - 1 WHERE id = ?", e1))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("SAL-409-010");
        assertThatThrownBy(() -> jdbc.update("UPDATE sal.estimate_line SET amount = 0 WHERE estimate_id = ?", e1))
                .hasMessageContaining("SAL-409-010");

        // 발송 → 기회 NEGOTIATING, 새 버전(3%) → v1 SUPERSEDED · 금액 불변, v2 는 SALES 한도 안이라 바로 ISSUED
        post("/api/v1/sal/estimates/" + e1 + ":send", map("contacts", List.of("합성 담당자")), sales, "SALES")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        assertThat(get("/api/v1/sal/opportunities/" + opp, sales, "SALES").get("stage").asText()).isEqualTo("NEGOTIATING");
        JsonNode v2 = createEstimate(opp, "0.03", sales, "SALES");
        assertThat(v2.get("versionNo").asInt()).isEqualTo(2);
        assertThat(v2.get("estimateNo").asText()).isEqualTo(v1.get("estimateNo").asText());
        JsonNode old = get("/api/v1/sal/estimates/" + e1, sales, "SALES");
        assertThat(old.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(old.get("offerAmount").decimalValue()).isEqualByComparingTo("48338000");
        assertThat(issue(UUID.fromString(v2.get("id").asText()), sales, "SALES").get("status").asText()).isEqualTo("ISSUED");
    }

    @Test
    @DisplayName("BR-SAL-006: SALES_LEAD 8% → 바로 ISSUED(10% 한도) · 12% → PENDING → EXEC REJECT → DRAFT / EXEC 한도 49% 초과(49.5%) 승인 422")
    void leadLimitRejectAndExecCap() throws Exception {
        UUID client = createClient(sales, "합성발주처-sal-" + UUID.randomUUID());
        UUID opp = proposingOpportunity(client, "PRECISE_DIAG");
        JsonNode e8 = createEstimate(opp, "0.08", lead, "SALES");
        assertThat(issue(UUID.fromString(e8.get("id").asText()), lead, "SALES").get("status").asText()).isEqualTo("ISSUED");
        assertThat(issue(UUID.fromString(createEstimate(opp, "0.08", sales, "SALES").get("id").asText()), sales, "SALES")
                .get("status").asText()).isEqualTo("PENDING_APPROVAL");   // 같은 8% 도 SALES 는 승인 대기

        JsonNode e12 = createEstimate(opp, "0.12", lead, "SALES");
        UUID id12 = UUID.fromString(e12.get("id").asText());
        JsonNode p = issue(id12, lead, "SALES");
        assertThat(p.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode rejected = json(postIfMatch("/api/v1/sal/estimates/" + id12 + ":approve-discount",
                map("decision", "REJECT", "comment", "합성: 할인 과다"), p.get("version").asLong(), exec, "EXEC")
                .andExpect(status().isOk()).andReturn());
        assertThat(rejected.get("status").asText()).isEqualTo("DRAFT");
        assertThat(rejected.get("approvedBy").isNull()).isTrue();

        JsonNode e495 = createEstimate(opp, "0.495", sales, "SALES");
        UUID id495 = UUID.fromString(e495.get("id").asText());
        JsonNode p495 = issue(id495, sales, "SALES");
        postIfMatch("/api/v1/sal/estimates/" + id495 + ":approve-discount", map("decision", "APPROVE"),
                p495.get("version").asLong(), exec, "EXEC").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAL-422-006"));
        post("/api/v1/sal/opportunities/" + opp + "/estimates", feeRequest("2026-10-15", new BigDecimal("0.5")), sales,
                "SALES").andExpect(status().isUnprocessableEntity());
    }

    // ------------------------------------------------------------------ 보안 검토 2026-10-19 회귀 시험

    @Test
    @DisplayName("SEC-SAL-01: EXEC A 승인 후 EXEC B 반려 → DRAFT · approved_by 해제, 다시 :issue 하면 ISSUED 가 아니라 PENDING_APPROVAL")
    void rejectAfterApproveClearsApproval() throws Exception {
        UUID exec2 = employee("임원2");
        UUID client = createClient(sales, "합성발주처-sal-" + UUID.randomUUID());
        UUID opp = proposingOpportunity(client, "PRECISE_DIAG");
        UUID id = UUID.fromString(createEstimate(opp, "0.07", sales, "SALES").get("id").asText());
        JsonNode p = issue(id, sales, "SALES");
        assertThat(p.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode approved = json(postIfMatch("/api/v1/sal/estimates/" + id + ":approve-discount",
                map("decision", "APPROVE"), p.get("version").asLong(), exec, "EXEC").andExpect(status().isOk()).andReturn());
        JsonNode rejected = json(postIfMatch("/api/v1/sal/estimates/" + id + ":approve-discount",
                map("decision", "REJECT", "comment", "합성: 재검토"), approved.get("version").asLong(), exec2, "EXEC")
                .andExpect(status().isOk()).andReturn());
        assertThat(rejected.get("status").asText()).isEqualTo("DRAFT");
        assertThat(rejected.get("approvedBy").isNull()).isTrue();
        JsonNode again = issue(id, sales, "SALES");
        assertThat(again.get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(events("sal.estimate.issued")).isEmpty();
    }

    @Test
    @DisplayName("SEC-SAL-02: 다른 영업 기회의 ACCEPTED 견적을 계약에 연결 → 422 SAL-422-001, 계약 0건")
    void contractRejectsEstimateOfOtherOpportunity() throws Exception {
        UUID[] a = wonWithAcceptedEstimate();
        UUID[] b = wonWithAcceptedEstimate();
        BigDecimal offerB = get("/api/v1/sal/estimates/" + b[1], sales, "SALES").get("offerAmount").decimalValue();
        post("/api/v1/sal/contracts", contractBody(a[0], b[1], "PRIVATE_SECTOR", offerB), sales, "SALES")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SAL-422-001"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sal.contract WHERE opportunity_id = ?", Integer.class, a[0]))
                .isZero();
    }

    @Test
    @DisplayName("SEC-SAL-03: page=2147483647 → int 곱 오버플로(음수 OFFSET) 500 이 아니라 200 빈 목록")
    void pagingOverflowIsNot500() throws Exception {
        mvc.perform(as(MockMvcRequestBuilders.get("/api/v1/sal/opportunities?page=2147483647&size=500"), sales, "SALES"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        mvc.perform(as(MockMvcRequestBuilders.get("/api/v1/sal/bid-notices?page=2147483647&size=500"), sales, "SALES"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    // ------------------------------------------------------------------ 계약 → 프로젝트 → CST 실행예산 (TC-SAL-031 · 030 · 032, TC-CST-01)

    /** WON 기회 + ACCEPTED 견적 */
    UUID[] wonWithAcceptedEstimate() throws Exception {
        UUID client = createClient(sales, "합성발주처-sal-" + UUID.randomUUID());
        UUID opp = proposingOpportunity(client, "PRECISE_DIAG");
        UUID est = UUID.fromString(createEstimate(opp, "0.03", sales, "SALES").get("id").asText());
        issue(est, sales, "SALES");
        post("/api/v1/sal/estimates/" + est + ":send", Map.of(), sales, "SALES").andExpect(status().isOk());
        post("/api/v1/sal/estimates/" + est + ":accept", Map.of(), sales, "SALES").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
        assertThat(get("/api/v1/sal/opportunities/" + opp, sales, "SALES").get("stage").asText()).isEqualTo("WON");
        return new UUID[] {opp, est, client};
    }

    Map<String, Object> contractBody(UUID opp, UUID est, String method, Object amount) {
        return map("opportunityId", opp, "estimateId", est, "title", "합성 정밀안전진단 용역 계약", "contractMethod", method,
                "amount", amount, "signDate", "2026-10-19", "startDate", "2026-11-01", "endDate", "2027-02-28");
    }

    @Test
    @DisplayName("TC-SAL-031 · TC-CST-01: 계약 등록 → EXEC 결재(ready-to-sign) → 서명 기록 → PRJ 채번 · 기회 CONTRACTED · sal.contract.signed → CST 실행예산 초안 1건(중복 수신 2종에도 1건)")
    void contractToProjectAndBudget() throws Exception {
        UUID[] w = wonWithAcceptedEstimate();
        UUID opp = w[0];
        UUID est = w[1];
        BigDecimal offer = get("/api/v1/sal/estimates/" + est, sales, "SALES").get("offerAmount").decimalValue();

        // TC-SAL-032: 수의계약 사유 누락 → DB CHECK → 422 / A8-8: 견적과 2% 차이 사유 누락 → 422
        post("/api/v1/sal/contracts", contractBody(opp, est, "PRIVATE_NEGOTIATED", offer), sales, "SALES")
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("SAL-422-011"));
        post("/api/v1/sal/contracts", contractBody(opp, est, "PRIVATE_SECTOR", offer.multiply(new BigDecimal("1.02"))),
                sales, "SALES").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAL-422-012"));

        JsonNode c = json(post("/api/v1/sal/contracts", contractBody(opp, est, "PRIVATE_SECTOR", offer), sales, "SALES")
                .andExpect(status().isCreated()).andReturn());
        UUID cid = UUID.fromString(c.get("id").asText());
        assertThat(c.get("status").asText()).isEqualTo("DRAFT");
        assertThat(c.get("contractNo").asText()).startsWith("CTR-2026-");

        // 사내 결재: SALES 403, EXEC → PENDING + ready-to-sign 1회
        postIfMatch("/api/v1/sal/contracts/" + cid + ":approve", Map.of(), c.get("version").asLong(), sales, "SALES")
                .andExpect(status().isForbidden());
        JsonNode pending = json(postIfMatch("/api/v1/sal/contracts/" + cid + ":approve",
                map("signers", List.of("합성 발주처 대표")), c.get("version").asLong(), exec, "EXEC")
                .andExpect(status().isOk()).andReturn());
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        var rts = events("sal.contract.ready-to-sign");
        assertThat(rts).hasSize(1);
        assertThat(rts.get(0).get("contractId").asText()).isEqualTo(cid.toString());
        assertThat(rts.get(0).get("signDeadline").asText()).isEqualTo("2026-11-02");
        assertThat(rts.get(0).get("signers").get(0).asText()).isEqualTo("합성 발주처 대표");

        // 고객 서명 기록 → 프로젝트 생성(같은 트랜잭션) · sal.contract.signed
        JsonNode signed = json(postIfMatch("/api/v1/sal/contracts/" + cid + ":sign", map("signedOn", "2026-10-19"),
                pending.get("version").asLong(), sales, "SALES").andExpect(status().isOk()).andReturn());
        assertThat(signed.get("status").asText()).isEqualTo("APPROVED");
        assertThat(signed.get("projectNo").asText()).matches("PRJ-2026-\\d{4}");
        UUID projectId = UUID.fromString(signed.get("projectId").asText());
        assertThat(jdbc.queryForObject("SELECT contract_id FROM core.project WHERE id = ?", UUID.class, projectId))
                .isEqualTo(cid);
        assertThat(jdbc.queryForObject("SELECT contract_amount FROM core.project WHERE id = ?", BigDecimal.class,
                projectId)).isEqualByComparingTo(offer);
        assertThat(get("/api/v1/sal/opportunities/" + opp, sales, "SALES").get("stage").asText()).isEqualTo("CONTRACTED");
        var sig = events("sal.contract.signed");
        assertThat(sig).hasSize(1);
        assertThat(sig.get(0).get("projectId").asText()).isEqualTo(projectId.toString());
        assertThat(sig.get(0).get("estimateLines")).hasSize(21);
        assertThat(events("core.project.created")).hasSize(1);

        // CST 구독 → 실행예산 Rev.0 DRAFT (TC-CST-01)
        relay.pollOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.budget_revision WHERE project_id = ?", Integer.class,
                projectId)).isEqualTo(1);
        var rev = jdbc.queryForMap("SELECT * FROM cst.budget_revision WHERE project_id = ?", projectId);
        assertThat(rev).containsEntry("status", "DRAFT").containsEntry("rev_no", 0);
        BigDecimal estimateCost = jdbc.queryForObject("SELECT direct_labor + direct_expense FROM sal.estimate WHERE id = ?",
                BigDecimal.class, est);
        assertThat((BigDecimal) rev.get("bac_krw")).isCloseTo(estimateCost, org.assertj.core.data.Offset.offset(
                new BigDecimal("50")));   // 라인 금액 = round(인·일 × 단가) — 견적 라인 절사와 원 단위 차이만
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.budget_line WHERE revision_id = ?", Integer.class,
                rev.get("id"))).isEqualTo(21);
        assertThat(jdbc.queryForList("SELECT code FROM cst.wbs_node WHERE project_id = ? ORDER BY sort_order",
                String.class, projectId)).containsExactly("10-DATA", "20-FIELD", "30-TEST", "40-ANALYSIS", "50-REPORT",
                "90-EXPENSE");
        assertThat(jdbc.queryForObject("SELECT sum(weight_pct) FROM cst.wbs_node WHERE project_id = ?", BigDecimal.class,
                projectId)).isEqualByComparingTo("100");
        // 현장조사 WBS 는 FLD 조사 완료로 진척 100%(마일스톤) — 골든 패스 시험에서 드러난 누락 회귀
        assertThat(jdbc.queryForList("SELECT code FROM cst.wbs_node WHERE project_id = ? AND milestone_event = "
                + "'fld.survey.completed'", String.class, projectId)).containsExactly("20-FIELD");

        // 중복 수신 ① 같은 eventId 재전달(published_at 초기화) ② 새 eventId 같은 payload → 여전히 1건
        jdbc.update("UPDATE core.outbox_event SET published_at = NULL WHERE event_type = 'sal.contract.signed'");
        Timestamp now = Timestamp.from(clock.instant());
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, event_version, payload, headers,
                                              occurred_at, next_attempt_at)
                SELECT gen_random_uuid(), aggregate_type, aggregate_id, event_type, event_version, payload, headers, ?, ?
                  FROM core.outbox_event WHERE event_type = 'sal.contract.signed'""", now, now);
        relay.pollOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.budget_revision WHERE project_id = ?", Integer.class,
                projectId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_dead", Integer.class)).isZero();
        // 서명 완료 계약은 다시 서명 불가
        postIfMatch("/api/v1/sal/contracts/" + cid + ":sign", Map.of(), signed.get("version").asLong(), sales, "SALES")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAL-409-005"));
    }

    @Test
    @DisplayName("TC-SAL-030: 서명 기록 시 기술자 투입 중복 → 409 SAL-409-003, 프로젝트 · sal.contract.signed 0건, 계약 PENDING 유지")
    void assignmentConflictRollsBack() throws Exception {
        UUID[] w = wonWithAcceptedEstimate();
        BigDecimal offer = get("/api/v1/sal/estimates/" + w[1], sales, "SALES").get("offerAmount").decimalValue();
        JsonNode c = json(post("/api/v1/sal/contracts", contractBody(w[0], w[1], "PRIVATE_SECTOR", offer), sales, "SALES")
                .andExpect(status().isCreated()).andReturn());
        UUID cid = UUID.fromString(c.get("id").asText());
        JsonNode pending = json(postIfMatch("/api/v1/sal/contracts/" + cid + ":approve", Map.of(),
                c.get("version").asLong(), exec, "EXEC").andExpect(status().isOk()).andReturn());
        feasibility.markConflict(cid);
        postIfMatch("/api/v1/sal/contracts/" + cid + ":sign", Map.of(), pending.get("version").asLong(), sales, "SALES")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SAL-409-003"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.project WHERE contract_id = ?", Integer.class, cid)).isZero();
        assertThat(events("sal.contract.signed")).isEmpty();
        assertThat(get("/api/v1/sal/contracts/" + cid, sales, "SALES").get("status").asText()).isEqualTo("PENDING");
    }

    // ------------------------------------------------------------------ 구독: 점검 도래 · 포털 (BR-SAL-007)

    @Test
    @DisplayName("TC-SAL-020: fac.inspection.due-approaching 중복(같은 eventId 재전달 + D-90 새 이벤트) → 기회 1건 · 이후는 활동, processed_event 멱등")
    void dueApproachingLeadIdempotent() throws Exception {
        UUID building = UUID.randomUUID();
        LocalDate due = LocalDate.of(2027, 5, 1);
        publish(new FacEvents.InspectionDueApproaching(building, "PRECISE_INSP", due, 180, building + ":PRECISE_INSP:180"));
        relay.pollOnce();
        List<Map<String, Object>> opps = jdbc.queryForList("SELECT * FROM sal.opportunity WHERE facility_id = ?", building);
        assertThat(opps).hasSize(1);
        assertThat(opps.get(0)).containsEntry("source", "DUE_SOON").containsEntry("stage", "LEAD")
                .containsEntry("due_inspection_type", "PRECISE_INSP").containsEntry("service_type", "PRECISE_INSP");
        UUID eventId = jdbc.queryForObject("SELECT id FROM core.outbox_event WHERE event_type = "
                + "'fac.inspection.due-approaching' AND aggregate_id = ?", UUID.class, building);
        jdbc.update("UPDATE core.outbox_event SET published_at = NULL WHERE id = ?", eventId);
        relay.pollOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event WHERE consumer = 'sal.due-soon-lead' "
                + "AND event_id = ?", Integer.class, eventId)).isEqualTo(1);
        publish(new FacEvents.InspectionDueApproaching(building, "PRECISE_INSP", due, 90, building + ":PRECISE_INSP:90"));
        relay.pollOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sal.opportunity WHERE facility_id = ?", Integer.class,
                building)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT summary FROM sal.activity WHERE opportunity_id = ?", String.class,
                opps.get(0).get("id"))).containsExactly("점검 도래 D-90 (PRECISE_INSP, 2027-05-01)");
    }

    @Test
    @DisplayName("ptl.service-request.submitted(스텁 계약) → PORTAL 리드, 같은 요청 2회(다른 eventId) → 1건")
    void portalRequestLead() {
        UUID req = UUID.randomUUID();
        publish(new PtlRequest(req, null, UUID.randomUUID(), "PRECISE_INSP", "합성: 점검 견적 요청"));
        publish(new PtlRequest(req, null, null, "PRECISE_INSP", "합성: 같은 요청 재전송"));
        relay.pollOnce();
        assertThat(jdbc.queryForList("SELECT title FROM sal.opportunity WHERE source = 'PORTAL' AND source_ref = ?",
                String.class, req.toString())).containsExactly("포털 요청: 합성: 점검 견적 요청");
    }

    // ------------------------------------------------------------------ 적격심사 · 실적 (BR-SAL-014)

    @Test
    @DisplayName("TC-SAL-041: 실적 이관 cert_no 중복(배치 안 · DB) 거부 리포트, 공동도급 40% 실적은 금액 × 0.4 로 수행능력 반영(performance_record 만)")
    void performanceImportAndSimulation() throws Exception {
        String svc = "PD" + UUID.randomUUID().toString().substring(0, 8);
        String p = "CERT-" + UUID.randomUUID().toString().substring(0, 8) + "-";
        var rows = List.of(
                map("certNo", p + "A", "serviceType", svc, "clientName", "합성시", "amountKrw", 100000000,
                        "startDate", "2025-01-02", "endDate", "2025-06-30"),
                map("certNo", p + "B", "serviceType", svc, "clientName", "합성공단", "amountKrw", 200000000,
                        "jointShare", 40, "startDate", "2024-03-01", "endDate", "2024-12-31"),
                map("certNo", p + "A", "serviceType", svc, "clientName", "합성시", "amountKrw", 100000000,
                        "startDate", "2025-01-02", "endDate", "2025-06-30"),
                map("certNo", p + "OLD", "serviceType", svc, "clientName", "합성구", "amountKrw", 900000000,
                        "startDate", "2019-01-01", "endDate", "2019-12-31"),
                map("certNo", p + "X", "serviceType", "OTHER_SVC", "clientName", "합성군", "amountKrw", 500000000,
                        "startDate", "2025-01-01", "endDate", "2025-03-31"));
        JsonNode rep = json(post("/api/v1/sal/performance-records:import", map("rows", rows), admin, "ADMIN")
                .andExpect(status().isOk()).andReturn());
        assertThat(rep.get("inserted").asInt()).isEqualTo(4);
        assertThat(rep.get("rejected").toString()).contains(p + "A").contains("DUPLICATE_IN_BATCH");
        JsonNode rep2 = json(post("/api/v1/sal/performance-records:import", map("rows", List.of(rows.get(1))), admin,
                "ADMIN").andExpect(status().isOk()).andReturn());
        assertThat(rep2.get("inserted").asInt()).isZero();
        assertThat(rep2.get("rejected").get(0).get("reason").asText()).isEqualTo("DUPLICATE_CERT_NO");

        JsonNode o = json(post("/api/v1/sal/opportunities", map("source", "G2B", "serviceType", svc, "title",
                "합성 공고 기회", "ownerId", sales), sales, "SALES").andExpect(status().isCreated()).andReturn());
        JsonNode sim = json(post("/api/v1/sal/opportunities/" + o.get("id").asText() + "/simulations",
                map("noticeDate", "2026-10-12", "estPrice", 182000000, "bidRates", List.of(0.877, 0.882, 0.9),
                        "engineersQualified", 6, "engineersRequired", 5, "creditGrade", "BBB0"), sales, "SALES")
                .andExpect(status().isOk()).andReturn());
        // 100,000,000 + 200,000,000 × 0.4 = 180,000,000 (2019 실적 · 다른 용역 제외) → 비율 0.989 → 13.2
        assertThat(sim.get("inputs").get("similarAmount").decimalValue()).isEqualByComparingTo("180000000");
        assertThat(sim.get("inputs").get("performanceRecords")).hasSize(2);
        assertThat(sim.get("inputs").get("performanceSource").asText()).isEqualTo("sal.performance_record");
        assertThat(sim.get("capability").get("total").decimalValue()).isEqualByComparingTo("41.2");
        assertThat(sim.get("scenarios").get(0).get("belowLowerLimit").asBoolean()).isTrue();
        assertThat(sim.get("scenarios").get(1).get("total").decimalValue()).isEqualByComparingTo("95.8");
        assertThat(sim.get("recommendedBidRate").decimalValue()).isEqualByComparingTo("0.882");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sal.qual_simulation WHERE opportunity_id = ?::uuid",
                Integer.class, o.get("id").asText())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 나라장터 수집 (BR-SAL-001 · 013)

    static String g2bPage(String... items) {
        return """
                {"response":{"header":{"resultCode":"00","resultMsg":"정상"},
                 "body":{"items":[%s],"numOfRows":100,"pageNo":1,"totalCount":%d}}}""".formatted(String.join(",", items),
                items.length);
    }

    static String g2bItem(String bidNo, String seq, String kind, String price) {
        return """
                {"bidNtceNo":"%s","bidNtceOrd":"%s","bidNtceNm":"합성시 청사 정밀안전진단 용역(%s)","ntceInsttNm":"합성시",
                 "ntceKindNm":"%s","presmptPrce":"%s","bidNtceDt":"2026-10-12 10:00:00","bidClseDt":"2026-10-26 18:00:00"}"""
                .formatted(bidNo, seq, seq, kind, price);
    }

    void stubG2b(String body) {
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo(G2B_OP)).willReturn(okJson(body)));
    }

    JsonNode collectNow() throws Exception {
        return json(post("/api/v1/sal/bid-notices:collect-now", Map.of(), admin, "ADMIN").andExpect(status().isOk())
                .andReturn());
    }

    @Test
    @DisplayName("TC-SAL-001 · 002 · A8-3: 같은 응답 2회 → 1행 · updated_at 불변 / 정정(001차) → 000차 SUPERSEDED · 기회 기준변경 / 취소공고 → 기회 DROPPED")
    void collectUpsertSupersedeCancel() throws Exception {
        String bidNo = "R26BK" + String.format("%08d", (int) (Math.random() * 1e8));
        stubG2b(g2bPage(g2bItem(bidNo, "000", "등록공고", "182000000")));
        JsonNode r1 = collectNow();
        assertThat(r1.get("success").asBoolean()).isTrue();
        assertThat(r1.get("inserted").asInt()).isEqualTo(1);
        UUID n0 = jdbc.queryForObject("SELECT id FROM sal.bid_notice WHERE bid_no = ? AND bid_seq = '000'", UUID.class, bidNo);
        Timestamp before = jdbc.queryForObject("SELECT updated_at FROM sal.bid_notice WHERE id = ?", Timestamp.class, n0);
        assertThat(events("sal.bid-notice.collected")).hasSize(1);
        clock.advance(Duration.ofMinutes(30));
        JsonNode r2 = collectNow();
        assertThat(r2.get("upserted").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sal.bid_notice WHERE bid_no = ?", Integer.class, bidNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM sal.bid_notice WHERE id = ?", Timestamp.class, n0))
                .isEqualTo(before);

        JsonNode opp = json(post("/api/v1/sal/opportunities", map("source", "G2B", "bidNoticeId", n0, "serviceType",
                "PRECISE_DIAG", "title", "합성 공고 기회"), sales, "SALES").andExpect(status().isCreated()).andReturn());
        assertThat(jdbc.queryForObject("SELECT status FROM sal.bid_notice WHERE id = ?", String.class, n0))
                .isEqualTo("CONVERTED");

        stubG2b(g2bPage(g2bItem(bidNo, "001", "정정공고", "190000000")));
        clock.advance(Duration.ofMinutes(30));
        assertThat(collectNow().get("inserted").asInt()).isEqualTo(1);
        UUID n1 = jdbc.queryForObject("SELECT id FROM sal.bid_notice WHERE bid_no = ? AND bid_seq = '001'", UUID.class, bidNo);
        var old = jdbc.queryForMap("SELECT status, superseded_by FROM sal.bid_notice WHERE id = ?", n0);
        assertThat(old).containsEntry("status", "SUPERSEDED").containsEntry("superseded_by", n1);
        JsonNode o2 = get("/api/v1/sal/opportunities/" + opp.get("id").asText(), sales, "SALES");
        assertThat(o2.get("criteriaChanged").asBoolean()).isTrue();
        assertThat(o2.get("bidNoticeId").asText()).isEqualTo(n1.toString());

        stubG2b(g2bPage(g2bItem(bidNo, "002", "취소공고", "190000000")));
        clock.advance(Duration.ofMinutes(30));
        collectNow();
        assertThat(get("/api/v1/sal/opportunities/" + opp.get("id").asText(), sales, "SALES").get("stage").asText())
                .isEqualTo("DROPPED");
        assertThat(jdbc.queryForObject("SELECT status FROM sal.bid_notice WHERE bid_no = ? AND bid_seq = '002'",
                String.class, bidNo)).isEqualTo("CANCELLED");
        assertThat(get("/api/v1/sal/bid-notices?q=" + bidNo.substring(0, 5), sales, "SALES").isArray()).isTrue();
    }

    @Test
    @DisplayName("TC-SAL-004 · TC-SAL-003(부분): 성공 → 실패(500 ×3, collect_log success=false) → 다음 실행 구간 시작 = 마지막 성공 range_to − 10분(KST 파라미터), 실패 구간 누락 0")
    void collectWindowBackfillsAfterFailure() throws Exception {
        stubG2b(g2bPage());
        JsonNode ok = collectNow();
        assertThat(ok.get("success").asBoolean()).isTrue();
        var lastTo = jdbc.queryForObject("SELECT range_to FROM sal.collect_log WHERE id = ?", Timestamp.class,
                ok.get("logId").asLong()).toInstant().atOffset(ZoneOffset.UTC);

        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo(G2B_OP)).willReturn(aResponse().withStatus(500)));
        clock.advance(Duration.ofMinutes(30));
        int before = PUBLIC_API.findAll(getRequestedFor(urlPathEqualTo(G2B_OP))).size();
        JsonNode fail = collectNow();
        assertThat(fail.get("success").asBoolean()).isFalse();
        assertThat(fail.get("error").asText()).isEqualTo("HTTP_5XX");
        assertThat(PUBLIC_API.findAll(getRequestedFor(urlPathEqualTo(G2B_OP))).size() - before).isEqualTo(3);
        var failRow = jdbc.queryForMap("SELECT success, range_from FROM sal.collect_log WHERE id = ?",
                fail.get("logId").asLong());
        assertThat(failRow.get("success")).isEqualTo(false);
        assertThat(((Timestamp) failRow.get("range_from")).toInstant()).isEqualTo(lastTo.minusMinutes(10).toInstant());

        stubG2b(g2bPage());
        clock.advance(Duration.ofMinutes(30));
        JsonNode next = collectNow();
        assertThat(next.get("success").asBoolean()).isTrue();
        var nextFrom = jdbc.queryForObject("SELECT range_from FROM sal.collect_log WHERE id = ?", Timestamp.class,
                next.get("logId").asLong()).toInstant();
        assertThat(nextFrom).isEqualTo(lastTo.minusMinutes(10).toInstant());   // 실패 구간 포함(백필)
        var reqs = PUBLIC_API.findAll(getRequestedFor(urlPathEqualTo(G2B_OP)));
        assertThat(reqs.get(reqs.size() - 1).queryParameter("inqryBgnDt").firstValue())
                .isEqualTo(G2bApiClient.kst(lastTo.minusMinutes(10)));

        // 수동 백필은 BACKFILL 로 기록되고 정기 구간 기준이 되지 않음
        var bf = json(post("/api/v1/sal/bid-notices:collect", map("from", "2026-10-01T00:00:00Z", "to",
                "2026-10-02T00:00:00Z"), admin, "ADMIN").andExpect(status().isOk()).andReturn());
        assertThat(bf.get("jobType").asText()).isEqualTo("BACKFILL");
        post("/api/v1/sal/bid-notices:collect", map("from", "2026-10-01T00:00:00Z", "to", "2026-10-02T00:00:00Z"),
                sales, "SALES").andExpect(status().isForbidden());
    }
}
