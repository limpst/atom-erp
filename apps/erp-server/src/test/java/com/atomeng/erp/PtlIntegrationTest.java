package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.sal.api.SalEvents;
import com.atomeng.erp.support.IntegrationTestBase;
import com.atomeng.erp.support.RptFlow;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 09 PTL 고객 포털 통합 테스트 — 고객사 A · B 두 곳의 합성 데이터(시설물 · 점검 도래 · 실제 RPT 흐름으로 발행 · 납품된 보고서 ·
 * 서비스 요청 · 알림 · 공유 영업 문서)를 만들고:
 * <ul>
 *   <li>TC-PTL-01 교차 고객 접근 거부 매트릭스 — 모든 단건 엔드포인트에 상대 고객 id → 404(존재 은닉) + DENIED 기록,
 *       모든 목록 엔드포인트에 상대 고객 id 0건, 양방향(A→B, B→A)</li>
 *   <li>TC-PTL-02(API) 포털 고객이 아닌 호출(내부 역할 · cid 없는 CUSTOMER · 무인증) → 403 PTL-403-001</li>
 *   <li>TC-PTL-03 발행만 된(납품 전) 보고서는 비공개 → 납품 확인 후 공개</li>
 *   <li>TC-PTL-04 워터마크 다운로드(RPT 스텁 PDF): 모든 쪽 문구 · 메타데이터 · 다운로드 감사 · core 감사</li>
 *   <li>TC-PTL-05 시설물 목록 · 일정은 소유 고객사 범위</li>
 *   <li>TC-PTL-08 정정본 → 이전본 SUPERSEDED 배너 · 정정 알림 · 다운로드 409, 새 판 납품 후 공개</li>
 *   <li>서비스 요청 → ptl.service-request.submitted → SAL PORTAL 리드(실제 Relay), FAC · SAL 이벤트 → 알림 · 공유 문서</li>
 * </ul>
 * 모든 데이터는 합성값.
 */
class PtlIntegrationTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));

    @Autowired
    OutboxRelay relay;
    @Autowired
    OutboxPublisher outbox;
    @Autowired
    TransactionTemplate tx;

    RptFlow flow;

    /** 고객사 1곳의 합성 자원 */
    record Tenant(UUID clientId, UUID actor, UUID building, UUID projectId, UUID pm, UUID reportId, UUID revisionId,
                  UUID publishedReportId, UUID serviceRequestId, UUID notificationId, UUID salesDocumentId) {}

    @BeforeEach
    void setUp() {
        clock.reset();
        flow = new RptFlow(mvc, jdbc, objectMapper, relay);
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder b, Tenant t) {
        return b.header("X-Actor-Id", t.actor().toString()).header("X-Roles", "CUSTOMER")
                .header("X-Client-Org-Id", t.clientId().toString());
    }

    private void emit(Object event) {
        tx.executeWithoutResult(s -> outbox.publish((com.atomeng.common.events.DomainEvent) event));
    }

    // ------------------------------------------------------------------ 합성 기준 데이터

    /** 고객사 소유 시설물(ACTIVE) + 진행 중 점검 도래(D+20 → RED) */
    private UUID ownedBuilding(UUID clientId, String name, int dueInDays) {
        UUID site = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        jdbc.update("INSERT INTO fac.site(id, name, owner_client_id, created_by, updated_by) VALUES (?, ?, ?, ?, ?)",
                site, "합성 단지 " + name, clientId, by, by);
        jdbc.update("INSERT INTO fac.building(id, site_id, name, road_address, structure_nm, status, data_source, "
                + "created_by, updated_by) VALUES (?, ?, ?, '서울 합성구 포털로 1', '철근콘크리트구조', 'ACTIVE', 'MANUAL', ?, ?)",
                b, site, name, by, by);
        UUID rule = jdbc.queryForObject("SELECT id FROM fac.inspection_cycle_rule ORDER BY id LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO fac.inspection_due(id, building_id, inspection_type, due_date, rule_id, calc_trace) "
                + "VALUES (?, ?, 'REGULAR', ?, ?, '{}'::jsonb)", UUID.randomUUID(), b,
                java.sql.Date.valueOf(today().plusDays(dueInDays)), rule);
        return b;
    }

    private UUID project(UUID clientId, UUID pm) throws Exception {
        JsonNode p = flow.call(RptFlow.postJson("/api/v1/core/projects", "{\"name\":\"합성 포털 점검 프로젝트\","
                + "\"serviceType\":\"PRECISE_DIAG\",\"clientId\":\"" + clientId + "\"}"), pm, "PM", 201);
        UUID id = UUID.fromString(p.get("id").asText());
        flow.member(id, pm, "RESP");
        return id;
    }

    /** 실제 RPT 흐름: 생성 → 동결 → compose → 확정 → 렌더 → 검토 → QAR 승인 → 발행 (납품 확인 전) */
    private UUID[] publishedReport(UUID pm, UUID project, UUID building, String grade) throws Exception {
        JsonNode report = flow.createReport(pm, project, building, "RT_REG", today().minusDays(15));
        UUID reportId = UUID.fromString(report.get("id").asText());
        UUID rev = UUID.fromString(report.get("currentRevisionId").asText());
        flow.freeze(pm, reportId, RptFlow.snapshotBody(building, grade, null));
        flow.composeToPublish(pm, rev);
        return new UUID[] {reportId, rev};
    }

    private void deliver(UUID pm, UUID rev) throws Exception {
        flow.call(RptFlow.postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation",
                "{\"recipientName\":\"합성 고객 담당자\"}"), pm, "PM", 201);
        flow.drain();
    }

    private UUID opportunity(UUID clientId) {
        UUID id = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        jdbc.update("INSERT INTO sal.opportunity(id, opp_no, source, client_id, service_type, title, stage, created_by, "
                + "updated_by) VALUES (?, ?, 'INQUIRY', ?, 'REGULAR_INSP', '합성 포털 견적 기회', 'LEAD', ?, ?)",
                id, "OPP-PT-" + SEQ.incrementAndGet(), clientId, by, by);
        return id;
    }

    private Tenant tenant(String label, String grade) throws Exception {
        UUID client = createClient(actor(), "합성고객-ptl-" + label + "-" + UUID.randomUUID());
        Tenant t0 = new Tenant(client, UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        UUID building = ownedBuilding(client, "합성 " + label + " 사옥", 20);
        UUID pm = flow.employee("PTL-PM");
        UUID project = project(client, pm);
        UUID[] rep = publishedReport(pm, project, building, grade);
        flow.drain();
        deliver(pm, rep[1]);
        UUID published = jdbc.queryForObject("SELECT id FROM ptl.published_report WHERE revision_id = ?", UUID.class,
                rep[1]);
        Tenant t = new Tenant(client, t0.actor(), building, project, pm, rep[0], rep[1], published, null, null, null);
        // 서비스 요청(견적)
        JsonNode sr = json(mvc.perform(asCustomer(RptFlow.postJson("/api/v1/ptl/service-requests", """
                {"kind":"QUOTE","facilityId":"%s","serviceType":"PRECISE_INSP","description":"합성 %s 정밀점검 견적 요청"}"""
                .formatted(building, label)), t)).andExpect(status().isCreated()).andReturn());
        UUID req = UUID.fromString(sr.get("id").asText());
        // SAL 계약 서명 요청(sal.contract.ready-to-sign) → 공유 문서 + 알림
        UUID contract = UUID.randomUUID();
        emit(new SalEvents.ContractReadyToSign(contract, client, null, new BigDecimal("13200000"),
                today().plusDays(30), List.of("대표이사")));
        flow.drain();
        UUID noti = jdbc.queryForObject("SELECT id FROM ptl.notification WHERE customer_id = ? AND kind = 'REPORT_DELIVERED'",
                UUID.class, client);
        UUID doc = jdbc.queryForObject("SELECT id FROM ptl.sales_document WHERE customer_id = ? AND sal_ref_id = ?",
                UUID.class, client, contract);
        return new Tenant(client, t0.actor(), building, project, pm, rep[0], rep[1], published, req, noti, doc);
    }

    private List<String> ids(Tenant t) {
        List<String> out = new ArrayList<>();
        for (UUID u : List.of(t.clientId(), t.building(), t.reportId(), t.revisionId(), t.publishedReportId(),
                t.serviceRequestId(), t.notificationId(), t.salesDocumentId())) {
            out.add(u.toString());
        }
        return out;
    }

    private long denied(UUID clientId, UUID target) {
        return jdbc.queryForObject("SELECT count(*) FROM ptl.access_log WHERE customer_id = ? AND action = 'DENIED' "
                + "AND target_id = ?", Long.class, clientId, target);
    }

    // ------------------------------------------------------------------ 시험

    @Test
    @DisplayName("TC-PTL-01 · 05: 교차 고객 접근 거부 매트릭스 — 단건 9종 404(+DENIED 기록), 목록 7종 상대 id 0건, 양방향")
    void crossTenantMatrix() throws Exception {
        Tenant a = tenant("A", "B");
        Tenant b = tenant("B", "C");
        for (Tenant[] pair : List.of(new Tenant[] {a, b}, new Tenant[] {b, a})) {
            Tenant me = pair[0];
            Tenant other = pair[1];
            // 단건: 상대 고객 자원 → 404 PTL-404-001 (403 이 아니라 존재 은닉)
            List<MockHttpServletRequestBuilder> single = List.of(
                    get("/api/v1/ptl/buildings/" + other.building()),
                    get("/api/v1/ptl/buildings/" + other.building() + "/grades"),
                    get("/api/v1/ptl/buildings/" + other.building() + "/inspection-schedule"),
                    get("/api/v1/ptl/reports/" + other.publishedReportId()),
                    post("/api/v1/ptl/reports/" + other.publishedReportId() + ":download"),
                    get("/api/v1/ptl/service-requests/" + other.serviceRequestId()),
                    post("/api/v1/ptl/notifications/" + other.notificationId() + ":read"),
                    RptFlow.postJson("/api/v1/ptl/service-requests",
                            "{\"kind\":\"QUOTE\",\"facilityId\":\"" + other.building() + "\",\"description\":\"위장 요청\"}"),
                    get("/api/v1/ptl/reports/" + other.revisionId()));   // 리비전 id 로 찔러도 404
            for (MockHttpServletRequestBuilder req : single) {
                MvcResult r = mvc.perform(asCustomer(req, me)).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s", r.getRequest().getRequestURI()).isEqualTo(404);
                assertThat(json(r).get("code").asText()).isEqualTo("PTL-404-001");
                String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
                assertThat(body).doesNotContain(other.clientId().toString());
            }
            assertThat(denied(me.clientId(), other.building())).isGreaterThanOrEqualTo(4);
            assertThat(denied(me.clientId(), other.publishedReportId())).isEqualTo(2);
            assertThat(denied(me.clientId(), other.serviceRequestId())).isEqualTo(1);
            assertThat(denied(me.clientId(), other.notificationId())).isEqualTo(1);
            // 상대 알림은 여전히 안 읽음, 위장 요청은 저장 · 발행되지 않음
            assertThat(jdbc.queryForObject("SELECT read_at IS NULL FROM ptl.notification WHERE id = ?", Boolean.class,
                    other.notificationId())).isTrue();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ptl.service_request WHERE customer_id = ?", Long.class,
                    me.clientId())).isEqualTo(1);
            // 목록: 내 자원은 보이고 상대 자원 id 는 하나도 없다
            for (String url : List.of("/api/v1/ptl/buildings", "/api/v1/ptl/inspection-schedule", "/api/v1/ptl/reports",
                    "/api/v1/ptl/downloads", "/api/v1/ptl/service-requests", "/api/v1/ptl/notifications",
                    "/api/v1/ptl/sales-documents", "/api/v1/ptl/me")) {
                String body = mvc.perform(asCustomer(get(url), me)).andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
                for (String foreign : ids(other)) {
                    assertThat(body).as("%s leaks %s", url, foreign).doesNotContain(foreign);
                }
            }
            JsonNode buildings = json(mvc.perform(asCustomer(get("/api/v1/ptl/buildings"), me)).andReturn());
            assertThat(buildings).hasSize(1);
            assertThat(buildings.get(0).get("id").asText()).isEqualTo(me.building().toString());
            assertThat(buildings.get(0).get("currentGrade").asText()).isEqualTo(me == a ? "B" : "C");   // RPT 발행 → FAC 등급
            JsonNode reports = json(mvc.perform(asCustomer(get("/api/v1/ptl/reports"), me)).andReturn());
            assertThat(reports).hasSize(1);
            assertThat(reports.get(0).get("id").asText()).isEqualTo(me.publishedReportId().toString());
            JsonNode docs = json(mvc.perform(asCustomer(get("/api/v1/ptl/sales-documents"), me)).andReturn());
            assertThat(docs).hasSize(1);
            assertThat(docs.get(0).get("status").asText()).isEqualTo("AWAITING_SIGNATURE");
        }
        // 자기 알림 읽음 처리는 204
        mvc.perform(asCustomer(post("/api/v1/ptl/notifications/" + a.notificationId() + ":read"), a))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT read_at IS NOT NULL FROM ptl.notification WHERE id = ?", Boolean.class,
                a.notificationId())).isTrue();
    }

    @Test
    @DisplayName("TC-PTL-02(API): 내부 역할(ptl:share 권한 보유 포함) · cid 없는 CUSTOMER → 403 PTL-403-001, 무인증 → 401")
    void nonCustomersAreRejected() throws Exception {
        UUID client = createClient(actor(), "합성고객-ptl-403-" + UUID.randomUUID());
        List<String> urls = List.of("/api/v1/ptl/me", "/api/v1/ptl/buildings", "/api/v1/ptl/reports",
                "/api/v1/ptl/service-requests", "/api/v1/ptl/notifications", "/api/v1/ptl/inspection-schedule");
        for (String url : urls) {
            for (String role : List.of("SALES", "PM", "ADMIN", "EXEC")) {
                MvcResult r = mvc.perform(get(url).header("X-Actor-Id", UUID.randomUUID().toString())
                        .header("X-Roles", role).header("X-Client-Org-Id", client.toString())).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s %s", role, url).isEqualTo(403);
            }
            mvc.perform(get(url).header("X-Actor-Id", UUID.randomUUID().toString()).header("X-Roles", "CUSTOMER"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("PTL-403-001"));
            mvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/v1/ptl/me").header("X-Actor-Id", UUID.randomUUID().toString()).header("X-Roles", "SALES")
                .header("X-Client-Org-Id", client.toString()))
                .andExpect(jsonPath("$.code").value("PTL-403-001"));
    }

    @Test
    @DisplayName("TC-PTL-03 · 04: 발행만 된 보고서는 비공개 → 납품 확인 후 공개, 워터마크 다운로드(모든 쪽 · 메타데이터 · 감사)")
    void deliveredReportDownloadWithWatermark() throws Exception {
        UUID client = createClient(actor(), "합성고객-ptl-dl-" + UUID.randomUUID());
        Tenant t = new Tenant(client, UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        UUID building = ownedBuilding(client, "합성 다운로드 사옥", 200);
        UUID pm = flow.employee("PTL-PM");
        UUID project = project(client, pm);
        UUID[] rep = publishedReport(pm, project, building, "B");
        flow.drain();
        // TC-PTL-03: QAR 승인 + 발행까지 됐어도 납품 전에는 고객에게 보이지 않는다
        assertThat(json(mvc.perform(asCustomer(get("/api/v1/ptl/reports"), t)).andReturn())).isEmpty();
        deliver(pm, rep[1]);
        JsonNode list = json(mvc.perform(asCustomer(get("/api/v1/ptl/reports"), t)).andExpect(status().isOk()).andReturn());
        assertThat(list).hasSize(1);
        JsonNode r0 = list.get(0);
        assertThat(r0.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(r0.get("safetyGrade").asText()).isEqualTo("B");
        assertThat(r0.get("downloadable").asBoolean()).isTrue();
        assertThat(r0.get("banner").isNull()).isTrue();
        UUID pub = UUID.fromString(r0.get("id").asText());
        // 납품 알림 + FAC 등급 알림(rpt.report.published → FAC 등급 기록 → fac.grade.recorded)
        JsonNode notis = json(mvc.perform(asCustomer(get("/api/v1/ptl/notifications?unread=true"), t)).andReturn());
        List<String> kinds = new ArrayList<>();
        notis.forEach(n -> kinds.add(n.get("kind").asText()));
        assertThat(kinds).contains("REPORT_DELIVERED", "GRADE_RECORDED");
        // 재배달(같은 이벤트 다른 처리)에도 알림 1건
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptl.notification WHERE customer_id = ? AND kind = "
                + "'REPORT_DELIVERED'", Long.class, client)).isEqualTo(1);

        MvcResult d = mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + pub + ":download"), t)
                .with(req -> {
                    req.setRemoteAddr("203.0.113.45");
                    return req;
                })).andExpect(status().isOk()).andReturn();
        assertThat(d.getResponse().getContentType()).isEqualTo("application/pdf");
        byte[] pdf = d.getResponse().getContentAsByteArray();
        String downloadId = d.getResponse().getHeader("X-Download-Id");
        assertThat(d.getResponse().getHeader("X-Content-SHA256")).isEqualTo(Hashing.sha256Hex(pdf));
        String sourceSha = jdbc.queryForObject("SELECT pdf_sha256 FROM rpt.report_revision WHERE id = ?", String.class,
                rep[1]).strip();
        assertThat(Hashing.sha256Hex(pdf)).isNotEqualTo(sourceSha);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isGreaterThanOrEqualTo(2);
            assertThat(doc.getCurrentAccessPermission().canExtractContent()).isFalse();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                assertThat(stripper.getText(doc)).as("page %d", p).contains("download-id " + downloadId)
                        .contains("customer " + client).contains("ATOM-ERP RPT STUB PDF page " + p);
            }
            assertThat(doc.getDocumentInformation().getCustomMetadataValue("AtomDownloadId")).isEqualTo(downloadId);
            assertThat(doc.getDocumentInformation().getCustomMetadataValue("AtomCustomerId")).isEqualTo(client.toString());
            assertThat(doc.getDocumentInformation().getCustomMetadataValue("AtomDownloadedAt")).isNotBlank();
        }
        // 다운로드 감사(append-only) · 접근 로그 · core 감사(EXPORT)
        var audit = jdbc.queryForMap("SELECT * FROM ptl.report_download WHERE id = ?::uuid", downloadId);
        assertThat(audit.get("customer_id")).isEqualTo(client);
        assertThat(audit.get("published_report_id")).isEqualTo(pub);
        assertThat(audit.get("output_sha256").toString()).isEqualTo(Hashing.sha256Hex(pdf));
        assertThat(audit.get("source_sha256").toString()).isEqualTo(sourceSha);
        assertThat(audit.get("client_ip")).isEqualTo("203.0.x.x");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptl.access_log WHERE customer_id = ? AND action = 'DOWNLOAD'",
                Long.class, client)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'PTL' AND action = 'EXPORT' "
                + "AND target_id = ?", Long.class, pub)).isEqualTo(1);
        JsonNode downloads = json(mvc.perform(asCustomer(get("/api/v1/ptl/downloads"), t)).andReturn());
        assertThat(downloads).hasSize(1);
        assertThat(downloads.get(0).get("downloadId").asText()).isEqualTo(downloadId);
        // 두 번째 다운로드 → 다른 download id · 다른 해시
        MvcResult d2 = mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + pub + ":download"), t))
                .andExpect(status().isOk()).andReturn();
        assertThat(d2.getResponse().getHeader("X-Download-Id")).isNotEqualTo(downloadId);
        assertThat(d2.getResponse().getHeader("X-Content-SHA256")).isNotEqualTo(Hashing.sha256Hex(pdf));
        // 보안 검토 CMN-09: Idempotency-Key 를 붙인 다운로드 재전송도 매번 PDF 본문(바이너리는 멱등 저장 · 빈 200 재반환 안 함)
        String dlKey = UUID.randomUUID().toString();
        for (int i = 0; i < 2; i++) {
            MvcResult k = mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + pub + ":download"), t)
                    .header("Idempotency-Key", dlKey)).andExpect(status().isOk()).andReturn();
            assertThat(k.getResponse().getContentType()).isEqualTo("application/pdf");
            assertThat(k.getResponse().getContentAsByteArray()).isNotEmpty();
            assertThat(k.getResponse().getHeader("Idempotent-Replayed")).isNull();
        }
    }

    @Test
    @DisplayName("TC-PTL-08 BR-PTL-012: 정정본 발행 → 이전 공개본 SUPERSEDED 배너 · 정정 알림 · 다운로드 409, 새 판 납품 후 공개")
    void supersededReportShowsBanner() throws Exception {
        UUID client = createClient(actor(), "합성고객-ptl-sup-" + UUID.randomUUID());
        Tenant t = new Tenant(client, UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        UUID building = ownedBuilding(client, "합성 정정 사옥", 120);
        UUID pm = flow.employee("PTL-PM");
        UUID project = project(client, pm);
        UUID[] rep = publishedReport(pm, project, building, "B");
        flow.drain();
        deliver(pm, rep[1]);
        UUID pub1 = jdbc.queryForObject("SELECT id FROM ptl.published_report WHERE revision_id = ?", UUID.class, rep[1]);
        JsonNode rev2 = flow.call(RptFlow.postJson("/api/v1/rpt/reports/" + rep[0] + "/revisions",
                "{\"changeNote\":\"균열폭 측정 오기 정정(합성)\",\"reuseSnapshot\":true}"), pm, "PM", 201);
        UUID rev2Id = UUID.fromString(rev2.get("id").asText());
        flow.freeze(pm, rep[0], RptFlow.snapshotBody(building, "C", null));
        flow.composeToPublish(pm, rev2Id);
        flow.drain();

        JsonNode old = json(mvc.perform(asCustomer(get("/api/v1/ptl/reports/" + pub1), t)).andExpect(status().isOk())
                .andReturn());
        assertThat(old.get("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(old.get("banner").asText()).contains("정정본으로 대체");
        assertThat(old.get("supersededByRevisionId").asText()).isEqualTo(rev2Id.toString());
        assertThat(old.get("supersededReason").asText()).contains("정정");
        assertThat(old.get("downloadable").asBoolean()).isFalse();
        mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + pub1 + ":download"), t))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PTL-409-001"))
                .andExpect(jsonPath("$.supersededByRevisionId").value(rev2Id.toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptl.notification WHERE customer_id = ? AND kind = "
                + "'REPORT_SUPERSEDED'", Long.class, client)).isEqualTo(1);
        // 새 판은 납품 전 비공개 → 납품 후 공개(이전본은 배너와 함께 열람만)
        assertThat(json(mvc.perform(asCustomer(get("/api/v1/ptl/reports"), t)).andReturn())).hasSize(1);
        deliver(pm, rev2Id);
        JsonNode list = json(mvc.perform(asCustomer(get("/api/v1/ptl/reports"), t)).andReturn());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("revNo").asInt()).isEqualTo(2);
        assertThat(list.get(0).get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(list.get(0).get("safetyGrade").asText()).isEqualTo("C");
        assertThat(list.get(1).get("status").asText()).isEqualTo("SUPERSEDED");
        UUID pub2 = UUID.fromString(list.get(0).get("id").asText());
        mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + pub2 + ":download"), t)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("서비스 요청 → ptl.service-request.submitted → 실제 Relay → SAL PORTAL 리드(고객사 · 시설물), 긴급점검 SLA 2시간 · 사유 필수")
    void serviceRequestCreatesSalLead() throws Exception {
        UUID client = createClient(actor(), "합성고객-ptl-sr-" + UUID.randomUUID());
        Tenant t = new Tenant(client, UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        UUID building = ownedBuilding(client, "합성 요청 사옥", 60);
        JsonNode sr = json(mvc.perform(asCustomer(RptFlow.postJson("/api/v1/ptl/service-requests", """
                {"kind":"EMERGENCY","facilityId":"%s","reasonCode":"CRACK_GROWTH",
                 "description":"합성: 2층 복도 보 하부 균열 확대"}""".formatted(building)), t))
                .andExpect(status().isCreated()).andReturn());
        UUID id = UUID.fromString(sr.get("id").asText());
        assertThat(sr.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(sr.get("serviceType").asText()).isEqualTo("EMERGENCY_INSP");
        java.time.Instant created = java.time.Instant.parse(sr.get("createdAt").asText());
        assertThat(java.time.Instant.parse(sr.get("slaDueAt").asText())).isEqualTo(created.plus(java.time.Duration.ofHours(2)));
        JsonNode ev = objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event WHERE "
                + "event_type = 'ptl.service-request.submitted' AND aggregate_id = ?", String.class, id));
        assertThat(ev.get("clientId").asText()).isEqualTo(client.toString());
        assertThat(ev.get("message").asText()).startsWith("[EMERGENCY/CRACK_GROWTH]");
        flow.drain();
        var opp = jdbc.queryForMap("SELECT source, client_id, facility_id, service_type, stage FROM sal.opportunity "
                + "WHERE source = 'PORTAL' AND source_ref = ?", id.toString());
        assertThat(opp).containsEntry("source", "PORTAL").containsEntry("client_id", client)
                .containsEntry("facility_id", building).containsEntry("service_type", "EMERGENCY_INSP")
                .containsEntry("stage", "LEAD");
        // 긴급점검 사유 없음 → 422, 알 수 없는 종류 → 400(검증)
        mvc.perform(asCustomer(RptFlow.postJson("/api/v1/ptl/service-requests",
                "{\"kind\":\"EMERGENCY\",\"description\":\"사유 없음\"}"), t))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("PTL-422-001"));
        assertThat(mvc.perform(asCustomer(RptFlow.postJson("/api/v1/ptl/service-requests",
                "{\"kind\":\"HACK\",\"description\":\"x\"}"), t)).andReturn().getResponse().getStatus()).isBetween(400, 422);
        assertThat(json(mvc.perform(asCustomer(get("/api/v1/ptl/service-requests"), t)).andReturn())).hasSize(1);
    }

    @Test
    @DisplayName("구독: fac.grade.recorded · due-approaching · overdue · sal.estimate.issued → 소유 고객사에만 알림 · 공유 문서, 재전송 멱등")
    void facAndSalEventsNotifyOwnerOnly() throws Exception {
        UUID clientA = createClient(actor(), "합성고객-ptl-ev-A-" + UUID.randomUUID());
        UUID clientB = createClient(actor(), "합성고객-ptl-ev-B-" + UUID.randomUUID());
        Tenant a = new Tenant(clientA, UUID.randomUUID(), null, null, null, null, null, null, null, null, null);
        UUID bA = ownedBuilding(clientA, "합성 이벤트 A동", 25);
        UUID bB = ownedBuilding(clientB, "합성 이벤트 B동", 25);
        emit(new FacEvents.GradeRecorded(bA, "C", "B", "REGULAR", today().minusDays(3), UUID.randomUUID()));
        emit(new FacEvents.InspectionDueApproaching(bA, "REGULAR", today().plusDays(25), 30, "due:" + bA + ":30"));
        emit(new FacEvents.InspectionDueApproaching(bA, "REGULAR", today().plusDays(25), 30, "due:" + bA + ":30"));
        emit(new FacEvents.InspectionOverdue(bB, "REGULAR", today().minusDays(1)));
        UUID opp = opportunity(clientA);
        UUID estimate = UUID.randomUUID();
        emit(new SalEvents.EstimateIssued(estimate, opp, "EST-PT-" + SEQ.incrementAndGet(), 1,
                new BigDecimal("8800000"), new BigDecimal("880000"), null));
        flow.drain();
        List<String> kindsA = jdbc.queryForList("SELECT kind FROM ptl.notification WHERE customer_id = ? ORDER BY kind",
                String.class, clientA);
        assertThat(kindsA).containsExactly("ESTIMATE_ISSUED", "GRADE_RECORDED", "INSPECTION_DUE_APPROACHING");
        assertThat(jdbc.queryForList("SELECT kind FROM ptl.notification WHERE customer_id = ?", String.class, clientB))
                .containsExactly("INSPECTION_OVERDUE");
        JsonNode docs = json(mvc.perform(asCustomer(get("/api/v1/ptl/sales-documents"), a)).andReturn());
        assertThat(docs).hasSize(1);
        assertThat(docs.get(0).get("kind").asText()).isEqualTo("ESTIMATE");
        assertThat(docs.get(0).get("salRefId").asText()).isEqualTo(estimate.toString());
        JsonNode schedule = json(mvc.perform(asCustomer(get("/api/v1/ptl/inspection-schedule"), a)).andReturn());
        assertThat(schedule).hasSize(1);
        assertThat(schedule.get(0).get("severity").asText()).isEqualTo("RED");
        assertThat(schedule.get(0).get("dDay").asInt()).isEqualTo(25);
        JsonNode detail = json(mvc.perform(asCustomer(get("/api/v1/ptl/buildings/" + bA), a)).andExpect(status().isOk())
                .andReturn());
        assertThat(detail.get("inspectionSchedule")).hasSize(1);
        // 소유 고객 없는 시설물 이벤트 → 아무 고객에게도 알림 없음(예외로 다른 리스너를 롤백시키지 않음)
        UUID site = UUID.randomUUID();
        UUID orphan = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        jdbc.update("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES (?, '합성 무소유 단지', ?, ?)", site, by, by);
        jdbc.update("INSERT INTO fac.building(id, site_id, name, status, data_source, created_by, updated_by) "
                + "VALUES (?, ?, '합성 무소유 동', 'ACTIVE', 'MANUAL', ?, ?)", orphan, site, by, by);
        emit(new FacEvents.InspectionOverdue(orphan, "REGULAR", today().minusDays(2)));
        flow.drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptl.notification WHERE ref_id = ?", Long.class, orphan))
                .isZero();
    }

    // ------------------------------------------------------------------ 보안 검토 2026-10-19 정책 결정

    @Autowired
    com.atomeng.erp.ptl.application.PortalDownloadRateLimiter downloadLimiter;
    @Autowired
    com.atomeng.common.identity.authz.PermissionRegistry permissionRegistry;

    @Test
    @DisplayName("PTL-01: CUSTOMER 권한은 ptl:* 뿐, 포털 주체가 내부 API(rpt 원본 PDF · core · dem · fac · sal)를 부르면 "
            + "403 CORE-403-010(problem+json), 포털 API 는 200")
    void ptl01PortalRealmLimitedToPtl() throws Exception {
        assertThat(permissionRegistry.permissionsOf(com.atomeng.common.identity.Role.CUSTOMER).keySet())
                .isNotEmpty().allMatch(k -> k.startsWith("ptl:"));
        Tenant t = tenant("P1", "B");
        for (String url : List.of("/api/v1/rpt/revisions/" + t.revisionId() + "/files/report.pdf",
                "/api/v1/rpt/revisions/" + t.revisionId(), "/api/v1/rpt/reports?projectId=" + t.projectId(),
                "/api/v1/core/projects/" + t.projectId(), "/api/v1/dem/daily-logs/" + UUID.randomUUID(),
                "/api/v1/fac/buildings/" + t.building(), "/api/v1/sal/estimates/" + UUID.randomUUID(),
                "/api/v1/test/hrq/engineers", "/actuator/metrics")) {
            mvc.perform(asCustomer(get(url), t)).andExpect(status().isForbidden())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                            .contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("CORE-403-010"));
        }
        mvc.perform(asCustomer(post("/api/v1/rpt/reports"), t).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-010"));
        // 포털 API 는 그대로
        mvc.perform(asCustomer(get("/api/v1/ptl/me"), t)).andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value(t.clientId().toString()));
        mvc.perform(asCustomer(get("/api/v1/ptl/reports"), t)).andExpect(status().isOk());
        // 내부 직원(PM)은 영향 없음
        mvc.perform(as(get("/api/v1/rpt/revisions/" + t.revisionId()), t.pm(), "PM")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("PTL-03: 고객사별 워터마크 다운로드 속도 제한 — 분당 한도 초과 429 PTL-429-001(retryAfterSeconds), "
            + "다른 고객사는 영향 없음, 1분 뒤 다시 200")
    void ptl03DownloadRateLimitPerCustomer() throws Exception {
        Tenant t = tenant("RL", "B");
        Tenant other = new Tenant(createClient(actor(), "합성고객-ptl-RL2-" + UUID.randomUUID()), UUID.randomUUID(),
                null, null, null, null, null, null, null, null, null);
        int max = downloadLimiter.maxPerMinute();
        assertThat(max).isPositive();
        for (int i = 0; i < max; i++) {
            mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + t.publishedReportId() + ":download"), t))
                    .andExpect(status().isOk());
        }
        mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + t.publishedReportId() + ":download"), t))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("PTL-429-001"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ptl.report_download WHERE customer_id = ?", Long.class,
                t.clientId())).isEqualTo(max);
        // 다른 고객사: 한도와 무관(자기 고객사 자원이 아니므로 404 — 429 아님)
        mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + t.publishedReportId() + ":download"), other))
                .andExpect(status().isNotFound());
        clock.advance(java.time.Duration.ofSeconds(61));
        mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + t.publishedReportId() + ":download"), t))
                .andExpect(status().isOk());
    }
}
