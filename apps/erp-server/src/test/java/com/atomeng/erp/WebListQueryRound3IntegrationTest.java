package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 웹 3차 화면용 읽기 API — HRQ 자격 · 월 마감 목록, CST 회계기간 · 대사 · EVM 추이, FCD 가져오기 목록, AIP Job · 제안 목록.
 * 쓰기 · 마이그레이션 · 권한 시드 변경 없음. 범위 규칙은 단건 조회와 같고, 판정할 수 없으면 거부(실패하는 쪽으로 닫힘).
 */
class WebListQueryRound3IntegrationTest extends IntegrationTestBase {

    UUID admin;

    @BeforeEach
    void setUp() {
        clock.reset();
        admin = actor();
    }

    private JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String roles, int expect) throws Exception {
        MvcResult r = mvc.perform(as(b, actor, roles)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as("%s → %s", r.getRequest().getRequestURI(), body).isEqualTo(expect);
        return body.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private UUID employee(String empNo) throws Exception {
        UUID id = UUID.randomUUID();
        call(postJson("/api/v1/hrq/employees", """
                {"id":"%s","empNo":"%s","name":"합성 %s","field":"ARCH","employmentType":"FULLTIME",
                 "registeredField":true,"hiredOn":"2020-03-02","initialGrade":"INTER","gradeFrom":"2020-03-02"}"""
                .formatted(id, empNo, empNo)), admin, "ADMIN", 201);
        return id;
    }

    @Test
    @DisplayName("HRQ GET /credentials — ALL 역할 전체 · SELF(ENG) 본인만 · 상태 필터 · 잘못된 상태 422")
    void hrqCredentialList() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        UUID e1 = employee("R3A-" + suffix);
        UUID e2 = employee("R3B-" + suffix);
        String c1 = call(postJson("/api/v1/hrq/employees/" + e1 + "/credentials",
                "{\"typeCode\":\"SAFETY_DIAG_CERT\",\"certNo\":\"합성-1\",\"acquiredOn\":\"2026-01-02\",\"expiresOn\":\"2027-01-01\"}"),
                e1, "ENG", 201).get("id").asText();
        String c2 = call(postJson("/api/v1/hrq/employees/" + e2 + "/credentials",
                "{\"typeCode\":\"ARCH_ENG\",\"certNo\":\"합성-2\",\"acquiredOn\":\"2019-06-01\"}"), e2, "ENG", 201)
                .get("id").asText();
        call(postJson("/api/v1/hrq/credentials/" + c2 + ":approve", "{}"), admin, "ADMIN", 200);

        JsonNode all = call(get("/api/v1/hrq/credentials"), admin, "ADMIN", 200);
        assertThat(all.findValuesAsText("id")).contains(c1, c2);
        JsonNode row = null;
        for (JsonNode r : all) {
            if (r.get("id").asText().equals(c1)) {
                row = r;
            }
        }
        assertThat(row.get("employee_name").asText()).isEqualTo("합성 R3A-" + suffix);
        assertThat(row.get("type_name").asText()).contains("예시");
        assertThat(row.get("status").asText()).isEqualTo("PENDING");

        JsonNode pending = call(get("/api/v1/hrq/credentials").param("status", "PENDING"), admin, "QA", 200);
        assertThat(pending.findValuesAsText("id")).contains(c1).doesNotContain(c2);
        // SELF 범위(ENG)는 본인 자격만 — 다른 직원 자격 · 이름 노출 없음
        JsonNode mine = call(get("/api/v1/hrq/credentials"), e1, "ENG", 200);
        assertThat(mine.findValuesAsText("employee_id")).containsOnly(e1.toString());
        assertThat(call(get("/api/v1/hrq/credentials").param("status", "BOGUS"), admin, "ADMIN", 422)
                .get("code").asText()).isEqualTo("HRQ-422-001");
        assertThat(call(get("/api/v1/hrq/credentials"), actor(), "SALES", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }

    @Test
    @DisplayName("HRQ GET /month-close — EXEC · PM · ADMIN 만, SELF 범위(ENG) 403 CORE-403-002, 권한 없음 403 CORE-403-001")
    void hrqMonthCloseList() throws Exception {
        String ym = YearMonth.now(clock.withZone(ZoneId.of("Asia/Seoul"))).minusYears(3)
                .format(DateTimeFormatter.ofPattern("yyyyMM"));
        call(postJson("/api/v1/hrq/month-close/" + ym, "{}"), admin, "ADMIN", 200);
        JsonNode list = call(get("/api/v1/hrq/month-close"), actor(), "PM", 200);
        assertThat(list.findValuesAsText("year_month")).contains(ym);
        assertThat(call(get("/api/v1/hrq/month-close"), actor(), "ENG", 403).get("code").asText())
                .isEqualTo("CORE-403-002");
        assertThat(call(get("/api/v1/hrq/month-close"), actor(), "QA", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }

    @Test
    @DisplayName("CST GET /periods(다음 달까지) · /periods/{ym}/reconciliations · /projects/{id}/evm/snapshots(실원가 마스킹 · 범위)")
    void cstLists() throws Exception {
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
        String next = YearMonth.from(today).plusMonths(1).format(DateTimeFormatter.ofPattern("yyyyMM"));
        JsonNode periods = call(get("/api/v1/cst/periods"), actor(), "FIN", 200);
        for (JsonNode p : periods) {
            assertThat(p.get("period_ym").asText()).isLessThanOrEqualTo(next);
        }
        assertThat(call(get("/api/v1/cst/periods/" + YearMonth.from(today).format(DateTimeFormatter.ofPattern("yyyyMM"))
                + "/reconciliations"), actor(), "AUDITOR", 200).isArray()).isTrue();
        assertThat(call(get("/api/v1/cst/periods"), actor(), "ENG", 403).get("code").asText()).isEqualTo("CORE-403-001");
        assertThat(call(get("/api/v1/cst/periods/2026-13/reconciliations"), actor(), "FIN", 422).get("code").asText())
                .startsWith("CST-422");

        UUID client = createClient(actor(), "합성 R3 고객 " + UUID.randomUUID());
        UUID project = UUID.fromString(call(postJson("/api/v1/core/projects", "{\"name\":\"합성 R3 EVM\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), actor(), "PM", 201).get("id").asText());
        jdbc.update("""
                INSERT INTO cst.evm_snapshot(project_id, as_of, bac, pv, ev, ac, ac_std, cv, sv, cpi, spi, eac, etc, vac, tcpi,
                                             alert_level) VALUES (?, ?, 1000000, 400000, 300000, 360000, 250000, -60000, -100000,
                                             0.833, 0.750, 1200000, 840000, -200000, 1.0, 'YELLOW')""",
                project, java.sql.Date.valueOf(today.minusDays(7)));
        JsonNode real = call(get("/api/v1/cst/projects/" + project + "/evm/snapshots"), actor(), "EXEC", 200);
        assertThat(real).hasSize(1);
        assertThat(real.get(0).get("acBasis").asText()).isEqualTo("REAL");
        assertThat(real.get(0).get("ac").asLong()).isEqualTo(360000);
        assertThat(real.get(0).get("cpi").decimalValue()).isEqualByComparingTo("0.833");
        JsonNode std = call(get("/api/v1/cst/projects/" + project + "/evm/snapshots"), actor(), "FIN", 200);
        assertThat(std.get(0).get("acBasis").asText()).isEqualTo("STANDARD");
        assertThat(std.get(0).get("ac").isNull()).isTrue();
        assertThat(std.get(0).get("cpi").isNull()).isTrue();
        assertThat(std.get(0).get("eac").isNull()).isTrue();
        assertThat(std.get(0).get("acStd").asLong()).isEqualTo(250000);
        assertThat(std.get(0).get("cpiStd").decimalValue()).isEqualByComparingTo("1.200");
        // PM 은 PROJECT 범위 — 투입되지 않은 프로젝트는 거부
        assertThat(call(get("/api/v1/cst/projects/" + project + "/evm/snapshots"), actor(), "PM", 403).get("code").asText())
                .isEqualTo("CORE-403-002");
    }

    @Test
    @DisplayName("FCD GET /imports — 배치 목록(원문 없음), import:read 없는 역할 403")
    void fcdImportList() throws Exception {
        String csv = """
                이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고
                2026.07.01,12:00,999999******0000,77000001,(예시)합성식당,,한식,"9,000",KRW,,
                """;
        var file = new org.springframework.mock.web.MockMultipartFile("file", "r3-list.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
        UUID fin = actor();
        String id = call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/fcd/imports")
                .file(file), fin, "FIN", 201).get("id").asText();
        JsonNode list = call(get("/api/v1/fcd/imports"), actor(), "AUDITOR", 200);
        JsonNode mine = null;
        for (JsonNode b : list) {
            if (b.get("id").asText().equals(id)) {
                mine = b;
            }
        }
        assertThat(mine).isNotNull();
        assertThat(mine.get("filename").asText()).isEqualTo("r3-list.csv");
        assertThat(mine.has("raw")).isFalse();
        assertThat(call(get("/api/v1/fcd/imports"), actor(), "EMPLOYEE", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }

    @Test
    @DisplayName("AIP GET /jobs · /suggestions — ALL 역할 전체, PROJECT 역할은 본인 · 투입 프로젝트 것만(행마다 거름)")
    void aipLists() throws Exception {
        UUID pmA = actor();
        UUID pmB = actor();
        String job = call(postJson("/api/v1/aip/jobs", "{\"serviceCode\":\"KB_SEARCH\",\"priority\":3,"
                + "\"params\":{\"query\":\"합성 균열\"}}").header("Idempotency-Key", UUID.randomUUID().toString()),
                pmA, "PM", 202).get("id").asText();
        assertThat(call(get("/api/v1/aip/jobs"), pmA, "PM", 200).findValuesAsText("id")).contains(job);
        assertThat(call(get("/api/v1/aip/jobs"), pmB, "PM", 200).findValuesAsText("id")).doesNotContain(job);
        JsonNode qa = call(get("/api/v1/aip/jobs").param("serviceCode", "KB_SEARCH"), actor(), "QA", 200);
        assertThat(qa.findValuesAsText("id")).contains(job);
        for (JsonNode j : qa) {
            assertThat(j.get("serviceCode").asText()).isEqualTo("KB_SEARCH");
            assertThat(j.get("requestedBy")).isNotNull();
        }
        assertThat(call(get("/api/v1/aip/jobs").param("status", "NOPE"), actor(), "QA", 422).get("code").asText())
                .startsWith("AIP-422");
        assertThat(call(get("/api/v1/aip/jobs"), actor(), "FIN", 403).get("code").asText()).isEqualTo("CORE-403-001");
        assertThat(call(get("/api/v1/aip/suggestions").param("status", "SUGGESTED"), actor(), "QA", 200).isArray())
                .isTrue();
        assertThat(call(get("/api/v1/aip/suggestions"), actor(), "FIN", 403).get("code").asText())
                .isEqualTo("CORE-403-001");
    }
}
