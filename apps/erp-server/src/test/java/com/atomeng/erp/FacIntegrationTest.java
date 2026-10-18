package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.erp.fac.adapter.in.event.ReportPublishedListener;
import com.atomeng.erp.fac.application.DueScanService;
import com.atomeng.erp.support.IntegrationTestBase;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 02 FAC 통합 테스트 — 등록·중복(TC-FAC-001), 등급(TC-FAC-003·005), 도래 스캔(TC-FAC-006), 이벤트 */
class FacIntegrationTest extends IntegrationTestBase {

    private static final AtomicLong PNU_SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @Autowired
    OutboxRelay relay;
    @Autowired
    DueScanService dueScan;
    @Autowired
    ApplicationEventPublisher events;
    @Autowired
    PlatformTransactionManager txManager;

    UUID pm;

    @BeforeEach
    void setUp() {
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
        pm = actor();
        clock.reset();
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    private static String pnu() {
        return "11%017d".formatted(PNU_SEQ.incrementAndGet());
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneId.of("Asia/Seoul")));
    }

    private UUID register(String pnu, String name, String dong) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/fac/buildings", """
                        {"name":"%s","dongName":"%s","pnu":"%s","roadAddress":"서울 합성구 합성로 %s",
                         "structureNm":"철근콘크리트구조","floorsAbove":12,"floorsBelow":2,
                         "approvalDate":"1995-06-30","dataSource":"MANUAL"}"""
                        .formatted(name, dong, pnu, pnu.substring(14))), pm, "PM")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated())
                .andExpect(header().string("ETag", "\"v0\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))       // 대장 조회 없이 수기 등록
                .andExpect(jsonPath("$.dataSource").value("MANUAL"))
                .andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private void classify(UUID id, String cls) throws Exception {
        mvc.perform(as(put("/api/v1/fac/buildings/" + id + "/classification"), pm, "PM")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sitbeopClass\":\"" + cls + "\",\"bmaTarget\":false,"
                                + "\"evidence\":{\"grossFloorArea\":31000,\"note\":\"합성 근거\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLASSIFIED"));
    }

    /** RPT 미구현 → 보고서 발행 이벤트를 outbox 에 직접 넣고 Relay 로 전달(실제 경로와 동일) */
    private UUID publishReport(String type, UUID buildingId, String grade, String inspectionType, LocalDate performedOn) {
        UUID eventId = UUID.randomUUID();
        UUID reportId = UUID.randomUUID();
        String payload = """
                {"reportId":"%s","buildingIds":["%s"],"grade":"%s","inspectionType":"%s","performedOn":"%s",
                 "approvedBy":"%s"}""".formatted(reportId, buildingId, grade, inspectionType, performedOn, pm);
        jdbc.update("""
                INSERT INTO core.outbox_event(id, aggregate_type, aggregate_id, event_type, payload, next_attempt_at)
                VALUES (?, 'Report', ?, ?, ?::jsonb, now() - interval '1 minute')""", eventId, reportId, type, payload);
        // 과거 시각: DB 시계(now)와 JVM 시계(Relay 기준)의 수 ms 차이로 즉시 폴링이 놓치는 플래키 방지
        return eventId;
    }

    private int outboxCount(String type, UUID aggregateId) {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, type, aggregateId);
    }

    @Test
    @DisplayName("TC-FAC-001: 같은 PNU·동명 두 번 등록 → 두 번째 409 FAC-409-001 + 후보 1건(PNU_DONG)")
    void tcFac001_duplicateCandidate() throws Exception {
        String pnu = pnu();
        UUID first = register(pnu, "합성 사옥 본관", "본관");
        assertThat(outboxCount("fac.building.registered", first)).isEqualTo(1);

        mvc.perform(as(postJson("/api/v1/fac/buildings", """
                        {"name":"합성 사옥 본관","dongName":"본관","pnu":"%s"}""".formatted(pnu)), pm, "PM"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FAC-409-001"))
                .andExpect(jsonPath("$.candidates.length()").value(1))
                .andExpect(jsonPath("$.candidates[0].id").value(first.toString()))
                .andExpect(jsonPath("$.candidates[0].matchedBy").value("PNU_DONG"));
        // force 로도 PNU+동 일치는 우회 불가(BR-FAC-002)
        mvc.perform(as(postJson("/api/v1/fac/buildings", """
                        {"name":"합성 사옥 본관","dongName":"본관","pnu":"%s","force":true,"forceReason":"테스트"}"""
                        .formatted(pnu)), pm, "PM"))
                .andExpect(status().isConflict());
        // 같은 PNU 의 다른 동은 신규
        register(pnu, "합성 사옥 별관", "별관");
    }

    @Test
    @DisplayName("설계 9장: POST 멱등(Idempotent-Replayed) · ETag/If-Match 수정 412 · 논리삭제 · 감사 로그")
    void crudWithIdempotencyAndEtag() throws Exception {
        String key = UUID.randomUUID().toString();
        String body = "{\"name\":\"합성 물류센터\",\"pnu\":\"" + pnu() + "\"}";
        var first = mvc.perform(as(postJson("/api/v1/fac/buildings", body), pm, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated()).andReturn();
        String id = json(first).get("id").asText();
        mvc.perform(as(postJson("/api/v1/fac/buildings", body), pm, "SALES").header("Idempotency-Key", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(as(patch("/api/v1/fac/buildings/" + id), pm, "PM").header("If-Match", "\"v0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"floorsAbove\":5}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"v1\""));
        mvc.perform(as(patch("/api/v1/fac/buildings/" + id), actor(), "ADMIN").header("If-Match", "\"v0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"floorsAbove\":6}"))
                .andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("CORE-412-001"));

        // 삭제는 SYSADMIN 만 (A2)
        mvc.perform(as(delete("/api/v1/fac/buildings/" + id), pm, "PM").header("If-Match", "\"v1\""))
                .andExpect(status().isForbidden());
        mvc.perform(as(delete("/api/v1/fac/buildings/" + id), actor(), "SYSADMIN").header("If-Match", "\"v1\""))
                .andExpect(status().isNoContent());
        mvc.perform(as(get("/api/v1/fac/buildings/" + id), pm, "PM"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FAC-404-001"));
        assertThat(jdbc.queryForList("SELECT action FROM core.audit_log WHERE module = 'FAC' AND target_id = ?::uuid "
                + "ORDER BY id", String.class, id)).containsExactly("CREATE", "UPDATE", "DELETE");
        // 목록 검색
        mvc.perform(as(get("/api/v1/fac/buildings").param("q", "물류"), pm, "EXEC")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("TC-FAC-003: B 등급 → D 등급 보고서 → 등급 이력 append, 단축 주기로 도래일 재계산, critical 이벤트, 사용제한 플래그")
    void tcFac003_downgradeToD() throws Exception {
        UUID b = register(pnu(), "합성 노후 상가", "");
        classify(b, "CLASS1");
        LocalDate p1 = today().minusDays(400);
        LocalDate p2 = today().minusDays(10);
        publishReport("rpt.report.published", b, "B", "DETAILED", p1);
        assertThat(relay.pollOnce().published()).isGreaterThanOrEqualTo(1);
        mvc.perform(as(get("/api/v1/fac/buildings/" + b + "/dues"), pm, "PM"))
                .andExpect(jsonPath("$[?(@.inspectionType == 'DETAILED')].dueDate").value(p1.plusMonths(36).toString()));

        publishReport("rpt.report.published", b, "D", "DETAILED", p2);
        relay.pollOnce();

        mvc.perform(as(get("/api/v1/fac/buildings/" + b + "/grades"), pm, "PM"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].grade").value("D"))
                .andExpect(jsonPath("$[1].grade").value("B"));
        mvc.perform(as(get("/api/v1/fac/buildings/" + b + "/dues"), pm, "PM"))
                .andExpect(jsonPath("$[?(@.inspectionType == 'DETAILED')].dueDate").value(p2.plusMonths(24).toString()))
                .andExpect(jsonPath("$[?(@.inspectionType == 'DETAILED')].calcTrace.ruleCode")
                        .value("SITBEOP-CLASS1-DET-D-v2018"))
                // 정밀점검이 정기점검을 갈음 → 정기점검 기준일 = 정밀점검 수행일, D 등급 4개월(⚠️ 예시 규칙)
                .andExpect(jsonPath("$[?(@.inspectionType == 'REGULAR')].dueDate").value(p2.plusMonths(4).toString()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.inspection_due WHERE building_id = ? "
                + "AND inspection_type = 'DETAILED' AND status = 'DONE'", Integer.class, b))
                .as("최초(사용승인 기준)·B 회차 도래가 수행 완료로 종료").isEqualTo(2);
        assertThat(outboxCount("fac.grade.critical-detected", b)).isEqualTo(1);
        assertThat(outboxCount("fac.grade.recorded", b)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT payload->>'previousGrade' FROM core.outbox_event WHERE event_type = "
                + "'fac.grade.recorded' AND aggregate_id = ? AND payload->>'grade' = 'D'", String.class, b)).isEqualTo("B");
        mvc.perform(as(get("/api/v1/fac/buildings/" + b), pm, "PM"))
                .andExpect(jsonPath("$.useRestrictionFlag").value(true));
    }

    @Test
    @DisplayName("TC-FAC-005: 같은 rpt.report.published 이벤트 2회 수신 → 등급 이력 1건 (processed_event 멱등)")
    void tcFac005_duplicateDelivery() throws Exception {
        UUID b = register(pnu(), "합성 아파트 101동", "101동");
        classify(b, "CLASS2");
        UUID eventId = publishReport("rpt.report.published", b, "C", "REGULAR", today().minusDays(30));
        relay.pollOnce();
        // 재전달(at-least-once) — 같은 envelope 을 다시 받는다
        var payload = objectMapper.readTree(jdbc.queryForObject(
                "SELECT payload::text FROM core.outbox_event WHERE id = ?", String.class, eventId));
        var env = new EventEnvelope(eventId, "rpt.report.published", 1, OffsetDateTime.now(ZoneOffset.UTC), "Report",
                UUID.randomUUID(), null, null, payload);
        new TransactionTemplate(txManager).executeWithoutResult(s -> events.publishEvent(env));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fac.grade_history WHERE building_id = ?", Integer.class, b))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event WHERE consumer = ? AND event_id = ?",
                Integer.class, ReportPublishedListener.CONSUMER, eventId)).isEqualTo(1);
    }

    @Test
    @DisplayName("TC-FAC-006: D-90 스캔을 같은 날 2회 → due-approaching 1건, 이후 D-30·OVERDUE 각 1건(멱등키)")
    void tcFac006_dueScanIdempotent() throws Exception {
        UUID b = register(pnu(), "합성 공공청사", "");
        classify(b, "CLASS2");
        // 정기점검(⚠️ 예시: A~C 6개월) — 도래일이 오늘+60일이 되도록 수행일 설정
        LocalDate performed = today().plusDays(60).minusMonths(6);
        publishReport("rpt.report.published", b, "B", "REGULAR", performed);
        relay.pollOnce();
        LocalDate dueDate = performed.plusMonths(6);

        dueScan.scan();
        dueScan.scan();
        assertThat(outboxCount("fac.inspection.due-approaching", b)).isEqualTo(1);
        var ev = jdbc.queryForMap("SELECT payload->>'threshold' AS t, payload->>'idempotencyKey' AS k, "
                + "payload->>'dueDate' AS d FROM core.outbox_event WHERE event_type = 'fac.inspection.due-approaching' "
                + "AND aggregate_id = ?", b);
        assertThat(ev.get("t")).isEqualTo("90");
        assertThat(ev.get("d")).isEqualTo(dueDate.toString());
        assertThat(ev.get("k")).isEqualTo(b + ":REGULAR:" + dueDate + ":90");
        assertThat(jdbc.queryForObject("SELECT status FROM fac.inspection_due WHERE building_id = ? "
                + "AND inspection_type = 'REGULAR' AND status NOT IN ('DONE','CANCELLED')", String.class, b))
                .isEqualTo("APPROACHING_90");

        clock.advance(Duration.ofDays(35)); // 남은 25일 → D-30
        dueScan.scan();
        dueScan.scan();
        assertThat(outboxCount("fac.inspection.due-approaching", b)).isEqualTo(2);

        clock.advance(Duration.ofDays(30)); // 도래일 경과
        dueScan.scan();
        dueScan.scan();
        assertThat(outboxCount("fac.inspection.overdue", b)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT threshold_days FROM fac.due_notification n JOIN fac.inspection_due d "
                + "ON d.id = n.due_id WHERE d.building_id = ? ORDER BY threshold_days DESC", Integer.class, b))
                .containsExactly(90, 30, 0);
    }

    @Test
    @DisplayName("TC-FAC-007(통합): 애플리케이션 계정으로도 grade_history UPDATE 불가")
    void tcFac007_viaApplication() throws Exception {
        UUID b = register(pnu(), "합성 체육관", "");
        publishReport("rpt.report.published", b, "C", "DETAILED", today().minusDays(3));
        relay.pollOnce();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        jdbc.update("UPDATE fac.grade_history SET grade = 'A' WHERE building_id = ?", b))
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("SELECT grade FROM fac.grade_history WHERE building_id = ?", String.class, b))
                .isEqualTo("C");
    }

    @Test
    @DisplayName("FAC 권한(A2): CUSTOMER·EMPLOYEE 는 시설물 등록 불가, ENG 는 조회만")
    void permissions() throws Exception {
        for (String role : List.of("CUSTOMER", "EMPLOYEE", "ENG")) {
            mvc.perform(as(postJson("/api/v1/fac/buildings", "{\"name\":\"x\"}"), actor(), role))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(as(get("/api/v1/fac/buildings"), actor(), "ENG")).andExpect(status().isOk());
        assertThat(Map.of()).isEmpty();
    }
}
