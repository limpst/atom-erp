package com.atomeng.erp;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.identity.authz.ScopeResolver.ScopeTarget;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.ProbeScopeResolver;
import com.atomeng.erp.support.RptFlow;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 보안 검토 CMN-05 — RequirePermissionAspect 가 실패하는 쪽으로 닫혔는지(호출 시) 시험한다.
 * 프로브 엔드포인트(support/AuthzProbeController) · 합성 데이터만 쓴다.
 */
class PermissionFailClosedTest extends IntegrationTestBase {

    RptFlow flow;
    UUID pm;
    UUID project;
    UUID otherProject;
    UUID client;

    @BeforeEach
    void setUp() throws Exception {
        flow = new RptFlow(mvc, jdbc, objectMapper, null);
        pm = flow.employee("CMN05-PM");
        project = flow.project(pm);
        otherProject = flow.project(flow.employee("CMN05-PM2"));
        client = jdbc.queryForObject("SELECT client_id FROM core.project WHERE id = ?", UUID.class, project);
    }

    @Test
    @DisplayName("CMN-05: 범위 판정 수단이 없는 엔드포인트 — PROJECT · CLIENT 범위는 403 CORE-403-002, ALL 은 통과")
    void undeclaredFailsClosed() throws Exception {
        String url = "/api/v1/test/cmn05/undeclared/" + UUID.randomUUID();
        mvc.perform(as(get(url), pm, "PM")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"));
        // CLIENT 범위(포털 권한 ptl:share:read — PTL-01 이후 CUSTOMER 의 유일한 권한군)도 판정 수단 없으면 403
        mvc.perform(as(get("/api/v1/ptl/test/cmn05/undeclared/" + UUID.randomUUID()), UUID.randomUUID(), "CUSTOMER")
                        .header("X-Client-Org-Id", client.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        // PTL-01: 포털 주체는 /api/v1/ptl 밖이면 권한 판정 전에 403 CORE-403-010
        mvc.perform(as(get(url), UUID.randomUUID(), "CUSTOMER").header("X-Client-Org-Id", client.toString()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-010"));
        mvc.perform(as(get(url), UUID.randomUUID(), "EXEC")).andExpect(status().isOk());
        // 서비스 검사를 명시한 엔드포인트는 aspect 가 역할만 본다
        mvc.perform(as(get("/api/v1/test/cmn05/service-checked/" + UUID.randomUUID()), pm, "PM"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CMN-05: scopeResolver — PROJECT 는 투입 프로젝트만, CLIENT 는 발주 고객만, 없는 자원은 404")
    void resolverProjectAndClient() throws Exception {
        UUID mine = UUID.randomUUID();
        UUID theirs = UUID.randomUUID();
        ProbeScopeResolver.TARGETS.put(mine, ScopeTarget.project(project));
        ProbeScopeResolver.TARGETS.put(theirs, ScopeTarget.project(otherProject));
        mvc.perform(as(get("/api/v1/test/cmn05/reports/" + mine), pm, "PM")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/test/cmn05/reports/" + theirs), pm, "PM")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(get("/api/v1/ptl/test/cmn05/reports/" + mine), UUID.randomUUID(), "CUSTOMER")
                .header("X-Client-Org-Id", client.toString())).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/ptl/test/cmn05/reports/" + theirs), UUID.randomUUID(), "CUSTOMER")
                .header("X-Client-Org-Id", client.toString())).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(get("/api/v1/test/cmn05/reports/" + UUID.randomUUID()), pm, "PM"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CORE-404-001"));
        // 프로젝트 없는 자원: PROJECT 범위는 소유자 본인만
        UUID orphan = UUID.randomUUID();
        ProbeScopeResolver.TARGETS.put(orphan, ScopeTarget.owner(pm));
        mvc.perform(as(get("/api/v1/test/cmn05/reports/" + orphan), pm, "PM")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/test/cmn05/reports/" + orphan), flow.employee("CMN05-X"), "PM"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("CMN-05: SELF 범위 — 해석기의 소유 직원만 통과, projectIdParam 만으로는 SELF 를 판정할 수 없어 403")
    void selfScope() throws Exception {
        UUID eng = flow.employee("CMN05-ENG");
        UUID own = UUID.randomUUID();
        UUID others = UUID.randomUUID();
        ProbeScopeResolver.TARGETS.put(own, ScopeTarget.owner(eng));
        ProbeScopeResolver.TARGETS.put(others, ScopeTarget.owner(pm));
        mvc.perform(as(get("/api/v1/test/cmn05/timesheets/" + own), eng, "ENG")).andExpect(status().isOk());
        mvc.perform(as(get("/api/v1/test/cmn05/timesheets/" + others), eng, "ENG")).andExpect(status().isForbidden());
        flow.member(project, eng, "PART");
        mvc.perform(as(get("/api/v1/test/cmn05/projects/" + project + "/timesheets"), eng, "ENG"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
    }
}
