package com.atomeng.devserver;

import static com.atomeng.devserver.DevSeed.ADMIN;
import static com.atomeng.devserver.DevSeed.ENG;
import static com.atomeng.devserver.DevSeed.EXEC;
import static com.atomeng.devserver.DevSeed.PM;
import static com.atomeng.devserver.DevSeed.SALES;
import static com.atomeng.devserver.DevSeed.etag;
import static com.atomeng.devserver.DevSeed.id;
import static com.atomeng.devserver.DevSeed.log;
import static com.atomeng.devserver.DevSeed.map;
import static com.atomeng.devserver.DevSeed.user;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * dev 시드 4차(웹 4차 화면 — SAL 파이프라인 · 공고 · 적격심사, DEM 감리일지 작성 · 정정 요청, FLD 회차 설정, "내 할 일", 직무 분리 E2E)
 * — 실제 HTTP API 로만 만든다(직접 SQL 없음). 사람 · 회사 · 공고 · 금액은 모두 합성.
 * <ul>
 *   <li>dev 사용자 2명: 겸직임원(SALES+EXEC — SAL-06 본인 할인 승인 금지 확인용), 겸직책임(PM+ENG+EXEC — 본인 타임시트 ·
 *       본인이 올린 예산 결재 금지 확인용). ID 는 apps/erp-web/src/auth/devUsers.ts 와 같다.</li>
 *   <li>SAL: 나라장터 대역(WireMock) 공고 5건 수집(정정 1 · 취소 1), 공고 연결 기회 1(적격심사 연습), 리드 1,
 *       겸직임원이 SALES 로 만든 할인 8% 견적 2건(승인 대기 — 하나는 본인 승인 거부 시험, 하나는 다른 임원 승인 시험)</li>
 *   <li>HRQ: "(합성) 4차 직무분리 연습" 프로젝트(겸직책임 RESP · 정참여 PART), 겸직책임 오늘 타임시트 제출
 *       — 한빛타워(최책임 RESP · 문책임 PART) 2건 · 연습 프로젝트(본인 RESP) 1건</li>
 *   <li>CST: 두 번째 계약 서명 → 프로젝트 + 실행예산 Rev.0 초안(결재 대기, 겸직책임 RESP)</li>
 *   <li>DEM: 정참여가 어제(잠긴) 일지를 늦게 재전송 → 정정 대기(PENDING) 1건</li>
 * </ul>
 */
final class DevSeedRound4 {

    static final UUID SALES_EXEC = user(14);
    static final UUID PM_ENG_EXEC = user(15);

    private final DevSeed s;
    private final LocalDate today;

    DevSeedRound4(DevSeed s, LocalDate today) {
        this.s = s;
        this.today = today;
    }

    // ==================================================================== 나라장터 대역(WireMock)

    /**
     * dev 서버 기동 전에 부른다 — 나라장터 입찰공고 목록 대역을 띄우고 SAL 수집 설정을 그쪽으로 돌린다(실제 공공 API 호출 없음).
     * 응답 필드명은 G2bApiClient 가 가정한 공개 명세 관례(⚠️ 확인 필요)를 따른다.
     */
    static void configureG2bStub(Map<String, Object> props) {
        WireMockServer w = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        w.start();
        Runtime.getRuntime().addShutdownHook(new Thread(w::stop));
        LocalDate d = LocalDate.now(ZoneId.of("Asia/Seoul"));
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String notice = d.minusDays(1).atTime(9, 0).format(f);
        List<String> items = new ArrayList<>();
        Object[][] rows = {
                // bidNo, seq, 제목, 공고기관, 수요기관, 추정가격, 마감(일), 구분
                {"R4-0001", "000", "(합성) 합성시 청사 정밀안전점검 용역", "(합성) 합성시", "(합성) 합성시 회계과", 182000000, 7, "등록"},
                {"R4-0002", "000", "(합성) 예시구 공공도서관 정기안전점검 용역", "(합성) 예시구", "(합성) 예시구 문화과", 46000000, 10, "등록"},
                {"R4-0003", "000", "(합성) 가상교 보수보강 설계 용역", "(합성) 가상도로공사", "(합성) 가상도로공사 시설처", 95000000, 4, "등록"},
                {"R4-0003", "001", "(합성) 가상교 보수보강 설계 용역(정정)", "(합성) 가상도로공사", "(합성) 가상도로공사 시설처", 98000000, 6, "정정"},
                {"R4-0004", "000", "(합성) 표본초등학교 구조안전진단 용역", "(합성) 표본교육청", "(합성) 표본초등학교", 31000000, 3, "취소"},
        };
        for (Object[] r : rows) {
            items.add("""
                    {"bidNtceNo":"%s","bidNtceOrd":"%s","bidNtceNm":"%s","ntceInsttNm":"%s","dminsttNm":"%s",
                     "cntrctCnclsMthdNm":"제한경쟁","sucsfbidMthdNm":"적격심사","ntceKindNm":"%s","presmptPrce":"%s",
                     "bidNtceDt":"%s","bidClseDt":"%s","opengDt":"%s","bidNtceDtlUrl":"https://example.invalid/g2b/%s"}"""
                    .formatted(r[0], r[1], r[2], r[3], r[4], r[7], r[5], notice,
                            d.plusDays((Integer) r[6]).atTime(10, 0).format(f),
                            d.plusDays((Integer) r[6]).atTime(11, 0).format(f), r[0]));
        }
        String body = "{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"NORMAL SERVICE.\"},"
                + "\"body\":{\"items\":[" + String.join(",", items) + "],\"pageNo\":1,\"numOfRows\":100,\"totalCount\":"
                + items.size() + "}}}";
        w.stubFor(get(urlPathMatching("/g2b/.*")).willReturn(okJson(body)));
        props.put("atom.sal.g2b.base-url", w.baseUrl() + "/g2b");
        props.put("atom.sal.g2b.service-key", "synthetic-dev-key");
        props.put("atom.sal.g2b.max-attempts", "1");
        System.out.println("[dev-server] 나라장터 대역(WireMock) " + w.baseUrl() + "/g2b");
    }

    // ==================================================================== 시드

    void run(UUID client, UUID project) throws Exception {
        // dev 사용자(직원 대장)
        Object[][] people = {{SALES_EXEC, "D-014", "겸직임원", "OTHER", null}, {PM_ENG_EXEC, "D-015", "겸직책임", "ARCH", "SENIOR"}};
        for (Object[] p : people) {
            Map<String, Object> b = map("id", p[0], "empNo", p[1], "name", p[2], "field", p[3],
                    "employmentType", "FULLTIME", "registeredField", p[4] != null, "hiredOn", "2020-03-02");
            if (p[4] != null) {
                b.put("initialGrade", p[4]);
                b.put("gradeFrom", "2020-03-02");
            }
            s.call("POST", "/api/v1/hrq/employees", b, ADMIN, "ADMIN", null);
        }
        seedSal(client);
        seedHrq(client, project);
        seedCst(client);
        seedDem();
    }

    private void seedSal(UUID client) throws Exception {
        // 나라장터 공고 수집(대역) — ADMIN 수동 수집
        JsonNode collected = s.call("POST", "/api/v1/sal/bid-notices:collect-now", map(), ADMIN, "ADMIN", null);
        UUID notice = null;
        for (JsonNode n : s.call("GET", "/api/v1/sal/bid-notices?q=" + java.net.URLEncoder.encode("청사",
                java.nio.charset.StandardCharsets.UTF_8), null, SALES, "SALES", null)) {
            notice = id(n);
        }
        // 공고 연결 기회(적격심사 연습) · 리드(파이프라인 보드)
        JsonNode g2b = s.call("POST", "/api/v1/sal/opportunities", map("source", "G2B", "clientId", client,
                "bidNoticeId", notice, "serviceType", "PRECISE_INSPECTION", "title", "(합성) 합성시 청사 정밀안전점검 입찰",
                "ownerId", SALES, "expectedAmount", 182000000, "probability", 40), SALES, "SALES", null);
        s.call("POST", "/api/v1/sal/opportunities/" + id(g2b) + ":move", map("toStage", "QUALIFYING"), SALES, "SALES",
                etag(g2b));
        s.call("POST", "/api/v1/sal/opportunities", map("source", "INQUIRY", "clientId", client,
                "serviceType", "REGULAR_INSPECTION", "title", "(합성) 예시빌딩 정기점검 문의", "ownerId", SALES,
                "expectedAmount", 12000000, "probability", 20), SALES, "SALES", null);
        // SAL-06 연습: 겸직임원이 SALES 로 작성 · 발행한 할인 8% 견적 2건(승인 대기)
        for (String suffix : List.of("A(본인 승인 거부)", "B(다른 임원 승인)")) {
            JsonNode o = s.call("POST", "/api/v1/sal/opportunities", map("source", "REFERRAL", "clientId", client,
                    "serviceType", "PRECISE_DIAG", "title", "(합성) 4차 겸직 할인 " + suffix, "ownerId", SALES_EXEC),
                    SALES_EXEC, "SALES", null);
            long v = s.call("POST", "/api/v1/sal/opportunities/" + id(o) + ":move", map("toStage", "QUALIFYING"),
                    SALES_EXEC, "SALES", etag(o)).get("version").asLong();
            s.call("POST", "/api/v1/sal/opportunities/" + id(o) + ":move", map("toStage", "PROPOSING"), SALES_EXEC,
                    "SALES", "\"v" + v + "\"");
            JsonNode est = s.call("POST", "/api/v1/sal/opportunities/" + id(o) + "/estimates", fee(0.08), SALES_EXEC,
                    "SALES", null);
            s.call("POST", "/api/v1/sal/estimates/" + id(est) + ":issue", map(), SALES_EXEC, "SALES", null);
        }
        log("SAL 나라장터 대역 수집 " + collected.path("inserted").asInt() + "건 · 공고 기회 1 · 리드 1 · 겸직 할인 승인 대기 2");
    }

    private void seedHrq(UUID client, UUID mainProject) throws Exception {
        UUID p4 = id(s.call("POST", "/api/v1/core/projects", map("name", "(합성) 4차 직무분리 연습",
                "serviceType", "PRECISE_DIAG", "clientId", client, "startDate", today.minusMonths(1).toString(),
                "endDate", today.plusMonths(4).toString()), PM, "PM", null));
        String from = today.minusMonths(1).toString();
        String to = today.plusMonths(6).toString();
        member(p4, PM_ENG_EXEC, "RESP", from, to);
        member(p4, ENG, "PART", from, to);
        member(mainProject, PM_ENG_EXEC, "PART", from, to);
        // 겸직책임 본인 타임시트(오늘 — 항상 이번 달이라 지난달 마감 시험과 겹치지 않음)
        String week = "%d-W%02d".formatted(today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        s.call("PUT", "/api/v1/hrq/timesheets/" + PM_ENG_EXEC + "/weeks/" + week, map("submit", true, "entries", List.of(
                map("projectId", mainProject, "workDate", today.toString(), "workType", "FIELD", "hours", 3),
                map("projectId", mainProject, "workDate", today.toString(), "workType", "REPORT", "hours", 1),
                map("projectId", p4, "workDate", today.toString(), "workType", "OFFICE", "hours", 2))),
                PM_ENG_EXEC, "ENG", null);
        log("HRQ 4차 연습 프로젝트 + 겸직책임 제출 타임시트 3건(한빛타워 2 · 연습 1)");
    }

    private void seedCst(UUID client) throws Exception {
        JsonNode opp = s.call("POST", "/api/v1/sal/opportunities", map("source", "INQUIRY", "clientId", client,
                "serviceType", "PRECISE_DIAG", "title", "(합성) 4차 예산 결재 연습 — 별관 정밀진단", "ownerId", SALES,
                "expectedAmount", 40000000), SALES, "SALES", null);
        long v = s.call("POST", "/api/v1/sal/opportunities/" + id(opp) + ":move", map("toStage", "QUALIFYING"), SALES,
                "SALES", etag(opp)).get("version").asLong();
        s.call("POST", "/api/v1/sal/opportunities/" + id(opp) + ":move", map("toStage", "PROPOSING"), SALES, "SALES",
                "\"v" + v + "\"");
        JsonNode est = s.call("POST", "/api/v1/sal/opportunities/" + id(opp) + "/estimates", fee(0.02), SALES, "SALES",
                null);
        s.call("POST", "/api/v1/sal/estimates/" + id(est) + ":issue", map(), SALES, "SALES", null);
        s.call("POST", "/api/v1/sal/estimates/" + id(est) + ":send", map(), SALES, "SALES", null);
        s.call("POST", "/api/v1/sal/estimates/" + id(est) + ":accept", map(), SALES, "SALES", null);
        JsonNode contract = s.call("POST", "/api/v1/sal/contracts", map("opportunityId", id(opp), "estimateId", id(est),
                "title", "(합성) 4차 예산 결재 연습 용역", "contractMethod", "PRIVATE_SECTOR",
                "amount", est.get("offerAmount").decimalValue(), "signDate", today.toString(),
                "startDate", today.toString(), "endDate", today.plusDays(120).toString()), SALES, "SALES", null);
        JsonNode pending = s.call("POST", "/api/v1/sal/contracts/" + id(contract) + ":approve",
                map("signers", List.of("(합성) 발주처 대표")), DevSeed.EXEC, "EXEC", etag(contract));
        JsonNode signed = s.call("POST", "/api/v1/sal/contracts/" + id(contract) + ":sign",
                map("signedOn", today.toString()), SALES, "SALES", etag(pending));
        UUID project = UUID.fromString(signed.get("projectId").asText());
        String from = today.minusDays(1).toString();
        String to = today.plusMonths(6).toString();
        member(project, PM_ENG_EXEC, "RESP", from, to);
        boolean draft = false;
        for (int i = 0; i < 40 && !draft; i++) {   // 실행예산 초안(outbox Relay 비동기)
            draft = s.call("GET", "/api/v1/cst/projects/" + project + "/budget-revisions", null, EXEC, "EXEC", null)
                    .size() > 0;
            if (!draft) {
                Thread.sleep(250);
            }
        }
        log("CST 두 번째 계약 " + signed.get("projectNo").asText() + " · 실행예산 초안 " + (draft ? "결재 대기" : "생성 지연"));
    }

    private void seedDem() throws Exception {
        // 정참여(책임 감리원)가 어제(잠긴) 일지를 늦게 재전송 → 원문 불변 + 정정 대기(PENDING, LATE_SYNC)
        UUID supWp = null;
        for (JsonNode p : s.call("GET", "/api/v1/dem/projects", null, PM, "PM", null)) {
            for (JsonNode w : p.get("workPackages")) {
                if ("DEM_SUPERVISION".equals(w.get("packageType").asText())) {
                    supWp = id(w);
                }
            }
        }
        if (supWp == null) {
            return;
        }
        var r = s.send("PUT", "/api/v1/dem/daily-logs/" + supWp + "/" + today.minusDays(1), map("clientOpId",
                UUID.randomUUID(), "phaseCode", "UPPER_DEMOLITION", "progressPct", 42, "workersCount", 11,
                "remarks", "(합성) 늦은 동기화 — 작업자 수 11명으로 정정", "submit", true), ENG, "ENG", null, Map.of());
        log("DEM 늦은 재전송 → HTTP " + r.statusCode() + " (정정 대기)");
    }

    // ------------------------------------------------------------------ helpers

    private void member(UUID project, UUID emp, String role, String from, String to) throws Exception {
        s.call("POST", "/api/v1/core/projects/" + project + "/members",
                map("employeeId", emp, "roleInProject", role, "fromDate", from, "toDate", to), EXEC, "EXEC", null);
    }

    private Map<String, Object> fee(double discount) {
        return map("asOfDate", today.toString(), "serviceType", "PRECISE_DIAG",
                "facility", map("facilityClass", "BUILDING", "grossAreaM2", 8200.0, "structure", "RC",
                        "basementFloors", 1, "floors", 6, "completionYear", 1998, "inUse", true),
                "expenses", List.of(map("item", "TRAVEL", "quantity", 2, "unitPrice", 150000)),
                "discountRate", discount);
    }
}
