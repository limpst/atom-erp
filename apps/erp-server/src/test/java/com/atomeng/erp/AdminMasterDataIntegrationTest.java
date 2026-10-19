package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.cst.application.RateService;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 관리 API(웹 프론트엔드가 찾은 "관리 API 없음" 보완) — 영업팀장 그룹(V0036) · QA 검토자 프로필(V0037) · 기준 요율 승인(V0038).
 * 권한 · If-Match · problem+json 코드 · 감사 로그 · 업무 효과(할인 그룹 해석 · 배정 자격 · 원장 단가). 데이터는 전부 합성.
 */
class AdminMasterDataIntegrationTest extends IntegrationTestBase {

    static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    SalJdbc salJdbc;
    @Autowired
    RateService rateService;

    UUID admin;
    UUID exec;

    @BeforeEach
    void setUp() {
        clock.reset();
        admin = actor();
        exec = actor();
    }

    UUID employee() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성직원', DATE '2024-01-01', ?, ?)", id, "AM-" + id.toString().substring(0, 12), id, id);
        return id;
    }

    ResultActions post(String url, String body, String ifMatch, UUID actor, String roles) throws Exception {
        var b = MockMvcRequestBuilders.post(url).contentType("application/json").content(body);
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return mvc.perform(as(b, actor, roles));
    }

    ResultActions patch(String url, String body, String ifMatch, UUID actor, String roles) throws Exception {
        var b = MockMvcRequestBuilders.patch(url).contentType("application/json").content(body);
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        return mvc.perform(as(b, actor, roles));
    }

    JsonNode body(ResultActions r) throws Exception {
        return json(r.andReturn());
    }

    long audits(String targetType, UUID id) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE target_type = ? AND target_id = ?",
                Long.class, targetType, id);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ SAL

    @Test
    @DisplayName("SAL 영업팀장 그룹: ADMIN 만 등록 · 조회, 중복 409, 비직원 422, If-Match 필수, 비활성화하면 할인 그룹 해석에서 빠짐, 감사 로그")
    void salesGroupMembership() throws Exception {
        UUID lead = employee();
        String add = "{\"employeeId\":\"" + lead + "\",\"groupCode\":\"SALES_LEAD\",\"note\":\"(합성) 1팀장\"}";
        post("/api/v1/sal/sales-group-members", add, null, actor(), "SALES").andExpect(status().isForbidden());
        mvc.perform(as(MockMvcRequestBuilders.get("/api/v1/sal/sales-group-members"), actor(), "EXEC"))
                .andExpect(status().isForbidden());
        JsonNode m = body(post("/api/v1/sal/sales-group-members", add, null, admin, "ADMIN")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("ETag", "\"v0\"")));
        assertThat(m.get("note").asText()).isEqualTo("(합성) 1팀장");
        assertThat(salJdbc.groupsOf(lead)).containsExactly("SALES_LEAD");

        post("/api/v1/sal/sales-group-members", add, null, admin, "ADMIN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SAL-409-011"));
        post("/api/v1/sal/sales-group-members", "{\"employeeId\":\"" + UUID.randomUUID()
                + "\",\"groupCode\":\"SALES_LEAD\"}", null, admin, "ADMIN").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SAL-422-001"));
        post("/api/v1/sal/sales-group-members", "{\"employeeId\":\"" + lead + "\",\"groupCode\":\"VIP\"}", null, admin,
                "ADMIN").andExpect(status().isUnprocessableEntity());

        String url = "/api/v1/sal/sales-group-members/" + lead + "/SALES_LEAD";
        patch(url, "{\"active\":false}", null, admin, "ADMIN").andExpect(status().is(428));
        patch(url, "{\"active\":false}", "\"v9\"", admin, "ADMIN").andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("CORE-412-001"));
        patch(url, "{\"active\":false}", "\"v0\"", admin, "ADMIN").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.version").value(1));
        assertThat(salJdbc.groupsOf(lead)).isEmpty();
        patch(url, "{\"active\":true,\"note\":\"(합성) 복귀\"}", "\"v1\"", admin, "ADMIN").andExpect(status().isOk());
        assertThat(salJdbc.groupsOf(lead)).containsExactly("SALES_LEAD");
        JsonNode list = body(mvc.perform(as(MockMvcRequestBuilders.get("/api/v1/sal/sales-group-members"), admin,
                "ADMIN")).andExpect(status().isOk()));
        assertThat(list.findValuesAsText("employeeId")).contains(lead.toString());
        assertThat(audits("SalesGroupMember", lead)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ QAR

    @Test
    @DisplayName("QAR 검토자 프로필: ADMIN 만 등록 · 수정, 단계 · 상한 · 전문성 검증 422, 중복 409, 비활성화 = 배정 자격 상실, 감사 로그")
    void reviewerProfile() throws Exception {
        UUID qa = employee();
        String create = "{\"employeeId\":\"" + qa + "\",\"maxOpen\":3,\"expertise\":{\"RT_PSD\":4},"
                + "\"qualifications\":[\"(합성) 건축구조기술사\",\" \"]}";
        post("/api/v1/qar/reviewer-profiles", create, null, actor(), "QA").andExpect(status().isForbidden());
        body(post("/api/v1/qar/reviewer-profiles", create, null, admin, "ADMIN").andExpect(status().isCreated())
                .andExpect(jsonPath("$.stepCode").value("QA_REVIEW")).andExpect(jsonPath("$.maxOpen").value(3))
                .andExpect(jsonPath("$.expertise.RT_PSD").value(4))
                .andExpect(jsonPath("$.qualifications.length()").value(1))
                .andExpect(jsonPath("$.openAssignments").value(0)));
        post("/api/v1/qar/reviewer-profiles", create, null, admin, "ADMIN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("QAR-409-008"));
        UUID other = employee();
        post("/api/v1/qar/reviewer-profiles", "{\"employeeId\":\"" + other + "\",\"stepCode\":\"FINAL_APPROVAL\"}",
                null, admin, "ADMIN").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("QAR-422-001"));
        post("/api/v1/qar/reviewer-profiles", "{\"employeeId\":\"" + other + "\",\"expertise\":{\"psd\":-1}}",
                null, admin, "ADMIN").andExpect(status().isUnprocessableEntity());

        String url = "/api/v1/qar/reviewer-profiles/" + qa;
        patch(url, "{\"maxOpen\":8}", "\"v0\"", admin, "ADMIN").andExpect(status().isOk())
                .andExpect(jsonPath("$.maxOpen").value(8)).andExpect(jsonPath("$.expertise.RT_PSD").value(4));
        patch(url, "{\"active\":false}", "\"v1\"", admin, "ADMIN").andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM qar.reviewer_profile WHERE employee_id = ? AND active",
                Integer.class, qa)).isZero();
        assertThat(audits("ReviewerProfile", qa)).isEqualTo(3);
    }

    // ------------------------------------------------------------------ CST

    String rateBody(String key, long value, LocalDate from, LocalDate to) {
        return "{\"rateKind\":\"STD_LABOR\",\"keyCode\":\"" + key + "\",\"value\":" + value + ",\"validFrom\":\"" + from
                + "\"" + (to == null ? "" : ",\"validTo\":\"" + to + "\"") + ",\"sourceRef\":\"(합성) 예시 단가\"}";
    }

    static String v(JsonNode n) {
        return "\"v" + n.get("version").asLong() + "\"";
    }

    @Test
    @DisplayName("CST 기준 요율: ADMIN 초안 → EXEC 승인(4-eyes 403) · 승인 전 원장 미사용 · 초안 1개 · 초안만 수정 · 기간 겹침 409 · 반려 · 소급 종료 금지")
    void rateApprovalFlow() throws Exception {
        String key = "SYN-G" + SEQ.incrementAndGet();
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        LocalDate y2025 = LocalDate.of(2025, 1, 1);

        JsonNode d1 = body(post("/api/v1/cst/rates", rateBody(key, 200000, y2025, null), null, admin, "ADMIN")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")));
        UUID id1 = UUID.fromString(d1.get("id").asText());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cst.rate_table WHERE id = ?", Integer.class, id1)).isOne();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> rateService.resolve("STD_LABOR", key, today))
                .hasMessageContaining("유효한 요율 없음");   // 승인 전에는 원장 · 예산이 쓰지 않는다

        // 초안은 키당 1개
        post("/api/v1/cst/rates", rateBody(key, 210000, y2025.plusMonths(1), null), null, admin, "ADMIN")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-006"));
        // ADMIN 은 승인 권한 없음, 등록자 본인이 EXEC 를 겸해도 4-eyes 로 403
        post("/api/v1/cst/rates/" + id1 + ":approve", "{}", v(d1), admin, "ADMIN").andExpect(status().isForbidden());
        post("/api/v1/cst/rates/" + id1 + ":approve", "{}", v(d1), admin, "ADMIN,EXEC")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CST-403-001"));
        // 초안 수정(If-Match) — 수정자도 승인 불가
        JsonNode e1 = body(patch("/api/v1/cst/rates/" + id1, "{\"value\":205000}", v(d1), admin, "ADMIN")
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(205000)));
        post("/api/v1/cst/rates/" + id1 + ":approve", "{}", v(d1), exec, "EXEC")
                .andExpect(status().isPreconditionFailed());
        JsonNode a1 = body(post("/api/v1/cst/rates/" + id1 + ":approve", "{}", v(e1), exec, "EXEC")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value(exec.toString())));
        assertThat(rateService.resolve("STD_LABOR", key, today)).isEqualByComparingTo(new BigDecimal("205000"));
        // 승인된 행은 수정 불가
        patch("/api/v1/cst/rates/" + id1, "{\"value\":1}", v(a1), admin, "ADMIN").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CST-409-005"));

        // 새 적용일 초안 → 승인 때 직전 무기한 행 자동 마감
        LocalDate next = today.plusDays(10);
        JsonNode d2 = body(post("/api/v1/cst/rates", rateBody(key, 220000, next, null), null, admin, "ADMIN")
                .andExpect(status().isCreated()));
        body(post("/api/v1/cst/rates/" + d2.get("id").asText() + ":approve", "{}", v(d2), exec, "EXEC")
                .andExpect(status().isOk()));
        assertThat(rateService.resolve("STD_LABOR", key, next)).isEqualByComparingTo("220000");
        assertThat(rateService.resolve("STD_LABOR", key, next.minusDays(1))).isEqualByComparingTo("205000");

        // 이미 마감된 구간 안쪽의 한정 기간 초안 → 승인 때 겹침 409
        JsonNode d3 = body(post("/api/v1/cst/rates", rateBody(key, 230000, next.plusDays(5), next.plusDays(20)), null,
                admin, "ADMIN").andExpect(status().isCreated()));
        post("/api/v1/cst/rates/" + d3.get("id").asText() + ":approve", "{}", v(d3), exec, "EXEC")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CST-409-007"));
        // 반려(사유 필수)
        post("/api/v1/cst/rates/" + d3.get("id").asText() + ":reject", "{\"reason\":\"\"}", v(d3), exec, "EXEC")
                .andExpect(status().isUnprocessableEntity());
        post("/api/v1/cst/rates/" + d3.get("id").asText() + ":reject", "{\"reason\":\"(합성) 근거 불일치\"}", v(d3),
                exec, "EXEC").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));

        // 적용 종료: 소급 금지, 오늘 이후만
        JsonNode cur = body(mvc.perform(as(MockMvcRequestBuilders.get("/api/v1/cst/rates/" + d2.get("id").asText()),
                admin, "ADMIN")).andExpect(status().isOk()));
        post("/api/v1/cst/rates/" + d2.get("id").asText() + ":retire", "{\"validTo\":\"" + today.minusDays(1) + "\"}",
                v(cur), admin, "ADMIN").andExpect(status().isUnprocessableEntity());
        post("/api/v1/cst/rates/" + d2.get("id").asText() + ":retire", "{\"validTo\":\"" + next.plusDays(30) + "\"}",
                v(cur), admin, "ADMIN").andExpect(status().isOk())
                .andExpect(jsonPath("$.validTo").value(next.plusDays(30).toString()));
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> rateService.resolve("STD_LABOR", key, next.plusDays(30))).hasMessageContaining("유효한 요율 없음");
        assertThat(audits("Rate", id1)).isGreaterThanOrEqualTo(4);   // 등록 · 수정 · 승인 · (다음 승인 때) 마감
    }
}
