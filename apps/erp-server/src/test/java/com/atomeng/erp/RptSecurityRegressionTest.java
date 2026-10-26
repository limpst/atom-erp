package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.RptFlow;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 보안 검토 2026-10-19 회귀 시험 — RPT (SEC-RPT-02 · 03 · 04). 모든 데이터는 합성값. */
class RptSecurityRegressionTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    OutboxRelay relay;

    RptFlow flow;

    @BeforeEach
    void setUp() {
        clock.reset();
        flow = new RptFlow(mvc, jdbc, objectMapper, relay);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private UUID clientOf(UUID project) {
        return jdbc.queryForObject("SELECT client_id FROM core.project WHERE id = ?", UUID.class, project);
    }

    private static MockHttpServletRequestBuilder customer(MockHttpServletRequestBuilder b, UUID org) {
        return b.header("X-Client-Org-Id", org.toString());
    }

    private UUID ownedBuilding(UUID clientId) {
        UUID site = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        jdbc.update("INSERT INTO fac.site(id, name, owner_client_id, created_by, updated_by) VALUES (?, ?, ?, ?, ?)",
                site, "합성 단지 SEC", clientId, by, by);
        jdbc.update("INSERT INTO fac.building(id, site_id, name, road_address, structure_nm, status, data_source, "
                + "created_by, updated_by) VALUES (?, ?, '합성 타사 건물', '서울 합성구 보안로 1', '철근콘크리트구조', 'ACTIVE', "
                + "'MANUAL', ?, ?)", b, site, by, by);
        return b;
    }

    @Test
    @DisplayName("SEC-RPT-02 · PTL-01: CUSTOMER 는 내부 RPT API 를 전혀 쓸 수 없다 — 미발행 리비전 · 초안 · 섹션 · 등급 · 원본 PDF, "
            + "발행본까지 403 CORE-403-010(발행본은 포털 /api/v1/ptl 의 워터마크 다운로드로만)")
    void customerSeesOnlyPublished() throws Exception {
        UUID pm = flow.employee("SEC-PM");
        UUID project = flow.project(pm);
        UUID building = UUID.randomUUID();
        JsonNode report = flow.createReport(pm, project, building, "RT_PRC", today().minusDays(10));
        UUID reportId = UUID.fromString(report.get("id").asText());
        UUID rev = UUID.fromString(report.get("currentRevisionId").asText());
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, "C", null));
        UUID org = clientOf(project);
        UUID cust = UUID.randomUUID();

        for (String url : java.util.List.of("/api/v1/rpt/reports?projectId=" + project, "/api/v1/rpt/reports/" + reportId,
                "/api/v1/rpt/revisions/" + rev, "/api/v1/rpt/revisions/" + rev + "/sections",
                "/api/v1/rpt/revisions/" + rev + "/grade", "/api/v1/rpt/revisions/" + rev + "/files/report.pdf",
                "/api/v1/rpt/revisions/" + rev + "/section-editors")) {
            assertThat(flow.call(customer(get(url), org), cust, "CUSTOMER", 403).get("code").asText()).as(url)
                    .isEqualTo("CORE-403-010");
        }
        // 내부 PM 은 그대로 조회
        flow.call(get("/api/v1/rpt/revisions/" + rev), pm, "PM", 200);

        // 발행본도 내부 API 로는 받을 수 없다(워터마크 · 다운로드 감사 없는 원본 경로 차단, PTL-01)
        RptFlow.Published pub = flow.publishGrade(UUID.randomUUID(), "B", "REGULAR", today().minusDays(5));
        UUID org2 = clientOf(pub.projectId());
        flow.call(customer(get("/api/v1/rpt/revisions/" + pub.revisionId()), org2), cust, "CUSTOMER", 403);
        flow.call(customer(get("/api/v1/rpt/revisions/" + pub.revisionId() + "/files/report.pdf"), org2), cust,
                "CUSTOMER", 403);
        flow.call(customer(get("/api/v1/rpt/reports?projectId=" + pub.projectId()), org2), cust, "CUSTOMER", 403);

        // SEC-RPT-03: DXF Content-Disposition 은 RFC 6266 형식(ContentDisposition 으로 파싱 가능)
        var res = mvc.perform(RptFlow.as(get("/api/v1/rpt/revisions/" + pub.revisionId() + "/drawings/B01/1F.dxf"),
                pub.pm(), "PM")).andReturn().getResponse();
        assertThat(res.getStatus()).isEqualTo(200);
        ContentDisposition cd = ContentDisposition.parse(res.getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertThat(cd.isAttachment()).isTrue();
        assertThat(cd.getFilename()).isEqualTo("B01-1F.dxf");
    }

    @Test
    @DisplayName("SEC-RPT-04: 다른 고객사 소유 시설물로 보고서 생성 · 스냅샷 동결 → 422 (발행 시 타사 건물 등급 기록 차단)")
    void foreignBuildingRejected() throws Exception {
        UUID pm = flow.employee("SEC-PM");
        UUID project = flow.project(pm);
        UUID otherClient = UUID.fromString(flow.call(RptFlow.postJson("/api/v1/core/clients",
                "{\"name\":\"합성타사-" + UUID.randomUUID() + "\",\"clientType\":\"PRIVATE\"}"), UUID.randomUUID(), "SALES", 201)
                .get("id").asText());
        UUID foreign = ownedBuilding(otherClient);
        UUID own = ownedBuilding(clientOf(project));

        JsonNode denied = flow.call(RptFlow.postJson("/api/v1/rpt/reports", """
                {"projectId":"%s","facilityId":"%s","reportType":"RT_PRC","title":"합성","inspectionDate":"%s"}"""
                .formatted(project, foreign, today().minusDays(3))), pm, "PM", 422);
        assertThat(denied.get("code").asText()).isEqualTo("RPT-422-001");

        JsonNode report = flow.createReport(pm, project, own, "RT_PRC", today().minusDays(3));
        UUID reportId = UUID.fromString(report.get("id").asText());
        flow.call(RptFlow.postJson("/api/v1/rpt/reports/" + reportId + "/snapshots",
                RptFlow.snapshotBody(foreign, "C", null)), pm, "PM", 422);
        // 자기 발주처 시설물은 정상
        assertThat(flow.freeze(pm, reportId, RptFlow.snapshotBody(own, "C", null)).get("snapshotVer").asInt()).isEqualTo(1);
    }
}
