package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.erp.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 설계 8장 RBAC + 행 수준 권한(◐) — TC-CORE-001 */
class AuthorizationIntegrationTest extends IntegrationTestBase {

    UUID engineer;     // 합성 직원 (ENG)
    UUID clientOrg;
    UUID assignedProject;
    UUID otherProject;

    @BeforeEach
    void setUp() throws Exception {
        UUID sales = actor();
        clientOrg = createClient(sales, "합성발주처-authz-" + UUID.randomUUID());
        assignedProject = createProject(clientOrg, "합성 담당 프로젝트");
        otherProject = createProject(createClient(sales, "합성발주처-타사-" + UUID.randomUUID()), "합성 담당 외 프로젝트");

        engineer = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by)
                VALUES (?, ?, '합성기술자', DATE '2024-03-01', ?, ?)""",
                engineer, "E-" + engineer.toString().substring(0, 8), engineer, engineer);
        LocalDate today = LocalDate.now();
        jdbc.update("""
                INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date)
                VALUES (?, ?, 'PART', ?, ?)""", assignedProject, engineer, today.minusDays(7), today.plusDays(30));
    }

    private UUID createProject(UUID clientId, String name) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/core/projects",
                "{\"name\":\"" + name + "\",\"serviceType\":\"PRECISE_INSP\",\"clientId\":\"" + clientId + "\"}"),
                actor(), "SALES")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private int auditCount() {
        return jdbc.queryForObject("SELECT count(*) FROM core.audit_log", Integer.class);
    }

    @Test
    @DisplayName("TC-CORE-001: ENG 가 담당 외 프로젝트 조회 → 403 CORE-403-002, 감사 로그 없음")
    void tcCore001_engineerOutsideProjectScope() throws Exception {
        int before = auditCount();
        mvc.perform(as(get("/api/v1/core/projects/" + otherProject), engineer, "ENG"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"))
                .andExpect(jsonPath("$.type").value("https://erp.atom-eng.co.kr/problems/core-403-002"));
        assertThat(auditCount()).isEqualTo(before);

        // 담당 프로젝트는 조회 가능
        mvc.perform(as(get("/api/v1/core/projects/" + assignedProject), engineer, "ENG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(assignedProject.toString()));
    }

    @Test
    @DisplayName("프로젝트 목록(웹): ENG 는 투입 중인 프로젝트만, ALL 범위(EXEC)는 전체, 투입 없는 PM 은 빈 목록")
    void projectListIsScoped() throws Exception {
        var mine = json(mvc.perform(as(get("/api/v1/core/projects"), engineer, "ENG"))
                .andExpect(status().isOk()).andReturn());
        assertThat(mine.findValuesAsText("id")).containsExactly(assignedProject.toString());
        var all = json(mvc.perform(as(get("/api/v1/core/projects"), actor(), "EXEC"))
                .andExpect(status().isOk()).andReturn());
        assertThat(all.findValuesAsText("id")).contains(assignedProject.toString(), otherProject.toString());
        mvc.perform(as(get("/api/v1/core/projects"), actor(), "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(as(get("/api/v1/core/projects"), actor(), "LABELER"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-001"));
        // 고객 목록(웹 선택용): core:client:read 보유 역할만
        var clients = json(mvc.perform(as(get("/api/v1/core/clients"), actor(), "SALES"))
                .andExpect(status().isOk()).andReturn());
        assertThat(clients.findValuesAsText("id")).contains(clientOrg.toString());
        mvc.perform(as(get("/api/v1/core/clients"), engineer, "ENG")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("투입 기간이 끝난 ENG 는 같은 프로젝트라도 403 CORE-403-002")
    void expiredAssignmentIsOutOfScope() throws Exception {
        jdbc.update("UPDATE core.project_member SET from_date = ?, to_date = ? WHERE employee_id = ?",
                LocalDate.now().minusDays(60), LocalDate.now().minusDays(1), engineer);
        mvc.perform(as(get("/api/v1/core/projects/" + assignedProject), engineer, "ENG"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"));
    }

    @Test
    @DisplayName("설계 8.2: QA·AUDITOR(●)는 모든 프로젝트 조회, EMPLOYEE 는 권한 없음 403 CORE-403-001")
    void allScopeAndNoPermission() throws Exception {
        mvc.perform(as(get("/api/v1/core/projects/" + otherProject), actor(), "QA")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/core/projects/" + otherProject), actor(), "AUDITOR")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/core/projects/" + otherProject), actor(), "EMPLOYEE"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-001"));
    }

    @Test
    @DisplayName("설계 8.2 v0.3: AUDITOR 는 조회만, 업무 데이터 생성 불가(직무 분리) → 403 CORE-403-001")
    void auditorCannotWrite() throws Exception {
        mvc.perform(as(postJson("/api/v1/core/clients", "{\"name\":\"합성\",\"clientType\":\"PRIVATE\"}"),
                        actor(), "AUDITOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-001"));
        mvc.perform(as(postJson("/api/v1/core/clients", "{\"name\":\"합성\",\"clientType\":\"PRIVATE\"}"),
                        actor(), "FIN"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PTL-01: CUSTOMER(포털)는 내부 core API 를 쓸 수 없다 — 자기 고객사 프로젝트도 403 CORE-403-010(포털 API 만)")
    void customerClientScope() throws Exception {
        mvc.perform(as(get("/api/v1/core/projects/" + assignedProject), actor(), "CUSTOMER")
                        .header("X-Client-Org-Id", clientOrg.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-010"));
        mvc.perform(as(get("/api/v1/core/projects/" + otherProject), actor(), "CUSTOMER")
                        .header("X-Client-Org-Id", clientOrg.toString()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-010"));
    }

    @Test
    @DisplayName("인증 없음 → 401 CORE-401-001")
    void unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/core/projects/" + assignedProject))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CORE-401-001"));
    }
}
