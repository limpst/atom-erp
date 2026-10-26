package com.atomeng.erp;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxRelay;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.erp.fcd.domain.ingest.PanTools;
import com.atomeng.erp.fld.domain.Hlc;
import com.atomeng.erp.rpt.domain.hwpx.HwpxValidator;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 전체 업무 흐름 시험(골든 패스) — 한 회사의 일 하나가 영업부터 정산까지 모듈을 건너가는 과정을
 * <b>공개 HTTP API + 실제 Outbox Relay</b> 로만 재현한다(README 6장 순서도).
 *
 * <ol>
 *   <li>SAL 기회 → 견적(대가 엔진) → 한도 안 할인 → 발행 → 계약 결재(ready-to-sign) → 서명 ⇒ 프로젝트 + CST 실행예산 초안</li>
 *   <li>FAC 주소 조회(WireMock 공공 API) 등록 → 분류 → 점검 주기 계산</li>
 *   <li>HRQ 직원 · 자격 → 프로젝트 투입(책임 · 참여) → 타임시트 제출 · 승인 ⇒ CST 인건비 → 정정 1건 ⇒ 역분개 + 재전기</li>
 *   <li>EQP 검교정 장비 예약 · 반출 · 반납 ⇒ CST 장비 손료</li>
 *   <li>FLD 조사 회차 · 결함 · 측정(장비 id) · 사진 청크 업로드 검증 · 모든 구역 잠금 ⇒ fld.survey.completed</li>
 *   <li>RPT fld::api 스냅샷 · 등급 산정 · HWPX 렌더 · 검토 요청</li>
 *   <li>QAR 검토 라운드 · 자동검사 · 승인 ⇒ RPT 발행 ⇒ FAC 등급 · PTL 공개본</li>
 *   <li>PTL 고객 열람 · 워터마크 다운로드, 다른 고객사는 404</li>
 *   <li>FCD 명세서 CSV(쪼개기 결제 1건) ⇒ 케이스 ⇒ 소명 ⇒ 정산 ⇒ CST 경비 ⇒ 전표 동기화</li>
 *   <li>최종: CST 수익성 · EVM = 인건비 + 장비 + 카드 경비(BigDecimal 정확 일치), 감사 · 증거 해시 체인, PAN 0건,
 *       outbox 전량 처리(DLQ 0), 모듈 경계 위반 0</li>
 * </ol>
 * DB 직접 쓰기는 하지 않는다(시작 시 outbox 비우기만 — 다른 시험이 남긴 이벤트와 섞이지 않게). 모든 데이터는 합성값.
 */
class GoldenPathScenarioTest extends IntegrationTestBase {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Charset CP949 = Charset.forName("x-windows-949");
    /** CST 표준 노임 일 단가가 아직 없을 때만 등록(CstIntegrationTest 와 같은 합성 값 ⚠️ 실제 노임단가 아님) */
    private static final Map<String, Long> STD = Map.of("JUNIOR", 200_000L, "INTER", 250_000L, "SENIOR", 300_000L,
            "MASTER", 380_000L);

    @Autowired
    OutboxRelay relay;
    @Autowired
    AuditLog auditLog;

    private final Map<String, Long> stepMillis = new LinkedHashMap<>();
    private long stepStart;
    private String stepName;

    // 사람(합성)
    UUID admin;
    UUID exec;
    UUID fin;
    UUID auditor;
    UUID sales;
    UUID pm;
    UUID eng;
    UUID qa;
    UUID customer;

    @BeforeEach
    void setUp() {
        clock.reset();
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
        admin = actor();
        exec = actor();
        fin = actor();
        auditor = actor();
        customer = actor();
    }

    @AfterEach
    void tearDown() {
        clock.reset();
    }

    // ================================================================== 시나리오

    @Test
    @DisplayName("골든 패스: SAL → FAC → HRQ → EQP → FLD → RPT → QAR → PTL → FCD → CST 수익성 · EVM 정확 일치, 해시 체인 · PAN 0 · DLQ 0 · 경계 0")
    void wholeCompanyWorkflow() throws Exception {
        long t0 = System.nanoTime();
        LocalDate today = today();
        LocalDate workMon = today.minusWeeks(1).with(DayOfWeek.MONDAY);
        LocalDate workTue = workMon.plusDays(1);

        // ------------------------------------------------------------ 0. 사람 (HRQ 수동 등록 = 직원 대장)
        step("0 HRQ 직원 등록");
        sales = hrqEmployee("영업", "OTHER", null);
        pm = hrqEmployee("책임기술자", "ARCH", "SENIOR");
        eng = hrqEmployee("참여기술자", "ARCH", "INTER");
        qa = hrqEmployee("품질검토자", "ARCH", "SENIOR");
        // QA 검토자 등록(관리 API, V0037) — 수동 배정도 활성 검토자 프로필이 있어야 한다(보안 검토 SEC-QAR-02)
        call(postJson("/api/v1/qar/reviewer-profiles", json(map("employeeId", qa, "qualifications",
                List.of("(합성) 건축구조 검토 자격")))), admin, "ADMIN", 201);

        // ------------------------------------------------------------ 1. SAL
        step("1 SAL 기회 → 계약 → 프로젝트 · 예산 초안");
        UUID client = createClient(sales, "합성발주처-golden-" + UUID.randomUUID());
        JsonNode opp = call(postJson("/api/v1/sal/opportunities", json(map("source", "INQUIRY", "clientId", client,
                "serviceType", "PRECISE_DIAG", "title", "합성 골든 정밀점검 문의", "ownerId", sales))), sales, "SALES", 201);
        UUID oppId = id(opp);
        long v = opp.get("version").asLong();
        v = call(ifMatch(postJson("/api/v1/sal/opportunities/" + oppId + ":move", json(map("toStage", "QUALIFYING"))), v),
                sales, "SALES", 200).get("version").asLong();
        call(ifMatch(postJson("/api/v1/sal/opportunities/" + oppId + ":move", json(map("toStage", "PROPOSING"))), v),
                sales, "SALES", 200);
        JsonNode fee = call(postJson("/api/v1/sal/fee-calculations", json(feeRequest(today, null))), sales, "SALES", 200);
        BigDecimal subtotal = fee.get("subtotal").decimalValue();
        JsonNode est = call(postJson("/api/v1/sal/opportunities/" + oppId + "/estimates",
                json(feeRequest(today, new BigDecimal("0.03")))), sales, "SALES", 201);
        UUID estId = id(est);
        assertThat(est.get("subtotal").decimalValue()).isEqualByComparingTo(subtotal);   // 대가 엔진 = 견적 합계
        BigDecimal offer = subtotal.multiply(new BigDecimal("0.97")).divide(new BigDecimal("1000"), 0, RoundingMode.FLOOR)
                .multiply(new BigDecimal("1000"));
        assertThat(est.get("offerAmount").decimalValue()).isEqualByComparingTo(offer);  // 천 원 절사
        JsonNode issued = call(postJson("/api/v1/sal/estimates/" + estId + ":issue", "{}"), sales, "SALES", 200);
        assertThat(issued.get("status").asText()).isEqualTo("ISSUED");                 // 3% ≤ SALES 한도 5% → 승인 불요
        call(postJson("/api/v1/sal/estimates/" + estId + ":send", "{}"), sales, "SALES", 200);
        call(postJson("/api/v1/sal/estimates/" + estId + ":accept", "{}"), sales, "SALES", 200);
        JsonNode contract = call(postJson("/api/v1/sal/contracts", json(map("opportunityId", oppId, "estimateId", estId,
                "title", "합성 골든 정밀안전점검 용역", "contractMethod", "PRIVATE_SECTOR", "amount", offer,
                "signDate", today.toString(), "startDate", today.toString(), "endDate", today.plusDays(120).toString()))),
                sales, "SALES", 201);
        UUID contractId = id(contract);
        JsonNode pending = call(ifMatch(postJson("/api/v1/sal/contracts/" + contractId + ":approve",
                json(map("signers", List.of("합성 발주처 대표")))), contract.get("version").asLong()), exec, "EXEC", 200);
        assertThat(pending.get("status").asText()).isEqualTo("PENDING");
        assertThat(outboxCount("sal.contract.ready-to-sign", null)).isEqualTo(1);
        JsonNode signed = call(ifMatch(postJson("/api/v1/sal/contracts/" + contractId + ":sign",
                json(map("signedOn", today.toString()))), pending.get("version").asLong()), sales, "SALES", 200);
        assertThat(signed.get("status").asText()).isEqualTo("APPROVED");
        UUID project = UUID.fromString(signed.get("projectId").asText());
        drain();
        JsonNode budgets = call(get("/api/v1/cst/projects/" + project + "/budget-revisions"), exec, "EXEC", 200);
        assertThat(budgets).hasSize(1);
        JsonNode draft = budgets.get(0);
        assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.get("revNo").asInt()).isZero();
        assertThat(draft.get("contractAmountKrw").decimalValue()).isEqualByComparingTo(offer);
        // CST-01: DRAFT 직접 결재 409 → 상신(ADMIN) → 상신자 본인 결재 403(maker-checker) → EXEC 결재
        call(ifMatch(postJson("/api/v1/cst/projects/" + project + "/budget-revisions/"
                + draft.get("id").asText() + ":approve", "{}"), draft.get("version").asLong()), exec, "EXEC", 409);
        JsonNode submitted = call(ifMatch(postJson("/api/v1/cst/projects/" + project + "/budget-revisions/"
                + draft.get("id").asText() + ":submit", "{}"), draft.get("version").asLong()), admin, "ADMIN", 200);
        assertThat(submitted.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(call(ifMatch(postJson("/api/v1/cst/projects/" + project + "/budget-revisions/"
                + draft.get("id").asText() + ":approve", "{}"), submitted.get("version").asLong()), admin, "ADMIN", 403)
                .get("code").asText()).isEqualTo("CST-403-002");
        JsonNode approvedBudget = call(ifMatch(postJson("/api/v1/cst/projects/" + project + "/budget-revisions/"
                + draft.get("id").asText() + ":approve", "{}"), submitted.get("version").asLong()), exec, "EXEC", 200);
        assertThat(approvedBudget.get("status").asText()).isEqualTo("APPROVED");
        BigDecimal bac = approvedBudget.get("bacKrw").decimalValue();
        BigDecimal fieldBudget = BigDecimal.ZERO;
        for (JsonNode w : approvedBudget.get("wbs")) {
            if (w.get("code").asText().endsWith("-FIELD")) {
                assertThat(w.get("milestoneEvent").asText()).isEqualTo("fld.survey.completed");
                fieldBudget = w.get("budgetKrw").decimalValue();
            }
        }
        assertThat(fieldBudget).isPositive();

        // ------------------------------------------------------------ 2. FAC
        step("2 FAC 주소 조회 등록 · 점검 주기");
        String bun = stubPublicRegister("합성로 골든 " + ThreadLocalRandom.current().nextInt(1000, 9999));
        JsonNode lookup = call(get("/api/v1/fac/register-lookup").param("address", "합성로 골든 " + bun), pm, "SALES", 200);
        assertThat(lookup.at("/register/available").asBoolean()).isTrue();
        JsonNode bld = call(postJson("/api/v1/fac/buildings", json(map("lookupAddress", "합성로 골든 " + bun,
                "ownerClientId", client))).header("Idempotency-Key", UUID.randomUUID().toString()), pm, "PM", 201);
        UUID building = id(bld);
        assertThat(bld.get("dataSource").asText()).isEqualTo("REGISTER");
        assertThat(bld.get("status").asText()).isEqualTo("VERIFIED");
        call(put("/api/v1/fac/buildings/" + building + "/classification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"sitbeopClass\":\"CLASS2\",\"bmaTarget\":false,\"evidence\":{\"note\":\"합성 근거\"}}"),
                pm, "PM", 200);
        JsonNode dues = call(get("/api/v1/fac/buildings/" + building + "/dues"), pm, "PM", 200);
        assertThat(dues).isNotEmpty();
        for (JsonNode d : dues) {
            assertThat(LocalDate.parse(d.get("dueDate").asText())).isAfter(LocalDate.of(2001, 5, 20));   // 사용승인일 이후
            assertThat(d.get("calcTrace").get("ruleCode").asText()).isNotBlank();   // 주기 엔진 근거
        }
        call(postJson("/api/v1/fac/buildings/" + building + "/nodes:generate",
                "{\"floorFrom\":1,\"floorTo\":2,\"xGrid\":[\"X1\",\"X2\"],\"yGrid\":[\"Y1\",\"Y2\"],\"memberTypes\":[\"COL\"]}"),
                pm, "PM", 201);
        JsonNode tree = call(get("/api/v1/fac/buildings/" + building + "/nodes"), pm, "PM", 200);
        UUID floor1 = nodeId(tree, "1F");
        UUID floor2 = nodeId(tree, "2F");
        UUID col1 = nodeId(tree, "1F-C-X1Y1");
        UUID col2 = nodeId(tree, "1F-C-X2Y2");
        UUID col3 = nodeId(tree, "2F-C-X1Y2");

        // ------------------------------------------------------------ 3. HRQ
        step("3 HRQ 자격 · 투입 · 타임시트 ⇒ CST 인건비 · 정정");
        UUID cred = UUID.fromString(call(postJson("/api/v1/hrq/employees/" + eng + "/credentials",
                "{\"typeCode\":\"SAFETY_DIAG_CERT\",\"certNo\":\"SYN-GOLD-1\",\"acquiredOn\":\"2023-01-01\",\"expiresOn\":\""
                        + today.plusYears(3) + "\"}"), eng, "ENG", 201).get("id").asText());
        assertThat(call(postJson("/api/v1/hrq/credentials/" + cred + ":approve", "{}"), admin, "ADMIN", 200)
                .get("status").asText()).isEqualTo("VALID");
        UUID cred2 = UUID.fromString(call(postJson("/api/v1/hrq/employees/" + pm + "/credentials",
                "{\"typeCode\":\"ARCH_ENG\",\"certNo\":\"SYN-GOLD-2\",\"acquiredOn\":\"2015-01-01\"}"), pm, "ENG", 201)
                .get("id").asText());
        call(postJson("/api/v1/hrq/credentials/" + cred2 + ":approve", "{}"), admin, "ADMIN", 200);
        // 투입: 임원이 책임기술자(RESP) 지정 → 책임기술자가 참여기술자(PART) 지정(PM 은 투입된 프로젝트만)
        call(postJson("/api/v1/core/projects/" + project + "/members", json(map("employeeId", pm,
                "roleInProject", "RESP", "fromDate", today.minusDays(30).toString(),
                "toDate", today.plusDays(150).toString()))), exec, "EXEC", 201);
        call(postJson("/api/v1/core/projects/" + project + "/members", json(map("employeeId", eng,
                "roleInProject", "PART", "fromDate", today.minusDays(30).toString(),
                "toDate", today.plusDays(150).toString()))), UUID.randomUUID(), "PM", 403);   // 비투입 PM
        call(postJson("/api/v1/core/projects/" + project + "/members", json(map("employeeId", eng,
                "roleInProject", "PART", "fromDate", today.minusDays(30).toString(),
                "toDate", today.plusDays(150).toString()))), pm, "PM", 201);
        assertThat(call(get("/api/v1/core/projects/" + project + "/members"), pm, "PM", 200)).hasSize(2);
        assertThat(call(get("/api/v1/core/projects/" + project), eng, "ENG", 200).get("pmId").asText())
                .isEqualTo(pm.toString());
        // 단가(없으면 등록) — 기대 원가는 API 로 읽은 단가로 계산
        ensureStdRates();
        Map<String, BigDecimal> std = stdRates(workMon);
        String isoWeek = "%d-W%02d".formatted(workMon.get(IsoFields.WEEK_BASED_YEAR),
                workMon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        JsonNode week = call(put("/api/v1/hrq/timesheets/" + eng + "/weeks/" + isoWeek)
                .contentType(MediaType.APPLICATION_JSON).content(json(map("submit", true, "entries", List.of(
                        map("projectId", project, "workDate", workMon.toString(), "workType", "FIELD", "hours", 8),
                        map("projectId", project, "workDate", workTue.toString(), "workType", "FIELD", "hours", 8))))),
                eng, "ENG", 200);
        assertThat(week.findValuesAsText("status")).containsOnly("SUBMITTED");
        String e1 = week.get(0).get("id").asText();
        String e2 = week.get(1).get("id").asText();
        call(postJson("/api/v1/hrq/timesheets/approvals", json(map("entryIds", List.of(e1, e2)))), pm, "PM", 200);
        drain();
        JsonNode ledger = call(get("/api/v1/cst/projects/" + project + "/ledger"), exec, "EXEC", 200);
        assertThat(ledger).hasSize(2);
        assertThat(ledger.findValuesAsText("sourceType")).containsOnly("TIMESHEET");
        // 정정 1건: 화요일 8h → 6h (PM, Idempotency-Key) ⇒ 역분개 −8h + 재전기 6h
        String key = UUID.randomUUID().toString();
        call(postJson("/api/v1/hrq/timesheets/" + e2 + ":correct", "{\"hours\":6,\"reason\":\"합성: 현장 조기 종료\"}")
                .header("Idempotency-Key", key), pm, "PM", 201);
        drain();
        ledger = call(get("/api/v1/cst/projects/" + project + "/ledger"), exec, "EXEC", 200);
        assertThat(ledger).hasSize(4);
        List<String> types = ledger.findValuesAsText("sourceType");
        assertThat(types).containsExactlyInAnyOrder("TIMESHEET", "TIMESHEET", "REVERSAL", "TIMESHEET");
        BigDecimal interDaily = std.get("INTER");
        BigDecimal expectedLabor = labor(interDaily, 8).add(labor(interDaily, 8)).subtract(labor(interDaily, 8))
                .add(labor(interDaily, 6));
        assertThat(sumOf(ledger, "amountKrw")).isEqualByComparingTo(expectedLabor);
        assertThat(sumOf(ledger, "amountStdKrw")).isEqualByComparingTo(expectedLabor);   // 실단가 없음 → 표준 대체
        for (JsonNode r : ledger) {
            if (r.get("sourceType").asText().equals("REVERSAL")) {
                assertThat(r.get("hours").decimalValue()).isEqualByComparingTo("-8");
                assertThat(r.get("reversalOf").isNull()).isFalse();
            }
        }

        // ------------------------------------------------------------ 4. EQP
        step("4 EQP 예약 · 반출 · 반납 ⇒ CST 장비 손료");
        JsonNode eq = call(postJson("/api/v1/eqp/equipment", json(map("typeCode", "MSR-CRK", "serialNo",
                "GOLD-" + UUID.randomUUID().toString().substring(0, 8), "model", "합성 균열폭 측정기",
                "homeLocation", "합성 장비실"))), admin, "ADMIN", 201);
        UUID equipment = id(eq);
        String eqCode = eq.get("code").asText();
        call(postJson("/api/v1/eqp/equipment/" + equipment + "/calibrations", json(map("calibratedOn",
                today.minusDays(100).toString(), "nextDueOn", today.plusDays(265).toString(), "result", "PASS",
                "certNo", "KC-GOLD-" + eqCode, "labName", "합성교정기관", "labAccreditationNo", "KC-000"))), admin, "ADMIN", 201);
        assertThat(call(get("/api/v1/eqp/equipment/" + equipment + "/validity?date=" + today), admin, "ADMIN", 200)
                .get("valid").asBoolean()).isTrue();
        BigDecimal equipDaily = new BigDecimal("120000");
        approveRate(call(postJson("/api/v1/cst/rates", json(map("rateKind", "EQUIP_DAILY", "keyCode", eqCode,
                "value", equipDaily, "validFrom", "2000-01-01", "sourceRef", "합성 손료"))), admin, "ADMIN", 201));
        JsonNode res = call(postJson("/api/v1/eqp/reservations", json(map("equipmentId", equipment, "projectId", project,
                "from", today.toString(), "to", today.plusDays(3).toString()))), pm, "PM", 201);
        call(postJson("/api/v1/eqp/reservations/" + id(res) + ":approve", "{}"), pm, "PM", 200);
        String qr = call(postJson("/api/v1/eqp/equipment/" + equipment + "/labels", "{\"rotate\":false}"), admin, "ADMIN",
                200).get("token").asText();
        JsonNode co = call(postJson("/api/v1/eqp/checkouts", json(map("clientOpId", UUID.randomUUID(), "qrToken", qr,
                "projectId", project, "location", map("lat", 37.5251, "lng", 126.9255),
                "preCheck", map("exterior", "OK", "batteryPct", 90)))), eng, "ENG", 200);
        assertThat(co.get("reservationId").asText()).isEqualTo(id(res).toString());
        UUID checkout = UUID.fromString(co.get("checkoutId").asText());
        JsonNode ret = call(postJson("/api/v1/eqp/checkouts/" + checkout + "/return",
                "{\"postCheck\":{\"abnormal\":false}}"), eng, "ENG", 200);
        int usageDays = ret.get("usageDays").asInt();
        assertThat(usageDays).isEqualTo(1);
        drain();
        BigDecimal expectedEquip = equipDaily.multiply(BigDecimal.valueOf(usageDays));
        JsonNode eqRow = ledgerRow(project, "EQUIP");
        assertThat(eqRow.get("costElement").asText()).isEqualTo("EQUIP_USE");
        assertThat(eqRow.get("amountKrw").decimalValue()).isEqualByComparingTo(expectedEquip);

        // ------------------------------------------------------------ 5. FLD
        step("5 FLD 회차 · 결함 · 측정 · 사진 · 잠금 ⇒ fld.survey.completed");
        JsonNode round = call(postJson("/api/v1/fld/rounds", json(map("projectId", project, "buildingId", building,
                "surveyType", "PRECISE_INSPECTION"))), pm, "PM", 201);
        UUID roundId = id(round);
        UUID zone1 = id(call(postJson("/api/v1/fld/rounds/" + roundId + "/zones", json(map("zoneName", "1F-전체",
                "floorNodeId", floor1, "assigneeId", eng))), pm, "PM", 201));
        UUID zone2 = id(call(postJson("/api/v1/fld/rounds/" + roundId + "/zones", json(map("zoneName", "2F-전체",
                "floorNodeId", floor2, "assigneeId", eng))), pm, "PM", 201));
        UUID device = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        JsonNode pushed = push(device, roundId,
                op(d1, "DefectCreated", 0, created(zone1, "CRK", col1)),
                op(d1, "DefectMeasured", 1, measured("width_mm", "0.30", "INSTRUMENT", equipment)),
                op(d1, "DefectMeasured", 2, measured("length_m", "2.40", "VISUAL", null)),
                op(d2, "DefectCreated", 3, created(zone1, "CRK", col2)),
                op(d2, "DefectMeasured", 4, measured("width_mm", "0.20", "INSTRUMENT", equipment)),
                op(d2, "DefectMeasured", 5, measured("length_m", "1.10", "VISUAL", null)),
                op(d3, "DefectCreated", 6, created(zone2, "CRK", col3)),
                op(d3, "DefectMeasured", 7, measured("width_mm", "0.25", "INSTRUMENT", equipment)),
                op(d3, "DefectMeasured", 8, measured("length_m", "1.80", "VISUAL", null)));
        assertThat(pushed.findValuesAsText("status")).containsOnly("ACCEPTED");
        assertThat(pushed.findValuesAsText("calibration")).containsOnly("VALID", "NOT_APPLICABLE")   // 육안 = 장비 없음
                .filteredOn("VALID"::equals).hasSize(3);
        // 사진: 청크 3개 + 청크 체크섬 → 서버 SHA-256 재계산 → core 첨부(WORM) · 회차 해시 체인 VERIFIED
        byte[] photo = jpeg(42);
        String photoSha = sha(photo);
        UUID photoId = UUID.randomUUID();
        push(device, roundId, op(d1, "PhotoAttached", 9, "{\"photoId\":\"%s\",\"sha256\":\"%s\",\"kind\":\"NEAR\",\"sizeBytes\":%d}"
                .formatted(photoId, photoSha, photo.length)));
        JsonNode upload = call(postJson("/api/v1/fld/uploads", json(map("photoId", photoId, "roundId", roundId,
                "defectId", d1, "deviceId", device, "sha256", photoSha, "sizeBytes", photo.length, "kind", "NEAR",
                "takenAt", clock.instant().toString()))), eng, "ENG", 201);
        UUID uploadId = UUID.fromString(upload.get("uploadId").asText());
        int chunk = (photo.length + 2) / 3;
        JsonNode last = null;
        for (int off = 0; off < photo.length; off += chunk) {
            byte[] part = Arrays.copyOfRange(photo, off, Math.min(photo.length, off + chunk));
            last = call(patch("/api/v1/fld/uploads/" + uploadId).contentType("application/offset+octet-stream")
                    .header("Upload-Offset", off).header("Upload-Checksum", "sha256 "
                            + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(sha(part)))).content(part),
                    eng, "ENG", 200);
        }
        assertThat(last.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(last.get("photoState").asText()).isEqualTo("VERIFIED");
        assertThat(call(get("/api/v1/fld/photos/" + photoId + "/verify"), pm, "QA", 200).get("result").asText())
                .isEqualTo("MATCH");
        for (UUID z : List.of(zone1, zone2)) {
            assertThat(call(post("/api/v1/fld/zones/" + z + "/lock"), pm, "PM", 200).get("status").asText())
                    .isEqualTo("LOCKED");
        }
        assertThat(outboxCount("fld.survey.completed", roundId)).isEqualTo(1);
        drain();
        assertThat(call(get("/api/v1/fld/rounds/" + roundId), pm, "PM", 200).get("status").asText())
                .isEqualTo("COMPLETED");

        // ------------------------------------------------------------ 6. RPT
        step("6 RPT 스냅샷 · 등급 · HWPX · 검토 요청");
        JsonNode report = call(postJson("/api/v1/rpt/reports", json(map("projectId", project, "facilityId", building,
                "reportType", "RT_PRC", "title", "합성 골든 정밀안전점검 보고서", "inspectionDate", today.toString()))),
                pm, "PM", 201);
        UUID reportId = id(report);
        UUID rev = UUID.fromString(report.get("currentRevisionId").asText());
        JsonNode frozen = call(postJson("/api/v1/rpt/reports/" + reportId + "/snapshots", json(map(
                "facilityName", "합성 골든 사옥", "fldRoundId", roundId,
                "buildings", List.of(map("code", "B01", "name", "본관", "buildingId", building, "grossAreaM2", 12000.5)),
                "memberGroups", List.of(
                        map("buildingCode", "B01", "floorCode", "1F", "memberType", "COLUMN", "memberCount", 4),
                        map("buildingCode", "B01", "floorCode", "2F", "memberType", "COLUMN", "memberCount", 4))))),
                pm, "PM", 201);
        assertThat(frozen.get("defectCount").asInt()).isEqualTo(3);
        assertThat(frozen.get("blockingCount").asInt()).isZero();
        assertThat(frozen.at("/sourceWatermarks/fld").asText()).isEqualTo("fld::api");
        JsonNode composed = call(post("/api/v1/rpt/revisions/" + rev + "/compose"), pm, "PM", 200);
        String grade = composed.get("computedGrade").asText();
        assertThat(grade).matches("[A-E]");
        call(put("/api/v1/rpt/revisions/" + rev + "/grade/decision").header("If-Match", etag("/api/v1/rpt/revisions/" + rev, pm,
                "PM")).contentType(MediaType.APPLICATION_JSON).content(json(map("finalGrade", grade, "reason", "",
                "evidenceDefectIds", List.of()))), pm, "PM", 200);
        JsonNode render = call(post("/api/v1/rpt/revisions/" + rev + "/render"), pm, "PM", 200);
        assertThat(render.at("/hwpx/tables").asInt()).isPositive();
        byte[] hwpx = mvc.perform(as(get("/api/v1/rpt/revisions/" + rev + "/files/report.hwpx"), pm, "PM")).andReturn()
                .getResponse().getContentAsByteArray();
        assertThat(HwpxValidator.validate(hwpx).errors()).isEmpty();
        call(post("/api/v1/rpt/revisions/" + rev + "/review-request"), pm, "PM", 200);
        assertThat(call(post("/api/v1/rpt/revisions/" + rev + "/publish"), pm, "PM", 409).get("code").asText())
                .isEqualTo("RPT-409-001");   // QAR 승인 전 발행 불가

        // ------------------------------------------------------------ 7. QAR
        step("7 QAR 검토 · 자동검사 · 승인 ⇒ 발행 ⇒ FAC 등급 · PTL 공개본");
        JsonNode review = call(postJson("/api/v1/qar/reviews", "{\"revisionId\":\"" + rev + "\"}"), pm, "PM", 201);
        UUID reviewId = id(review);
        JsonNode run = call(post("/api/v1/qar/reviews/" + reviewId + "/autocheck-runs"), pm, "PM", 201);
        assertThat(run.get("blocking").asInt()).isZero();
        UUID roundQ = UUID.fromString(review.get("round").get("id").asText());
        answerChecklist(roundQ, "SELF", pm, "PM");
        call(post("/api/v1/qar/reviews/" + reviewId + "/submit").header("If-Match", etag("/api/v1/qar/reviews/" + reviewId,
                pm, "PM")), pm, "PM", 200);
        call(post("/api/v1/qar/ai-jobs:run"), qa, "QA", 200);
        assertThat(call(get("/api/v1/qar/reviews/" + reviewId), pm, "PM", 200).get("status").asText())
                .isEqualTo("QA_REVIEWING");
        call(postJson("/api/v1/qar/rounds/" + roundQ + "/assignments", json(map("stepCode", "QA_REVIEW",
                "assigneeId", qa))), qa, "QA", 201);
        answerChecklist(roundQ, "QA", qa, "QA");
        assertThat(call(postJson("/api/v1/qar/rounds/" + roundQ + "/decision", "{\"decision\":\"PASS\"}")
                .header("If-Match", etag("/api/v1/qar/reviews/" + reviewId, pm, "PM")), qa, "QA", 200)
                .get("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(call(post("/api/v1/qar/reviews/" + reviewId + "/approve").header("If-Match",
                etag("/api/v1/qar/reviews/" + reviewId, pm, "PM")), pm, "PM", 200).get("status").asText())
                .isEqualTo("APPROVED");
        drain();
        JsonNode published = call(post("/api/v1/rpt/revisions/" + rev + "/publish"), pm, "PM", 200);
        assertThat(published.get("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published.at("/grade/finalGrade").asText()).isEqualTo(grade);
        drain();
        JsonNode grades = call(get("/api/v1/fac/buildings/" + building + "/grades"), pm, "PM", 200);
        assertThat(grades).hasSize(1);
        assertThat(grades.get(0).get("grade").asText()).isEqualTo(grade);
        // 점검 수행(오늘) → 정밀점검 다음 도래일 재계산(오늘 이후), 이전 회차 도래는 수행 완료로 종료
        JsonNode duesAfter = call(get("/api/v1/fac/buildings/" + building + "/dues"), pm, "PM", 200);
        assertThat(duesAfter.findValuesAsText("inspectionType")).contains("DETAILED");
        for (JsonNode d : duesAfter) {
            if (d.get("inspectionType").asText().equals("DETAILED")) {
                assertThat(LocalDate.parse(d.get("dueDate").asText())).isAfter(today);
                assertThat(LocalDate.parse(d.get("baseDate").asText())).isEqualTo(today);
            }
        }
        assertThat(call(get("/api/v1/sal/opportunities/" + oppId), sales, "SALES", 200).get("stage").asText())
                .isEqualTo("CONTRACTED");
        call(postJson("/api/v1/rpt/revisions/" + rev + "/delivery-confirmation", "{\"recipientName\":\"합성 고객 담당자\"}"),
                pm, "PM", 201);
        drain();

        // ------------------------------------------------------------ 8. PTL
        step("8 PTL 고객 열람 · 워터마크 다운로드 · 교차 고객 404");
        JsonNode reports = call(asCustomer(get("/api/v1/ptl/reports"), client), null, null, 200);
        assertThat(reports).hasSize(1);
        UUID pub = id(reports.get(0));
        assertThat(reports.get(0).get("safetyGrade").asText()).isEqualTo(grade);
        assertThat(reports.get(0).get("downloadable").asBoolean()).isTrue();
        JsonNode myBuildings = call(asCustomer(get("/api/v1/ptl/buildings"), client), null, null, 200);
        assertThat(myBuildings).hasSize(1);
        assertThat(myBuildings.get(0).get("currentGrade").asText()).isEqualTo(grade);
        MvcResult dl = mvc.perform(asCustomer(post("/api/v1/ptl/reports/" + pub + ":download"), client)).andReturn();
        assertThat(dl.getResponse().getStatus()).isEqualTo(200);
        byte[] pdf = dl.getResponse().getContentAsByteArray();
        String downloadId = dl.getResponse().getHeader("X-Download-Id");
        assertThat(dl.getResponse().getHeader("X-Content-SHA256")).isEqualTo(Hashing.sha256Hex(pdf));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("download-id " + downloadId).contains("customer " + client);
            assertThat(doc.getDocumentInformation().getCustomMetadataValue("AtomCustomerId")).isEqualTo(client.toString());
        }
        // 고객 알림 · 공유 문서: SAL 견적 발행 · 서명 요청, RPT 납품, FAC 등급(모두 이벤트 구독으로 도착)
        List<String> kinds = call(asCustomer(get("/api/v1/ptl/notifications"), client), null, null, 200)
                .findValuesAsText("kind");
        assertThat(kinds).contains("ESTIMATE_ISSUED", "REPORT_DELIVERED", "GRADE_RECORDED");
        assertThat(call(asCustomer(get("/api/v1/ptl/sales-documents"), client), null, null, 200).findValuesAsText("kind"))
                .contains("ESTIMATE");
        UUID otherClient = createClient(sales, "합성타고객-golden-" + UUID.randomUUID());
        assertThat(call(asCustomer(get("/api/v1/ptl/reports"), otherClient), null, null, 200)).isEmpty();
        assertThat(call(asCustomer(get("/api/v1/ptl/reports/" + pub), otherClient), null, null, 404).get("code").asText())
                .isEqualTo("PTL-404-001");
        assertThat(call(asCustomer(post("/api/v1/ptl/reports/" + pub + ":download"), otherClient), null, null, 404)
                .get("code").asText()).isEqualTo("PTL-404-001");

        // ------------------------------------------------------------ 9. FCD
        step("9 FCD 명세서 · 쪼개기 케이스 · 소명 · 정산 ⇒ CST 경비 · 전표");
        String pan = PanTools.luhnComplete("999999" + String.format("%09d", ThreadLocalRandom.current()
                .nextLong(1_000_000_000L)));
        JsonNode card = call(postJson("/api/v1/fcd/cards", json(map("cardCompany", "ACARD", "pan", pan,
                "cardType", "PERSONAL_ASSIGNED", "holderEmployeeId", eng, "dept", "진단1팀", "limitPerTxn", 300_000,
                "limitDaily", 450_000, "limitMonthly", 5_000_000))), fin, "FIN", 201);
        assertThat(card.toString()).doesNotContain(pan);
        String day = workTue.format(DateTimeFormatter.ofPattern("yyyy.MM.dd"));
        String ym = YearMonth.from(workTue).toString();
        byte[] csv = acardCsv(pan.substring(pan.length() - 4), List.of(
                new String[] {day, "12:05", "81000001", "(예시)골든설렁탕 본점", "999-81-00001", "한식", "12,000"},
                new String[] {day, "14:00", "81000002", "(예시)골든공구상사", "999-81-00004", "철물공구", "280,000"},
                new String[] {day, "14:40", "81000003", "(예시)골든공구상사", "999-81-00004", "철물공구", "270,000"}));
        JsonNode batch = json(mvc.perform(as(multipart("/api/v1/fcd/imports").file(new MockMultipartFile("file",
                "acard_golden.csv", "text/csv", csv)), fin, "FIN")).andReturn());
        assertThat(batch.get("status").asText()).isEqualTo("VALIDATED");
        assertThat(batch.get("counts").get("NEW").asInt()).isEqualTo(3);
        call(postJson("/api/v1/fcd/imports/" + batch.get("id").asText() + ":commit", "{}"), fin, "FIN", 200);
        JsonNode items = call(get("/api/v1/fcd/transactions"), eng, "EMPLOYEE", 200);   // 본인 거래만
        assertThat(items).hasSize(3);
        assertThat(items.toString()).doesNotContain(pan);
        JsonNode queue = call(postJson("/api/v1/fcd/review-queue/" + ym + ":run", "{\"policy\":\"THRESHOLD\"}"),
                fin, "FIN", 200);
        assertThat(queue.get("casesOpened")).hasSize(1);                               // 쪼개기 묶음 1케이스
        UUID caseId = UUID.fromString(queue.get("casesOpened").get(0).asText());
        call(postJson("/api/v1/fcd/cases/" + caseId + ":request-explanation", "{}"), fin, "FIN", 200);
        call(postJson("/api/v1/fcd/cases/" + caseId + "/explanations",
                "{\"purpose\":\"BUSINESS\",\"text\":\"현장 긴급 안전용품을 두 번에 나눠 구매(합성)\"}"), eng, "EMPLOYEE", 201);
        assertThat(call(postJson("/api/v1/fcd/cases/" + caseId + ":resolve",
                "{\"label\":\"LEGIT\",\"reason\":\"긴급 구매 증빙 확인(합성)\"}"), auditor, "AUDITOR", 200)
                .get("status").asText()).isEqualTo("RESOLVED_LEGIT");
        BigDecimal expectedExpense = BigDecimal.ZERO;
        for (JsonNode t : items) {
            long amt = t.get("amount_krw").decimalValue().longValue();
            long supply = Math.round(amt / 1.1);
            boolean lunch = t.get("merchant_name").asText().contains("설렁탕");
            call(put("/api/v1/fcd/transactions/" + t.get("id").asText() + "/expense").contentType(MediaType.APPLICATION_JSON)
                    .content(json(map("accountCode", lunch ? "MEAL" : "SUPPLIES", "purpose", lunch ? "현장 중식" : "현장 안전용품",
                            "projectId", project, "supplyAmount", supply, "vatAmount", amt - supply,
                            "vatDeductible", true))), eng, "EMPLOYEE", 200);
            expectedExpense = expectedExpense.add(BigDecimal.valueOf(supply));
        }
        String expenseReport = call(postJson("/api/v1/fcd/expense-reports/" + ym + ":submit", "{}"), eng, "EMPLOYEE", 200)
                .get("reportId").asText();
        call(postJson("/api/v1/fcd/expense-reports/" + expenseReport + ":approve", "{}"), fin, "FIN", 200);
        assertThat(call(postJson("/api/v1/fcd/expense-reports/" + expenseReport + ":post", "{}"), fin, "FIN", 200)
                .get("postedLines")).hasSize(3);
        drain();
        assertThat(outboxCount("cst.voucher.synced", UUID.fromString(expenseReport))).isEqualTo(3);
        JsonNode expenseRows = ledgerRows(project, "EXPENSE");
        assertThat(expenseRows).hasSize(3);
        assertThat(sumOf(expenseRows, "amountKrw")).isEqualByComparingTo(expectedExpense);
        JsonNode rep = call(get("/api/v1/fcd/expense-reports/" + expenseReport), eng, "EMPLOYEE", 200);
        assertThat(rep.get("status").asText()).isEqualTo("POSTED");
        for (JsonNode l : rep.get("lines")) {
            assertThat(l.get("sync_status").asText()).isEqualTo("SYNCED");
            assertThat(l.get("voucher_no").asText()).startsWith("DZ-STUB-");             // 더존(스텁) 전표 번호
        }

        // ------------------------------------------------------------ 10. 최종 확인
        step("10 수익성 · EVM · 해시 체인 · PAN · DLQ · 경계");
        BigDecimal expectedAc = expectedLabor.add(expectedEquip).add(expectedExpense);
        JsonNode prof = call(get("/api/v1/cst/projects/" + project + "/profitability"), exec, "EXEC", 200);
        assertThat(prof.get("contractAmountKrw").decimalValue()).isEqualByComparingTo(offer);
        assertThat(prof.get("bacKrw").decimalValue()).isEqualByComparingTo(bac);
        assertThat(prof.get("acKrw").decimalValue()).isEqualByComparingTo(expectedAc);
        assertThat(prof.at("/byElement/LABOR").decimalValue()).isEqualByComparingTo(expectedLabor);
        assertThat(prof.at("/byElement/EQUIP_USE").decimalValue()).isEqualByComparingTo(expectedEquip);
        assertThat(prof.at("/bySource/EXPENSE").decimalValue()).isEqualByComparingTo(expectedExpense);
        assertThat(prof.get("marginKrw").decimalValue()).isEqualByComparingTo(offer.subtract(expectedAc));
        assertThat(prof.get("mandays").decimalValue()).isEqualByComparingTo("1.75");      // (8 + 6) / 8
        JsonNode evm = call(get("/api/v1/cst/projects/" + project + "/evm?asOf=" + today), exec, "EXEC", 200);
        assertThat(evm.get("acBasis").asText()).isEqualTo("REAL");
        assertThat(evm.at("/result/ac").decimalValue()).isEqualByComparingTo(expectedAc);
        assertThat(evm.at("/result/bac").decimalValue()).isEqualByComparingTo(bac);
        assertThat(evm.at("/result/ev").decimalValue()).isEqualByComparingTo(fieldBudget);   // 현장조사 마일스톤 100%
        assertThat(evm.at("/result/cv").decimalValue()).isEqualByComparingTo(fieldBudget.subtract(expectedAc));

        // 해시 체인: 감사 로그 전체 · FLD 회차 증거 체인(core 첨부)
        assertThat(auditLog.verifyChain()).as("audit_log 해시 체인 첫 위조 행").isEmpty();
        JsonNode chain = call(get("/api/v1/fld/rounds/" + roundId + "/evidence/verify"), pm, "PM", 200);
        assertThat(chain.get("valid").asBoolean()).isTrue();
        assertThat(chain.get("length").asInt()).isEqualTo(1);

        // PAN: 이 시나리오가 쓴 모든 업무 · 감사 · 이벤트 행에 Luhn 통과 카드번호 0건
        for (String q : List.of("SELECT row_to_json(x)::text FROM fcd.card x", "SELECT row_to_json(x)::text FROM fcd.txn x",
                "SELECT row_to_json(x)::text FROM fcd.txn_source x", "SELECT row_to_json(x)::text FROM fcd.import_staging x",
                "SELECT row_to_json(x)::text FROM fcd.import_batch x", "SELECT row_to_json(x)::text FROM fcd.risk_score x",
                "SELECT row_to_json(x)::text FROM fcd.expense_line x", "SELECT row_to_json(x)::text FROM fcd.fcd_case x",
                "SELECT row_to_json(x)::text FROM core.audit_log x",
                "SELECT row_to_json(x)::text FROM core.outbox_event x",
                "SELECT row_to_json(x)::text FROM cst.cost_ledger_entry x",
                "SELECT row_to_json(x)::text FROM cst.voucher_sync x")) {
            String dump = String.join(" | ", jdbc.queryForList(q, String.class));
            assertThat(dump).as(q).doesNotContain(pan);
            String scrubbed = dump.replaceAll("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}", "<uuid>")
                    .replaceAll("[0-9a-f]{32,}", "<hex>")
                    // 수기 등록 건물의 PNU(19자리 순번)는 카드번호가 아니지만 우연히 Luhn 을 통과할 수 있다 → 제외
                    .replaceAll("\"pnu\": \"\\d{19}\"", "\"pnu\": \"<pnu>\"");
            assertThat(PanTools.scanPans(scrubbed)).as(q).isEmpty();
        }

        // Outbox: 시나리오가 만든 이벤트 전량 전달 · DLQ 0
        drain();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE published_at IS NULL", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.outbox_dead", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT split_part(event_type, '.', 1)) FROM core.outbox_event",
                Integer.class)).as("이벤트를 낸 모듈 수").isGreaterThanOrEqualTo(9);

        // 모듈 경계: Spring Modulith verify() (위반 시 예외)
        ApplicationModules.of(ErpServerApplication.class).verify();
        stepEnd();

        long total = (System.nanoTime() - t0) / 1_000_000;
        StringBuilder sb = new StringBuilder("\n[골든 패스] 단계별 소요(ms)\n");
        stepMillis.forEach((k, ms) -> sb.append("  ").append(k).append(" : ").append(ms).append('\n'));
        sb.append("  합계 : ").append(total).append(" ms");
        System.out.println(sb);
    }

    // ================================================================== 도우미

    private void step(String name) {
        stepEnd();
        stepName = name;
        stepStart = System.nanoTime();
    }

    private void stepEnd() {
        if (stepName != null) {
            stepMillis.put(stepName, (System.nanoTime() - stepStart) / 1_000_000);
            stepName = null;
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private String json(Object o) throws Exception {
        return objectMapper.writeValueAsString(o);
    }

    private static UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asText());
    }

    private static MockHttpServletRequestBuilder ifMatch(MockHttpServletRequestBuilder b, long version) {
        return b.header("If-Match", "\"v" + version + "\"");
    }

    private MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder b, UUID clientId) {
        return b.header("X-Actor-Id", customer.toString()).header("X-Roles", "CUSTOMER")
                .header("X-Client-Org-Id", clientId.toString());
    }

    /** 요청 → 기대 상태 확인 → 본문(JSON). actor 가 null 이면 이미 헤더가 붙은 요청 */
    private JsonNode call(MockHttpServletRequestBuilder b, UUID actor, String role, int expect) throws Exception {
        MvcResult r = mvc.perform(actor == null ? b : as(b, actor, role)).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(r.getResponse().getStatus()).as("%s %s → %s", r.getRequest().getMethod(),
                r.getRequest().getRequestURI(), body).isEqualTo(expect);
        return body.isEmpty() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
    }

    private String etag(String url, UUID actor, String role) throws Exception {
        return mvc.perform(as(get(url), actor, role)).andReturn().getResponse().getHeader("ETag");
    }

    /** Relay 를 미전달 이벤트가 없어질 때까지(연쇄 이벤트 포함) */
    private void drain() {
        for (int i = 0; i < 200; i++) {
            var r = relay.pollOnce();
            if (r.published() == 0 && r.failed() == 0) {
                return;
            }
            assertThat(r.failed()).as("Relay 전달 실패(재시도 대기)").isZero();
        }
    }

    private int outboxCount(String type, UUID aggregate) {
        return aggregate == null
                ? jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ?", Integer.class, type)
                : jdbc.queryForObject("SELECT count(*) FROM core.outbox_event WHERE event_type = ? AND aggregate_id = ?",
                        Integer.class, type, aggregate);
    }

    /** HRQ 수동 등록(더존 연동 전 직원 대장) — core 직원 행도 함께 생긴다 */
    private UUID hrqEmployee(String name, String field, String grade) throws Exception {
        UUID id = UUID.randomUUID();
        String empNo = "G-" + id.toString().substring(0, 8);
        Map<String, Object> body = map("id", id, "empNo", empNo, "name", "합성" + name, "field", field,
                "employmentType", "FULLTIME", "registeredField", false, "hiredOn", "2020-01-01");
        if (grade != null) {
            body.put("initialGrade", grade);
            body.put("gradeFrom", "2020-01-01");
        }
        call(postJson("/api/v1/hrq/employees", json(body)), admin, "ADMIN", 201);
        return id;
    }

    private static Map<String, Object> feeRequest(LocalDate asOf, BigDecimal discount) {
        Map<String, Object> m = map("asOfDate", asOf.toString(), "serviceType", "PRECISE_DIAG",
                "facility", map("facilityClass", "BUILDING", "grossAreaM2", 12000.5, "structure", "RC",
                        "basementFloors", 1, "floors", 5, "completionYear", 2001, "inUse", true),
                "expenses", List.of(map("item", "TRAVEL", "quantity", 4, "unitPrice", 150000)));
        if (discount != null) {
            m.put("discountRate", discount);
        }
        return m;
    }

    /** 공공 API(도로명주소 · 건축HUB) 합성 응답 — 표제부 1건(지상 5 · 지하 1) */
    private String stubPublicRegister(String keyword) {
        String bun = keyword.substring(keyword.length() - 4);
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/juso/addrLinkApi.do")).withQueryParam("keyword", equalTo(keyword))
                .willReturn(okJson("""
                        {"results":{"common":{"errorCode":"0","totalCount":"1"},"juso":[{"roadAddr":"서울 합성구 %s",
                         "jibunAddr":"서울 합성구 골든동 %s","admCd":"1156011000","mtYn":"0","lnbrMnnm":"%s",
                         "lnbrSlno":"7","bdNm":"합성골든빌딩"}]}}""".formatted(keyword, bun, bun))));
        String hub = "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":%s}}}";
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrRecapTitleInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub.formatted("\"\""))));
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrTitleInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub.formatted("""
                        {"item":{"mgmBldrgstPk":"11560-G%s","bldNm":"합성 골든 사옥","dongNm":"","mainPurpsCdNm":"업무시설",
                         "strctCdNm":"철근콘크리트구조","totArea":"12000.5","grndFlrCnt":"5","ugrndFlrCnt":"1",
                         "useAprDay":"20010520"}}""".formatted(bun)))));
        PUBLIC_API.stubFor(WireMock.get(urlPathEqualTo("/hub/getBrFlrOulnInfo")).withQueryParam("bun", equalTo(bun))
                .willReturn(okJson(hub.formatted("\"\""))));
        return bun;
    }

    private static UUID nodeId(JsonNode nodes, String code) {
        for (JsonNode n : nodes) {
            if (code.equals(n.get("code").asText())) {
                return UUID.fromString(n.get("id").asText());
            }
            if (n.has("children")) {
                try {
                    return nodeId(n.get("children"), code);
                } catch (IllegalStateException ignored) {
                    // 다른 가지
                }
            }
        }
        throw new IllegalStateException("위치 노드 없음: " + code);
    }

    /** CST 표준 노임 단가 — 등급별로 없을 때만 ADMIN 이 등록(공유 DB 의 다른 시험과 같은 합성 값) */
    private void ensureStdRates() throws Exception {
        JsonNode rates = call(get("/api/v1/cst/rates?kind=STD_LABOR"), admin, "ADMIN", 200);
        for (var e : STD.entrySet()) {
            if (!rates.findValuesAsText("keyCode").contains(e.getKey())) {
                approveRate(call(postJson("/api/v1/cst/rates", json(map("rateKind", "STD_LABOR", "keyCode", e.getKey(),
                        "value", e.getValue(), "validFrom", "2000-01-01", "sourceRef", "합성 테스트 단가"))), admin, "ADMIN",
                        201));
            }
        }
    }

    /** 요율 초안 → EXEC 승인(등록자 ≠ 승인자, V0038) */
    private JsonNode approveRate(JsonNode draft) throws Exception {
        return call(postJson("/api/v1/cst/rates/" + draft.get("id").asText() + ":approve", "{}")
                .header("If-Match", "\"v" + draft.get("version").asLong() + "\""), exec, "EXEC", 200);
    }

    /** 근무일에 유효한 등급별 표준 일 단가(API 조회, 승인된 행만) */
    private Map<String, BigDecimal> stdRates(LocalDate on) throws Exception {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (JsonNode r : call(get("/api/v1/cst/rates?kind=STD_LABOR"), admin, "ADMIN", 200)) {
            if (!"APPROVED".equals(r.get("status").asText())) {
                continue;
            }
            LocalDate from = LocalDate.parse(r.get("validFrom").asText());
            LocalDate to = r.get("validTo").isNull() ? null : LocalDate.parse(r.get("validTo").asText());
            if (!on.isBefore(from) && (to == null || on.isBefore(to))) {
                out.put(r.get("keyCode").asText(), r.get("value").decimalValue());
            }
        }
        return out;
    }

    /** CST 인건비 = 단가 × 시간 ÷ 8, 원 단위 HALF_UP (LaborCost 와 독립 계산) */
    private static BigDecimal labor(BigDecimal daily, int hours) {
        return daily.multiply(BigDecimal.valueOf(hours)).divide(new BigDecimal("8"), 0, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumOf(JsonNode rows, String field) {
        BigDecimal s = BigDecimal.ZERO;
        for (JsonNode r : rows) {
            s = s.add(r.get(field).decimalValue());
        }
        return s;
    }

    private JsonNode ledgerRows(UUID project, String sourceType) throws Exception {
        var out = objectMapper.createArrayNode();
        for (JsonNode r : call(get("/api/v1/cst/projects/" + project + "/ledger"), exec, "EXEC", 200)) {
            if (r.get("sourceType").asText().equals(sourceType)) {
                out.add(r);
            }
        }
        return out;
    }

    private JsonNode ledgerRow(UUID project, String sourceType) throws Exception {
        JsonNode rows = ledgerRows(project, sourceType);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    // ------------------------------------------------------------------ FLD 동기화 프로토콜

    private String created(UUID zone, String type, UUID node) {
        return """
                {"zoneId":"%s","typeCode":"%s","subtypeCode":"FLEXURE","sheetVersion":1,
                 "geomNorm":{"type":"LineString","coords":[[0.412,0.338],[0.455,0.341]]},"locationNodeId":"%s"}"""
                .formatted(zone, type, node);
    }

    private String measured(String item, String value, String method, UUID equipment) {
        return """
                {"item":"%s","value":%s,"unit":"%s","method":"%s"%s,"measuredAt":"%s"}""".formatted(item, value,
                item.endsWith("_mm") ? "mm" : "m", method, equipment == null ? "" : ",\"equipmentId\":\"" + equipment + "\"",
                OffsetDateTime.ofInstant(clock.instant(), SEOUL));
    }

    private String op(UUID defect, String type, int seq, String payload) {
        String hlc = new Hlc(clock.instant().toEpochMilli() + seq, 0, "gold").pack();
        return "{\"opId\":\"%s\",\"defectId\":\"%s\",\"eventType\":\"%s\",\"hlc\":\"%s\",\"payload\":%s}"
                .formatted(UUID.randomUUID(), defect, type, hlc, payload);
    }

    private JsonNode push(UUID device, UUID round, String... ops) throws Exception {
        return call(postJson("/api/v1/fld/sync/push", "{\"deviceId\":\"%s\",\"roundId\":\"%s\",\"clientLastPulledSeq\":0,\"ops\":[%s]}"
                .formatted(device, round, String.join(",", ops))), eng, "ENG", 200);
    }

    private static byte[] jpeg(int seed) throws Exception {
        BufferedImage img = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 240; x++) {
                img.setRGB(x, y, new Color(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)).getRGB());
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static String sha(byte[] b) {
        return HashChain.sha256(new ByteArrayInputStream(b));
    }

    // ------------------------------------------------------------------ QAR · FCD

    private void answerChecklist(UUID roundId, String stage, UUID actor, String role) throws Exception {
        JsonNode cl = call(get("/api/v1/qar/rounds/" + roundId + "/checklist"), actor, role, 200);
        List<String> missing = new ArrayList<>();
        cl.get("SELF".equals(stage) ? "missingSelf" : "missingQa").forEach(n -> missing.add(n.asText()));
        for (JsonNode item : cl.get("items")) {
            if (missing.contains(item.get("itemNo").asText())) {
                call(put("/api/v1/qar/rounds/" + roundId + "/checklist/" + item.get("itemId").asText())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stage\":\"" + stage + "\",\"answer\":\"PASS\",\"note\":\"합성 확인\"}"), actor, role, 200);
            }
        }
    }

    /** 에이카드 승인내역 CSV(CP949 · 제목 2행 · 합계 행) — 카드번호는 마스킹 형식만 */
    private static byte[] acardCsv(String pan4, List<String[]> rows) {
        StringBuilder sb = new StringBuilder("(가상)에이카드 법인카드 이용내역(승인)\r\n조회기간,합성,출력일,합성\r\n");
        sb.append("이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고\r\n");
        long total = 0;
        for (String[] r : rows) {
            sb.append(String.join(",", r[0], r[1], "999999******" + pan4, r[2], r[3], r[4], r[5], "\"" + r[6] + "\"", "KRW",
                    "", "")).append("\r\n");
            total += Long.parseLong(r[6].replace(",", ""));
        }
        sb.append("합계,,,,,,,\"").append(String.format("%,d", total)).append("\",,,\r\n");
        return sb.toString().getBytes(CP949);
    }
}
