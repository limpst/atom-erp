package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.common.evidence.Buckets;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 10 DEM 통합 테스트 — 감리자 지정(TC-DEM-001·002·003, BR-DEM-001), HRQ 이벤트 구독(자격 만료 · 퇴사), 감리일지
 * (TC-DEM-004·005, 오프라인 clientOpId 재전송), 증거 사진(core 첨부 · TC-DEM-009), 도서 발행 · 검토의견(TC-DEM-007·008),
 * 해체 완료 → FAC 멸실(실제 이벤트 흐름), 프로젝트 종료, 인접건축물 비교. 모든 데이터는 합성값.
 */
class DemIntegrationTest extends IntegrationTestBase {

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.nanoTime() % 100_000));
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final String ITEM_GUARDRAIL = "0192d000-0000-7000-8000-000000001031";   // 사진 필수 · HIGH
    static final String ITEM_DEBRIS = "0192d000-0000-7000-8000-000000001033";      // 사진 필수 · CRITICAL
    static final String ITEM_SEQUENCE = "0192d000-0000-7000-8000-000000001035";    // 사진 선택

    @Autowired
    OutboxRelay relay;
    @Autowired
    ObjectStorage storage;
    @Autowired
    OutboxPublisher outbox;
    @Autowired
    TransactionTemplate tx;

    UUID admin;
    UUID pm;

    @BeforeEach
    void setUp() {
        admin = actor();
        pm = actor();
        clock.reset();
    }

    @AfterEach
    void reset() {
        clock.reset();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private void drain() {
        for (int i = 0; i < 30 && relay.pollOnce().published() > 0; i++) {
            // 연쇄 이벤트까지 모두 배달
        }
    }

    private int events(String type, UUID aggregate) {
        return jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                Integer.class, type, aggregate);
    }

    private JsonNode lastEvent(String type, UUID aggregate) throws Exception {
        return objectMapper.readTree(jdbc.queryForObject("SELECT payload::text FROM core.outbox_event "
                + "WHERE event_type = ? AND aggregate_id = ? ORDER BY occurred_at DESC, id DESC LIMIT 1",
                String.class, type, aggregate));
    }

    private MockHttpServletRequestBuilder putJson(String url, String body) {
        return put(url).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    // ------------------------------------------------------------------ 합성 기준 데이터

    /** core.employee + hrq.employee(등록 분야 아님 — HRQ 등록요건 시험에 영향 0) + 승인된 건축기사 자격(감리 자격 예시) */
    private UUID engineer(boolean qualified) throws Exception {
        UUID id = UUID.randomUUID();
        String empNo = "D-" + SEQ.incrementAndGet();
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성감리자', DATE '2020-01-01', ?, ?)", id, empNo, id, id);
        mvc.perform(as(postJson("/api/v1/hrq/employees", """
                        {"id":"%s","empNo":"%s","name":"합성감리자","field":"ARCH","employmentType":"FULLTIME",
                         "registeredField":false,"hiredOn":"2020-01-01","initialGrade":"SENIOR","gradeFrom":"2020-01-01"}"""
                        .formatted(id, empNo)), admin, "ADMIN"))
                .andExpect(status().isCreated());
        if (qualified) {
            credential(id, "ARCH_ENG", null);
        }
        return id;
    }

    private UUID credential(UUID emp, String type, LocalDate expiresOn) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + "/credentials", """
                        {"typeCode":"%s","certNo":"SYN-%d","acquiredOn":"2019-01-01"%s}""".formatted(type,
                        SEQ.incrementAndGet(), expiresOn == null ? "" : ",\"expiresOn\":\"" + expiresOn + "\"")), emp, "ENG"))
                .andExpect(status().isCreated()).andReturn();
        UUID credId = UUID.fromString(json(r).get("id").asText());
        mvc.perform(as(postJson("/api/v1/hrq/credentials/" + credId + ":approve", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk());
        return credId;
    }

    private UUID coreProject() throws Exception {
        UUID client = createClient(actor(), "합성발주처-dem-" + UUID.randomUUID());
        var r = mvc.perform(as(postJson("/api/v1/core/projects", "{\"name\":\"합성 해체감리 프로젝트\",\"serviceType\":"
                + "\"PRECISE_DIAG\",\"clientId\":\"" + client + "\"}"), pm, "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    /** 합성 해체 대상 건축물(ACTIVE) + 진행 중 점검 도래 1건 */
    private UUID building() {
        UUID site = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID by = UUID.randomUUID();
        jdbc.update("INSERT INTO fac.site(id, name, created_by, updated_by) VALUES (?, '합성 해체 단지', ?, ?)", site, by, by);
        jdbc.update("INSERT INTO fac.building(id, site_id, name, status, data_source, created_by, updated_by) "
                + "VALUES (?, ?, '합성 해체 대상 동', 'ACTIVE', 'MANUAL', ?, ?)", b, site, by, by);
        UUID rule = jdbc.queryForObject("SELECT id FROM fac.inspection_cycle_rule ORDER BY id LIMIT 1", UUID.class);
        jdbc.update("INSERT INTO fac.inspection_due(id, building_id, inspection_type, due_date, rule_id, calc_trace) "
                + "VALUES (?, ?, 'REGULAR', ?, ?, '{}'::jsonb)", UUID.randomUUID(), b, java.sql.Date.valueOf(today().plusYears(1)),
                rule);
        return b;
    }

    record Fixture(UUID projectId, UUID buildingId, UUID supervisionWp, UUID designWp) {}

    private Fixture demProject() throws Exception {
        UUID project = coreProject();
        // 담당 PM 투입(RESP) — 일지 열람은 PROJECT 범위(보안 검토 2026-10-19)
        jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                + "VALUES (?, ?, '합성PM', DATE '2020-01-01', ?, ?) ON CONFLICT (id) DO NOTHING", pm,
                "DPM-" + SEQ.incrementAndGet(), pm, pm);
        jdbc.update("INSERT INTO core.project_member(project_id, employee_id, role_in_project, from_date, to_date) "
                + "VALUES (?, ?, 'RESP', DATE '2020-01-01', DATE '2099-12-31')", project, pm);
        UUID building = building();
        var r = mvc.perform(as(postJson("/api/v1/dem/projects", """
                        {"projectId":"%s","buildingId":"%s","packages":[{"packageType":"DEM_SUPERVISION"},
                         {"packageType":"STR_DESIGN","designCode":"KDS 41"}]}""".formatted(project, building)), pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.workPackages.length()").value(2)).andReturn();
        UUID sup = null;
        UUID design = null;
        for (JsonNode w : json(r).get("workPackages")) {
            if (w.get("packageType").asText().equals("DEM_SUPERVISION")) {
                sup = UUID.fromString(w.get("id").asText());
            } else {
                design = UUID.fromString(w.get("id").asText());
            }
        }
        assertThat(events("dem.work-package.created", sup)).isEqualTo(1);
        return new Fixture(project, building, sup, design);
    }

    private JsonNode assign(UUID wp, UUID emp, String role, LocalDate from, LocalDate to, int expect) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/dem/work-packages/" + wp + "/supervisors", """
                        {"employeeId":"%s","role":"%s","validFrom":"%s"%s}""".formatted(emp, role, from,
                        to == null ? "" : ",\"validTo\":\"" + to + "\"")), pm, "PM"))
                .andExpect(status().is(expect)).andReturn();
        return json(r);
    }

    private void transition(UUID project, String to, int expect) throws Exception {
        mvc.perform(as(postJson("/api/v1/dem/projects/" + project + ":transition", "{\"to\":\"" + to + "\"}"), pm, "PM"))
                .andExpect(status().is(expect));
    }

    private void toPermitPending(UUID project) throws Exception {
        transition(project, "PLANNING", 200);
        transition(project, "REVIEWING", 200);
        transition(project, "PERMIT_PENDING", 200);
    }

    // ------------------------------------------------------------------ 감리자 지정

    @Test
    @DisplayName("TC-DEM-001 + hrq.credential.expired 구독: 만료 이벤트를 받은 자격만 가진 직원 → 422 DEM-422-002(CREDENTIAL_EXPIRED) · 저장 0, "
            + "현재 감리 중인 패키지는 공백 경고 + 일지 차단, 자격 없음 → NO_VALID_QUALIFICATION")
    void tcDem001_credentialExpiredBlocksAssignment() throws Exception {
        var f = demProject();
        var f2 = demProject();
        UUID emp = engineer(false);
        UUID cred = credential(emp, "SAFETY_DIAG_CERT", today().plusDays(200));
        var a = assign(f.supervisionWp(), emp, "CHIEF", today(), null, 201);
        assertThat(a.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(a.at("/hrqCheckSnapshot/qualified").asBoolean()).isTrue();
        assertThat(events("dem.supervisor.assigned", f.supervisionWp())).isEqualTo(1);

        // HRQ 가 자격 만료를 발행(대역: HRQ 정본 레코드 hrq::api HrqEvents.CredentialExpired) → Relay → DEM 구독
        tx.executeWithoutResult(s -> outbox.publish(new HrqEvents.CredentialExpired(emp, cred, "SAFETY_DIAG_CERT", today())));
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dem.hrq_credential_block WHERE credential_id = ?",
                Integer.class, cred)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT supervisor_gap FROM dem.work_package WHERE id = ?", Boolean.class,
                f.supervisionWp())).isTrue();
        assertThat(jdbc.queryForObject("SELECT kind FROM dem.supervisor_alert WHERE work_package_id = ?", String.class,
                f.supervisionWp())).isEqualTo("CREDENTIAL_EXPIRED");
        assertThat(events("dem.supervisor-gap.detected", f.supervisionWp())).isEqualTo(1);

        // HRQ 상태는 아직 VALID 지만(만료 스캔 전) 이벤트로 받은 만료 자격은 무효 → 새 지정 거부, 행 0
        mvc.perform(as(postJson("/api/v1/dem/work-packages/" + f2.supervisionWp() + "/supervisors", """
                        {"employeeId":"%s","role":"CHIEF","validFrom":"%s"}""".formatted(emp, today())), pm, "PM"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("DEM-422-002"))
                .andExpect(jsonPath("$.reason").value("DEM_SUPERVISOR_NOT_QUALIFIED"))
                .andExpect(jsonPath("$.reasons[0]").value("CREDENTIAL_EXPIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dem.supervisor_assignment WHERE work_package_id = ?",
                Integer.class, f2.supervisionWp())).isZero();
        // 공백 패키지의 일지 작성 차단(A8)
        mvc.perform(as(putJson("/api/v1/dem/daily-logs/" + f.supervisionWp() + "/" + today(), logBody(UUID.randomUUID(),
                        "[]", false)), emp, "ENG"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-006"));
        // 같은 이벤트 재배달 → 멱등(경고 1건 유지)
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dem.supervisor_alert WHERE work_package_id = ?",
                Integer.class, f.supervisionWp())).isEqualTo(1);

        // 자격 갱신(새 자격) → 다시 지정 가능, 대체 책임감리자 지정은 공백 해소
        credential(emp, "ARCH_ENG", null);
        assign(f2.supervisionWp(), emp, "CHIEF", today(), null, 201);
        UUID replacement = engineer(true);
        assign(f.supervisionWp(), replacement, "CHIEF", today().plusDays(1), null, 409);   // 기존 책임 기간과 겹침
        mvc.perform(as(postJson("/api/v1/dem/supervisors/" + a.get("id").asText() + ":release",
                "{\"releasedOn\":\"" + today() + "\"}"), pm, "PM")).andExpect(status().isOk());
        assign(f.supervisionWp(), replacement, "CHIEF", today().plusDays(1), null, 201);
        assertThat(jdbc.queryForObject("SELECT supervisor_gap FROM dem.work_package WHERE id = ?", Boolean.class,
                f.supervisionWp())).isFalse();

        // 자격이 아예 없는 직원
        UUID nobody = engineer(false);
        mvc.perform(as(postJson("/api/v1/dem/work-packages/" + f2.designWp() + "/supervisors", """
                        {"employeeId":"%s","role":"ASSISTANT","validFrom":"%s"}""".formatted(nobody, today())), pm, "PM"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.reasons[0]").value("NO_VALID_QUALIFICATION"));
    }

    @Test
    @DisplayName("TC-DEM-002 · 003: 동시 감리 한도(예시 3) 도달 감리자 추가 지정 → 422 DEM-422-003, 책임감리자 기간 중복 → 409 DEM-409-003")
    void tcDem002_003_limitAndOverlap() throws Exception {
        UUID emp = engineer(true);
        for (int i = 0; i < 3; i++) {
            assign(demProject().supervisionWp(), emp, "CHIEF", today(), today().plusMonths(6), 201);
        }
        var fourth = demProject();
        mvc.perform(as(postJson("/api/v1/dem/work-packages/" + fourth.supervisionWp() + "/supervisors", """
                        {"employeeId":"%s","role":"CHIEF","validFrom":"%s"}""".formatted(emp, today().plusDays(10))),
                        pm, "PM"))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("DEM-422-003"))
                .andExpect(jsonPath("$.activeCount").value(3)).andExpect(jsonPath("$.limit").value(3));
        // 한도 기간이 끝난 뒤(6개월 후)는 허용, 보조감리자는 한도 대상 아님
        assign(fourth.supervisionWp(), emp, "ASSISTANT", today(), null, 201);
        // TC-DEM-003: 같은 패키지 책임감리자 기간 중복
        UUID other = engineer(true);
        assign(fourth.supervisionWp(), other, "CHIEF", today(), today().plusMonths(1), 201);
        UUID third = engineer(true);   // 한도와 무관한 감리자로 기간 중복만 검증
        mvc.perform(as(postJson("/api/v1/dem/work-packages/" + fourth.supervisionWp() + "/supervisors", """
                        {"employeeId":"%s","role":"CHIEF","validFrom":"%s"}""".formatted(third, today().plusDays(15))),
                        pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-003"))
                .andExpect(jsonPath("$.reason").value("DEM_CHIEF_PERIOD_OVERLAP"));
        assign(fourth.supervisionWp(), emp, "CHIEF", today().plusMonths(7), null, 201);
    }

    @Test
    @DisplayName("hrq.employee.terminated 구독: 퇴사 확정 → 진행 중 지정 해제(valid_to = 퇴사일) · 공백 경고 · 이후 지정 422(EMPLOYEE_TERMINATED)")
    void employeeTerminatedReleasesSupervisor() throws Exception {
        var f = demProject();
        UUID emp = engineer(true);
        UUID aid = UUID.fromString(assign(f.supervisionWp(), emp, "CHIEF", today().minusDays(5), null, 201)
                .get("id").asText());
        LocalDate resigned = today();
        mvc.perform(as(postJson("/api/v1/hrq/employees/" + emp + ":terminate",
                "{\"resignedOn\":\"" + resigned + "\",\"source\":\"MANUAL\"}"), admin, "ADMIN")).andExpect(status().isOk());
        drain();
        assertThat(jdbc.queryForMap("SELECT status, valid_to, release_reason FROM dem.supervisor_assignment WHERE id = ?",
                aid)).containsEntry("status", "RELEASED").containsEntry("release_reason", "HRQ_TERMINATED")
                .containsEntry("valid_to", java.sql.Date.valueOf(resigned));
        assertThat(jdbc.queryForObject("SELECT supervisor_gap FROM dem.work_package WHERE id = ?", Boolean.class,
                f.supervisionWp())).isTrue();
        assertThat(events("dem.supervisor.released", f.supervisionWp())).isEqualTo(1);
        assertThat(lastEvent("dem.supervisor-gap.detected", f.supervisionWp()).get("kind").asText())
                .isEqualTo("EMPLOYEE_TERMINATED");
        mvc.perform(as(postJson("/api/v1/dem/work-packages/" + demProject().supervisionWp() + "/supervisors", """
                        {"employeeId":"%s","role":"CHIEF","validFrom":"%s"}""".formatted(emp, today().plusDays(1))), pm, "PM"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.reasons[0]").value("EMPLOYEE_TERMINATED"));
        mvc.perform(as(get("/api/v1/dem/work-packages/" + f.supervisionWp() + "/supervisors"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.alerts[0].kind").value("EMPLOYEE_TERMINATED"));
    }

    // ------------------------------------------------------------------ 감리일지 · 증거 사진

    private static byte[] syntheticPhoto(int i) throws Exception {
        var img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color((30 + i * 17) % 256, 90, 140));
        g.fillRect(0, 0, 160, 120);
        g.setColor(Color.DARK_GRAY);
        g.drawLine(5 + i, 5, 150, 110 - i);   // 합성 "잔재물"
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** core 첨부 파이프라인(presign → PUT → :complete)으로 합성 사진 업로드 → {attachmentId, objectKey, sha256} */
    private JsonNode upload(UUID eng, int i) throws Exception {
        byte[] photo = syntheticPhoto(i);
        String sha = HashChain.sha256(new ByteArrayInputStream(photo));
        var p = json(mvc.perform(as(postJson("/api/v1/core/attachments:presign", """
                        {"fileName":"DEM_%04d.png","mimeType":"image/png","sizeBytes":%d,"sha256":"%s","capturedAt":"%s",
                         "ownerModule":"DEM","ownerType":"EvidencePhoto","ownerId":"%s","chainScope":"%s"}"""
                        .formatted(i, photo.length, sha, Instant.now(), UUID.randomUUID(), UUID.randomUUID())), eng, "ENG")
                        .header("X-Client-Id", "field-app").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn());
        storage.put(Buckets.TEMP_UPLOAD, p.get("tempKey").asText(), new ByteArrayInputStream(photo), "image/png");
        var stored = json(mvc.perform(as(postJson("/api/v1/core/attachments/uploads/" + p.get("uploadId").asText()
                        + ":complete", "{}"), eng, "ENG").header("X-Client-Id", "field-app")
                        .header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andReturn());
        ((com.fasterxml.jackson.databind.node.ObjectNode) stored).put("sha256", sha);
        return stored;
    }

    private UUID registerPhoto(UUID eng, UUID wp, JsonNode att, int expect) throws Exception {
        var r = mvc.perform(as(postJson("/api/v1/dem/evidence/photos", """
                        {"attachmentId":"%s","sha256":"%s","workPackageId":"%s","capturedAt":"%s","lat":37.539,"lng":127.214,
                         "deviceId":"SYN-TABLET-01"}""".formatted(att.get("attachmentId").asText(), att.get("sha256").asText(),
                        wp, Instant.now())), eng, "ENG").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().is(expect)).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private static String logBody(UUID clientOpId, String checklist, boolean submit) {
        return """
                {"clientOpId":"%s","phaseCode":"UPPER_DEMOLITION","progressPct":45,"workersCount":14,
                 "weather":{"sky":"CLEAR","tempC":18,"windMs":3.0,"source":"SYNTHETIC"},
                 "equipment":[{"code":"CRUSHER_30T","qty":1},{"code":"EXCAVATOR_06W","qty":2}],
                 "checklist":%s,"remarks":"합성: 오후 강풍 예보로 살수 2회 추가","deviceSignedAt":"2026-10-15T08:42:10Z",
                 "submit":%s}""".formatted(clientOpId, checklist, submit);
    }

    private MockHttpServletRequestBuilder logPut(UUID wp, LocalDate date, String body, UUID eng) {
        return as(putJson("/api/v1/dem/daily-logs/" + wp + "/" + date, body), eng, "ENG").header("X-Client-Id", "field-app");
    }

    @Test
    @DisplayName("TC-DEM-004 · 오프라인 동기화 · 증거 사진: 사진 필수 항목 사진 없이 PASS → 422 DEM-422-004, 같은 clientOpId 재전송 → "
            + "같은 응답(Idempotent-Replayed, 재처리 0), FAIL → 시정지시(CRITICAL 작업중지), 미지정 ENG 403, If-Match 불일치 412, "
            + "사진 해시 위조 409 + 보안 감사, TC-DEM-009 원본 변조 → verify MISMATCH")
    void tcDem004_dailyLogOfflineSyncAndEvidence() throws Exception {
        var f = demProject();
        UUID eng = engineer(true);
        assign(f.supervisionWp(), eng, "CHIEF", today().minusDays(3), null, 201);

        // 사진 먼저 동기화(core 첨부 → DEM 증거 등록)
        JsonNode att1 = upload(eng, 1);
        JsonNode att2 = upload(eng, 2);
        UUID p1 = registerPhoto(eng, f.supervisionWp(), att1, 201);
        UUID p2 = registerPhoto(eng, f.supervisionWp(), att2, 201);
        assertThat(registerPhoto(eng, f.supervisionWp(), att1, 200)).isEqualTo(p1);   // 재등록 = 기존 id(멱등)
        // 보안 검토 2026-10-19: 다른 패키지로 같은 사진 등록 → 409(그 패키지 사진 정보 비노출),
        // 남이 올린 첨부 · DEM 외 모듈 첨부는 422
        mvc.perform(as(postJson("/api/v1/dem/evidence/photos", """
                        {"attachmentId":"%s","sha256":"%s","workPackageId":"%s"}""".formatted(
                        att1.get("attachmentId").asText(), att1.get("sha256").asText(), f.designWp())), eng, "ENG"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-008"))
                .andExpect(jsonPath("$.id").doesNotExist());
        JsonNode attOther = upload(eng, 7);
        mvc.perform(as(postJson("/api/v1/dem/evidence/photos", """
                        {"attachmentId":"%s","sha256":"%s","workPackageId":"%s"}""".formatted(
                        attOther.get("attachmentId").asText(), attOther.get("sha256").asText(), f.supervisionWp())),
                        engineer(true), "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEM-422-001"));
        jdbc.update("UPDATE core.attachment SET owner_module = 'FLD' WHERE id = ?::uuid", attOther.get("attachmentId").asText());
        mvc.perform(as(postJson("/api/v1/dem/evidence/photos", """
                        {"attachmentId":"%s","sha256":"%s","workPackageId":"%s"}""".formatted(
                        attOther.get("attachmentId").asText(), attOther.get("sha256").asText(), f.supervisionWp())),
                        eng, "ENG"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("DEM-422-001"));
        assertThat(events("dem.evidence-photo.registered", p1)).isEqualTo(1);
        assertThat(lastEvent("dem.evidence-photo.registered", p1).get("sha256").asText())
                .isEqualTo(att1.get("sha256").asText());

        // 해시 위조(기기 해시 ≠ 서버 해시) → 409 + 보안 감사(롤백돼도 남음)
        JsonNode att3 = upload(eng, 3);
        mvc.perform(as(postJson("/api/v1/dem/evidence/photos", """
                        {"attachmentId":"%s","sha256":"%s","workPackageId":"%s"}""".formatted(
                        att3.get("attachmentId").asText(), "f".repeat(64), f.supervisionWp())), eng, "ENG"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-007"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.audit_log WHERE module = 'DEM' AND target_id = ?::uuid "
                + "AND after_json::text LIKE '%EVIDENCE_HASH_MISMATCH%'", Integer.class, att3.get("attachmentId").asText()))
                .isEqualTo(1);

        LocalDate d = today();
        // TC-DEM-004: 사진 필수 항목(안전난간)을 사진 없이 PASS
        String noPhoto = "[{\"templateItemId\":\"%s\",\"result\":\"PASS\"},{\"templateItemId\":\"%s\",\"result\":\"PASS\"}]"
                .formatted(ITEM_GUARDRAIL, ITEM_SEQUENCE);
        mvc.perform(logPut(f.supervisionWp(), d, logBody(UUID.randomUUID(), noPhoto, false), eng))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("DEM-422-004"))
                .andExpect(jsonPath("$.reason").value("DEM_PHOTO_REQUIRED"))
                .andExpect(jsonPath("$.invalidItems[0]").value(ITEM_GUARDRAIL));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dem.daily_log WHERE work_package_id = ?", Integer.class,
                f.supervisionWp())).isZero();

        // 정상: 사진 첨부 PASS + CRITICAL FAIL(잔재물 적재 높이)
        String checklist = """
                [{"templateItemId":"%s","result":"PASS","photoIds":["%s"]},
                 {"templateItemId":"%s","result":"FAIL","note":"합성: 잔재물 적재높이 1.8m(기준 1.0m 초과)","photoIds":["%s"]},
                 {"templateItemId":"%s","result":"PASS"}]""".formatted(ITEM_GUARDRAIL, p1, ITEM_DEBRIS, p2, ITEM_SEQUENCE);
        UUID op = UUID.randomUUID();
        var first = mvc.perform(logPut(f.supervisionWp(), d, logBody(op, checklist, true), eng))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"v0\""))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.correctiveOrders.length()").value(1))
                .andExpect(jsonPath("$.correctiveOrders[0].severity").value("CRITICAL"))
                .andExpect(jsonPath("$.correctiveOrders[0].stopWorkRequested").value(true))
                .andExpect(jsonPath("$.replayed").value(false)).andReturn();
        JsonNode body = json(first);
        UUID logId = UUID.fromString(body.get("id").asText());
        assertThat(body.at("/correctiveOrders/0/orderNo").asText()).matches("CO-\\d{4}-\\d{4}");
        assertThat(events("dem.daily-log.submitted", logId)).isEqualTo(1);
        assertThat(lastEvent("dem.daily-log.submitted", logId).get("failCount").asInt()).isEqualTo(1);

        // 오프라인 재전송(같은 clientOpId) → 저장된 같은 응답, 시정지시 · 이벤트 · 버전 변화 0
        mvc.perform(logPut(f.supervisionWp(), d, logBody(op, checklist, true), eng))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.correctiveOrders[0].orderNo").value(body.at("/correctiveOrders/0/orderNo").asText()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dem.corrective_order co JOIN dem.checklist_result cr "
                + "ON cr.id = co.checklist_result_id WHERE cr.daily_log_id = ?", Integer.class, logId)).isEqualTo(1);
        assertThat(events("dem.daily-log.submitted", logId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM dem.daily_log WHERE id = ?", Long.class, logId)).isZero();

        // 새 작업 + 낡은 If-Match → 412, 맞는 If-Match → 200 v1
        mvc.perform(logPut(f.supervisionWp(), d, logBody(UUID.randomUUID(), checklist, true), eng).header("If-Match", "\"v7\""))
                .andExpect(status().is(412)).andExpect(jsonPath("$.code").value("CORE-412-001"));
        mvc.perform(logPut(f.supervisionWp(), d, logBody(UUID.randomUUID(), checklist, true), eng).header("If-Match", "\"v0\""))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"v1\""));
        // 지정되지 않은 기술자 → 403
        UUID stranger = engineer(true);
        mvc.perform(logPut(f.supervisionWp(), d, logBody(UUID.randomUUID(), checklist, false), stranger))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("DEM-403-001"));
        mvc.perform(as(get("/api/v1/dem/daily-logs/" + logId), pm, "PM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.checklist.length()").value(3))
                .andExpect(jsonPath("$.checklist[0].photoIds[0]").value(p1.toString()));
        // 보안 검토 2026-10-19: 일지 열람 범위 — 투입 안 된 PM · ENG 403, 지정 감리자(작성자) 200,
        // 다른 고객사 CUSTOMER 403, 발주 고객사 CUSTOMER 200
        mvc.perform(as(get("/api/v1/dem/daily-logs/" + logId), actor(), "PM"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CORE-403-002"));
        mvc.perform(as(get("/api/v1/dem/daily-logs/" + logId), stranger, "ENG")).andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/dem/daily-logs/" + logId), eng, "ENG")).andExpect(status().isOk());
        UUID owner = jdbc.queryForObject("SELECT client_id FROM core.project WHERE id = ?", UUID.class, f.projectId());
        mvc.perform(as(get("/api/v1/dem/daily-logs/" + logId), UUID.randomUUID(), "CUSTOMER")
                        .header("X-Client-Org-Id", UUID.randomUUID().toString()))
                .andExpect(status().isForbidden());
        mvc.perform(as(get("/api/v1/dem/daily-logs/" + logId), UUID.randomUUID(), "CUSTOMER")
                        .header("X-Client-Org-Id", owner.toString()))
                .andExpect(status().isOk());

        // TC-DEM-009: 업로드 후 원본 1바이트 변조(로컬 WORM 모사를 우회) → verify MISMATCH
        mvc.perform(as(get("/api/v1/dem/evidence/photos/" + p2 + "/verify"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("MATCH"));
        String bucket = jdbc.queryForObject("SELECT bucket FROM core.attachment WHERE id = ?::uuid", String.class,
                att2.get("attachmentId").asText());
        Path original = EVIDENCE_ROOT.resolve(bucket).resolve(att2.get("objectKey").asText());
        byte[] bytes = Files.readAllBytes(original);
        bytes[bytes.length - 1] ^= 0x01;
        Files.write(original, bytes);
        mvc.perform(as(get("/api/v1/dem/evidence/photos/" + p2 + "/verify"), pm, "QA"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result").value("MISMATCH"))
                .andExpect(jsonPath("$.includedInEvidencePackage").value(false));
        assertThat(jdbc.queryForObject("SELECT last_verify FROM dem.evidence_photo WHERE id = ?", String.class, p2))
                .isEqualTo("MISMATCH");
    }

    @Test
    @DisplayName("TC-DEM-005 · BR-DEM-006: 자정 경과 → 자동 잠금(정규화 해시), 잠금 후 동기화 → 202 Amendment 초안 · 원문 해시 불변, PM 정정은 append-only 유효 뷰")
    void tcDem005_lockAndAmendment() throws Exception {
        var f = demProject();
        UUID eng = engineer(true);
        assign(f.supervisionWp(), eng, "CHIEF", today().minusDays(3), null, 201);
        LocalDate d = today();
        String checklist = "[{\"templateItemId\":\"%s\",\"result\":\"PASS\"}]".formatted(ITEM_SEQUENCE);
        UUID logId = UUID.fromString(json(mvc.perform(logPut(f.supervisionWp(), d, logBody(UUID.randomUUID(), checklist,
                true), eng)).andExpect(status().isOk()).andReturn()).get("id").asText());

        clock.advance(Duration.ofDays(1).plusHours(1));   // 다음 날 01:00 이후
        var lock = mvc.perform(as(postJson("/api/v1/dem/daily-logs:lock-due", "{}"), admin, "ADMIN"))
                .andExpect(status().isOk()).andReturn();
        assertThat(json(lock).get("locked").asInt()).isGreaterThanOrEqualTo(1);
        var locked = jdbc.queryForMap("SELECT status, content_sha256 FROM dem.daily_log WHERE id = ?", logId);
        assertThat(locked).containsEntry("status", "LOCKED");
        String sha = (String) locked.get("content_sha256");
        assertThat(sha).hasSize(64);
        assertThat(lastEvent("dem.daily-log.locked", logId).get("contentSha256").asText()).isEqualTo(sha);

        // 잠금 후 도착한 오프라인 동기화 → Amendment 초안(PENDING), 원문 · 해시 불변
        UUID late = UUID.randomUUID();
        String changed = logBody(late, checklist, true).replace("\"progressPct\":45", "\"progressPct\":60");
        mvc.perform(logPut(f.supervisionWp(), d, changed, eng))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amendmentPending").value(true))
                .andExpect(jsonPath("$.contentSha256").value(sha));
        mvc.perform(logPut(f.supervisionWp(), d, changed, eng))   // 재전송도 같은 202
                .andExpect(status().isAccepted()).andExpect(header().string("Idempotent-Replayed", "true"));
        assertThat(jdbc.queryForMap("SELECT progress_pct, content_sha256 FROM dem.daily_log WHERE id = ?", logId))
                .containsEntry("progress_pct", 45).containsEntry("content_sha256", sha);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM dem.log_amendment WHERE daily_log_id = ? AND status = 'PENDING'",
                Integer.class, logId)).isEqualTo(1);

        // PM 정정 → 원문 그대로, 유효 뷰에만 반영 · 정정 이력 노출
        mvc.perform(as(postJson("/api/v1/dem/daily-logs/" + logId + "/amendments",
                        "{\"fieldPath\":\"remarks\",\"newValue\":\"합성: 살수 3회로 정정\",\"reason\":\"현장 기록 대조 결과 정정\"}"),
                        pm, "PM"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remarks").value("합성: 오후 강풍 예보로 살수 2회 추가"))
                .andExpect(jsonPath("$.effective.remarks").value("합성: 살수 3회로 정정"))
                .andExpect(jsonPath("$.amendments.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT content_sha256 FROM dem.daily_log WHERE id = ?", String.class, logId))
                .isEqualTo(sha);
        // 잠금 일지 직접 수정 · 삭제는 DB 가 거부(TC-DEM-014 와 같은 트리거)
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update("UPDATE dem.daily_log SET remarks = 'x' WHERE id = ?", logId))
                .hasMessageContaining("DEM_IMMUTABLE");
    }

    // ------------------------------------------------------------------ 도서 · Transmittal · 검토의견

    private UUID fileAttachment() {
        UUID id = UUID.randomUUID();
        int n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO core.attachment(id, bucket, object_key, file_name, mime_type, size_bytes, sha256, chain_hash, "
                + "owner_module, owner_type, owner_id, chain_scope, created_by) VALUES (?, 'drawings', ?, ?, 'application/pdf', "
                + "10, ?, ?, 'DEM', 'Revision', ?, ?, ?)", id, "dem/it-" + n + ".pdf", "it-" + n + ".pdf",
                "%064x".formatted(n), "%064x".formatted(n + 7), id, UUID.randomUUID(), id);
        return id;
    }

    private UUID newApprovedRevision(UUID doc, UUID model) throws Exception {
        var r = json(mvc.perform(as(postJson("/api/v1/dem/documents/" + doc + "/revisions", """
                        {"fileAttachmentId":"%s","changeSummary":"합성 변경"%s}""".formatted(fileAttachment(),
                        model == null ? "" : ",\"modelVersionId\":\"" + model + "\"")), actor(), "ENG"))
                .andExpect(status().isCreated()).andReturn());
        UUID rev = UUID.fromString(r.get("id").asText());
        mvc.perform(as(postJson("/api/v1/dem/revisions/" + rev + ":request-review", "{}"), actor(), "ENG"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("INTERNAL_REVIEW"));
        mvc.perform(as(postJson("/api/v1/dem/revisions/" + rev + ":approve", "{}"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        return rev;
    }

    private org.springframework.test.web.servlet.ResultActions issue(UUID wp, String purpose, UUID rev) throws Exception {
        return mvc.perform(as(postJson("/api/v1/dem/transmittals", """
                {"workPackageId":"%s","purpose":"%s","revisionIds":["%s"],"recipientName":"합성 발주처"}"""
                .formatted(wp, purpose, rev)), pm, "PM"));
    }

    private void reply(UUID comment, String status, String role) throws Exception {
        mvc.perform(as(postJson("/api/v1/dem/comments/" + comment + "/replies",
                "{\"body\":\"합성 회신 " + status + "\",\"resultingStatus\":\"" + status + "\"}"), actor(), role))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value(status));
    }

    @Test
    @DisplayName("TC-DEM-007 · 008 · BR-DEM-009: 미결 의견 OPEN · RESPONDED · REJECTED 각각 → FOR_CONSTRUCTION 409 DEM-409-002, FOR_REVIEW 는 허용, "
            + "의견 종결 후 최종 발행 · 이전 판 SUPERSEDED · 해석모델 동결, ISSUED 파일 교체 409 DEM-409-001")
    void tcDem007_transmittalAndComments() throws Exception {
        var f = demProject();
        UUID wp = f.designWp();
        UUID doc = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/work-packages/" + wp + "/documents",
                        "{\"docNo\":\"S-201\",\"title\":\"합성 2F 골조 평면도\"}"), actor(), "ENG"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        UUID model = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/work-packages/" + wp + "/model-versions", """
                        {"software":"MIDAS Gen (합성)","fileAttachmentId":"%s","designCode":"KDS 41","changeSummary":"최초 모델",
                         "loadCombinations":{"LC1":"1.2D+1.6L"}}""".formatted(fileAttachment())), actor(), "ENG"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());

        // Rev.A → 승인 → FOR_REVIEW 발행(Rev.0)
        UUID revA = newApprovedRevision(doc, null);
        issue(wp, "FOR_REVIEW", revA).andExpect(status().isCreated())
                .andExpect(jsonPath("$.transmittalNo").value(org.hamcrest.Matchers.matchesPattern("TR-\\d{4}-\\d{4}")))
                .andExpect(jsonPath("$.revisions[0].issuedRev").value("0"));
        // TC-DEM-008: 발행본 파일 교체 → 409
        mvc.perform(as(postJson("/api/v1/dem/revisions/" + revA + ":replace-file",
                        "{\"fileAttachmentId\":\"" + fileAttachment() + "\"}"), actor(), "ENG"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-001"))
                .andExpect(jsonPath("$.reason").value("DEM_IMMUTABLE"));

        // 발주처 지적 1건(OPEN)
        UUID comment = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/revisions/" + revA + "/comments",
                        "{\"source\":\"CLIENT\",\"severity\":\"MAJOR\",\"body\":\"합성: 보 B3 단면 검토 필요\"}"), actor(), "QA"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.commentNo").value("C-001"))
                .andReturn()).get("id").asText());
        UUID revB = newApprovedRevision(doc, model);
        // TC-DEM-007: OPEN · RESPONDED · REJECTED 각각 최종 발행 409
        issue(wp, "FOR_CONSTRUCTION", revB).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DEM-409-002")).andExpect(jsonPath("$.byStatus.OPEN").value(1));
        reply(comment, "RESPONDED", "ENG");
        issue(wp, "FOR_CONSTRUCTION", revB).andExpect(status().isConflict())
                .andExpect(jsonPath("$.byStatus.RESPONDED").value(1));
        reply(comment, "REJECTED", "QA");
        issue(wp, "FOR_CONSTRUCTION", revB).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DEM_OPEN_COMMENTS_EXIST"))
                .andExpect(jsonPath("$.byStatus.REJECTED").value(1));
        // 표에 없는 전이(REJECTED → CLOSED) 409
        mvc.perform(as(postJson("/api/v1/dem/comments/" + comment + "/replies",
                        "{\"body\":\"닫기\",\"resultingStatus\":\"CLOSED\"}"), actor(), "QA"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-004"));
        // FOR_REVIEW 는 미결 의견이 있어도 발행 허용 → Rev.B = 1, Rev.A SUPERSEDED, 참조 모델 동결
        issue(wp, "FOR_REVIEW", revB).andExpect(status().isCreated()).andExpect(jsonPath("$.revisions[0].issuedRev").value("1"));
        assertThat(jdbc.queryForObject("SELECT status FROM dem.document_revision WHERE id = ?", String.class, revA))
                .isEqualTo("SUPERSEDED");
        assertThat(jdbc.queryForObject("SELECT frozen FROM dem.analysis_model_version WHERE id = ?", Boolean.class, model))
                .isTrue();

        // 재회신 → 수용 → 종결 → Rev.C 최종 발행(For Construction)
        reply(comment, "RESPONDED", "ENG");
        reply(comment, "ACCEPTED", "QA");
        reply(comment, "CLOSED", "QA");
        assertThat(events("dem.comment.closed", comment)).isEqualTo(1);
        UUID revC = newApprovedRevision(doc, null);
        var tr = json(issue(wp, "FOR_CONSTRUCTION", revC).andExpect(status().isCreated())
                .andExpect(jsonPath("$.revisions[0].issuedRev").value("2")).andReturn());
        UUID trId = UUID.fromString(tr.get("id").asText());
        assertThat(lastEvent("dem.revision.issued", trId).get("purpose").asText()).isEqualTo("FOR_CONSTRUCTION");
        assertThat(jdbc.queryForList("SELECT status FROM dem.document_revision WHERE document_id = ? ORDER BY rev_code",
                String.class, doc)).containsExactly("SUPERSEDED", "SUPERSEDED", "ISSUED");
        mvc.perform(as(get("/api/v1/dem/documents/" + doc), pm, "PM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.unresolvedComments").value(0))
                .andExpect(jsonPath("$.revisions.length()").value(3));
        // 승인 전(WIP) 리비전 발행 → 409
        UUID wip = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/documents/" + doc + "/revisions",
                        "{\"fileAttachmentId\":\"" + fileAttachment() + "\"}"), actor(), "ENG"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.revCode").value("D")).andReturn()).get("id").asText());
        issue(wp, "FOR_REVIEW", wip).andExpect(status().isConflict())
                .andExpect(jsonPath("$.reason").value("DEM_REVISION_NOT_APPROVED"));
    }

    // ------------------------------------------------------------------ 해체 완료 → FAC · 종료

    @Test
    @DisplayName("BR-DEM-001 · v0.3: 책임감리자 없이 착공 409 → 지정 후 착공 → 해체 완료 신고 → dem.demolition.completed(buildingId) → "
            + "FAC 시설물 DEMOLISHED · 점검 도래 CANCELLED(실제 Relay 흐름, 멱등) → 종료 dem.project.closed")
    void demolitionCompletedUpdatesFacility() throws Exception {
        var f = demProject();
        toPermitPending(f.projectId());
        mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId() + ":transition", "{\"to\":\"IN_PROGRESS\"}"), pm, "PM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DEM-409-005"))
                .andExpect(jsonPath("$.workPackageIds[0]").value(f.supervisionWp().toString()));
        transition(f.projectId(), "COMPLETING", 422);   // 완료는 전용 명령만
        UUID chief = engineer(true);
        assign(f.supervisionWp(), chief, "CHIEF", today(), null, 201);
        transition(f.projectId(), "IN_PROGRESS", 200);

        var sub = json(mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId() + "/submissions", """
                        {"authorityName":"합성시청 건축과","submissionType":"COMPLETION","receiptNo":"SYN-2026-0001"}"""),
                        pm, "PM")).andExpect(status().isCreated()).andExpect(jsonPath("$.seq").value(1)).andReturn());
        mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId() + ":complete-demolition", """
                        {"completedOn":"%s","submissionId":"%s"}""".formatted(today(), sub.get("id").asText())), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETING"));
        JsonNode ev = lastEvent("dem.demolition.completed", f.projectId());
        assertThat(ev.get("buildingId").asText()).isEqualTo(f.buildingId().toString());
        assertThat(ev.get("workPackageId").asText()).isEqualTo(f.supervisionWp().toString());
        assertThat(ev.get("submissionId").asText()).isEqualTo(sub.get("id").asText());

        drain();
        assertThat(jdbc.queryForObject("SELECT status FROM fac.building WHERE id = ?", String.class, f.buildingId()))
                .isEqualTo("DEMOLISHED");
        assertThat(jdbc.queryForList("SELECT status FROM fac.inspection_due WHERE building_id = ?", String.class,
                f.buildingId())).containsOnly("CANCELLED");
        assertThat(events("fac.building.demolished", f.buildingId())).isEqualTo(1);
        // 같은 이벤트 재배달(Relay 재시도 모사) → 멱등: 처리 1회
        UUID eventId = jdbc.queryForObject("SELECT id FROM core.outbox_event WHERE event_type = 'dem.demolition.completed' "
                + "AND aggregate_id = ?", UUID.class, f.projectId());
        jdbc.update("UPDATE core.outbox_event SET published_at = NULL, next_attempt_at = now() WHERE id = ?", eventId);
        drain();
        assertThat(events("fac.building.demolished", f.buildingId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event WHERE consumer = 'fac.demolition-recorder' "
                + "AND event_id = ?", Integer.class, eventId)).isEqualTo(1);

        mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId() + ":close", "{}"), pm, "PM"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CLOSED"));
        assertThat(lastEvent("dem.project.closed", f.projectId()).get("buildingId").asText())
                .isEqualTo(f.buildingId().toString());
        transition(f.projectId(), "PLANNING", 409);   // CLOSED 이후 전이 없음
    }

    @Test
    @DisplayName("FR-DEM-018: 인접건축물 사전/사후 조사 비교 저장(진전 · 신규 · 동일 · 보수/미확인, 결과 SHA-256 · 규칙 고정), 도면 버전 불일치 422")
    void adjacentComparison() throws Exception {
        var f = demProject();
        String pre = """
                {"adjacentName":"합성 인접 상가","phase":"PRE","surveyedOn":"%s","drawingVersion":"A-01","defects":[
                 {"defectNo":"P-1","floor":"1F","memberRef":"W1","defectType":"CRACK","x":1.0,"y":1.0,"crackWidthMm":0.20,"lengthMm":500},
                 {"defectNo":"P-2","floor":"1F","memberRef":"W1","defectType":"CRACK","x":3.0,"y":1.0,"crackWidthMm":0.30,"lengthMm":800},
                 {"defectNo":"P-3","floor":"2F","memberRef":"C1","defectType":"LEAK","x":2.0,"y":2.0}]}"""
                .formatted(today().minusDays(60));
        String post = """
                {"adjacentName":"합성 인접 상가","phase":"POST","surveyedOn":"%s","drawingVersion":"A-01","defects":[
                 {"defectNo":"Q-1","floor":"1F","memberRef":"W1","defectType":"CRACK","x":1.1,"y":1.0,"crackWidthMm":0.22,"lengthMm":510},
                 {"defectNo":"Q-2","floor":"1F","memberRef":"W1","defectType":"CRACK","x":3.0,"y":1.1,"crackWidthMm":0.45,"lengthMm":800},
                 {"defectNo":"Q-3","floor":"1F","memberRef":"W1","defectType":"CRACK","x":8.0,"y":1.0,"crackWidthMm":0.10,"lengthMm":100}]}"""
                .formatted(today());
        UUID preId = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId()
                + "/adjacent-surveys", pre), actor(), "ENG")).andExpect(status().isCreated()).andReturn()).get("id").asText());
        UUID postId = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId()
                + "/adjacent-surveys", post), actor(), "ENG")).andExpect(status().isCreated()).andReturn()).get("id").asText());
        mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId() + "/adjacent-comparisons",
                        "{\"preSurveyId\":\"%s\",\"postSurveyId\":\"%s\"}".formatted(preId, postId)), actor(), "ENG"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.summary.SAME").value(1))
                .andExpect(jsonPath("$.summary.PROGRESSED").value(1))
                .andExpect(jsonPath("$.summary.NEW").value(1))
                .andExpect(jsonPath("$.summary.REPAIRED_OR_UNCONFIRMED").value(1))
                .andExpect(jsonPath("$.thresholds.widthGrowthMm").value(0.1))
                .andExpect(jsonPath("$.resultSha256").value(org.hamcrest.Matchers.matchesPattern("[0-9a-f]{64}")));
        UUID otherDrawing = UUID.fromString(json(mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId()
                + "/adjacent-surveys", post.replace("A-01", "A-02")), actor(), "ENG"))
                .andExpect(status().isCreated()).andReturn()).get("id").asText());
        mvc.perform(as(postJson("/api/v1/dem/projects/" + f.projectId() + "/adjacent-comparisons",
                        "{\"preSurveyId\":\"%s\",\"postSurveyId\":\"%s\"}".formatted(preId, otherDrawing)), actor(), "ENG"))
                .andExpect(status().is(422)).andExpect(jsonPath("$.reason").value("DEM_DRAWING_VERSION_MISMATCH"));
    }
}
