package com.atomeng.devserver;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.IsoFields;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 웹 개발용 합성 시드 — 실제 HTTP API 로만 만든다(직접 SQL 없음 — 영업팀장 그룹 · QA 검토자 · 기준 요율도 관리 API).
 * 사람 · 회사 · 금액은 전부 가짜. 사용자 ID 는 apps/erp-web/src/auth/devUsers.ts 와 같다.
 */
final class DevSeed {

    static UUID user(int n) {
        return UUID.fromString("00000000-0000-7000-8000-%012x".formatted(n));
    }

    static final UUID EXEC = user(1), SALES = user(2), SALES_LEAD = user(3), PM = user(4), ENG = user(5),
            QA = user(6), AUDITOR = user(7), FIN = user(8), ADMIN = user(9), PM2 = user(10);

    private final String base;
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper om = new ObjectMapper();
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private final java.nio.file.Path evidenceRoot;

    DevSeed(String base, java.nio.file.Path evidenceRoot) {
        this.base = base;
        this.evidenceRoot = evidenceRoot;
    }

    void run() throws Exception {
        log("시작");
        // 0. 직원 대장(HRQ 등록 = core.employee 보장)
        Object[][] people = {
                {EXEC, "D-001", "김임원", "OTHER", null}, {SALES, "D-002", "박영업", "OTHER", null},
                {SALES_LEAD, "D-003", "이영업팀장", "OTHER", null}, {PM, "D-004", "최책임", "ARCH", "SENIOR"},
                {ENG, "D-005", "정참여", "ARCH", "INTER"}, {QA, "D-006", "한품질", "STRUCT", "SENIOR"},
                {AUDITOR, "D-007", "오감사", "OTHER", null}, {FIN, "D-008", "윤재무", "OTHER", null},
                {ADMIN, "D-009", "서경영", "OTHER", null}, {PM2, "D-010", "문책임", "CIVIL", "MASTER"}};
        for (Object[] p : people) {
            Map<String, Object> b = map("id", p[0], "empNo", p[1], "name", p[2], "field", p[3],
                    "employmentType", "FULLTIME", "registeredField", p[4] != null, "hiredOn", "2020-03-02");
            if (p[4] != null) {
                b.put("initialGrade", p[4]);
                b.put("gradeFrom", "2020-03-02");
            }
            call("POST", "/api/v1/hrq/employees", b, ADMIN, "ADMIN", null);
        }
        // 영업팀장 그룹(할인 한도 10%) · QA 검토자 프로필 — ADMIN 관리 API(V0036 · V0037)
        call("POST", "/api/v1/sal/sales-group-members", map("employeeId", SALES_LEAD, "groupCode", "SALES_LEAD",
                "note", "(합성) 영업1팀장"), ADMIN, "ADMIN", null);
        call("POST", "/api/v1/qar/reviewer-profiles", map("employeeId", QA, "maxOpen", 5,
                "expertise", map("RT_PSD", 3), "qualifications", List.of("(합성) 건축구조 검토 자격")), ADMIN, "ADMIN", null);

        // 1. 고객 · 영업기회 · 견적 · 계약 → 프로젝트 · 예산 초안(CST, outbox 비동기)
        UUID client = id(call("POST", "/api/v1/core/clients",
                map("name", "(합성) 한빛자산관리", "clientType", "PRIVATE", "address", "서울시 합성구 예시로 1"),
                SALES, "SALES", null));
        UUID clientB = id(call("POST", "/api/v1/core/clients", map("name", "(합성) 푸른시설공단", "clientType", "PUBLIC"),
                SALES, "SALES", null));
        JsonNode opp = call("POST", "/api/v1/sal/opportunities", map("source", "INQUIRY", "clientId", client,
                "serviceType", "PRECISE_DIAG", "title", "(합성) 한빛타워 정밀안전진단", "ownerId", SALES,
                "expectedAmount", 60000000, "probability", 70), SALES, "SALES", null);
        UUID oppId = id(opp);
        long v = move(oppId, opp.get("version").asLong(), "QUALIFYING");
        move(oppId, v, "PROPOSING");
        JsonNode est = call("POST", "/api/v1/sal/opportunities/" + oppId + "/estimates", feeRequest(0.03), SALES,
                "SALES", null);
        UUID estId = id(est);
        call("POST", "/api/v1/sal/estimates/" + estId + ":issue", map(), SALES, "SALES", null);
        call("POST", "/api/v1/sal/estimates/" + estId + ":send", map(), SALES, "SALES", null);
        call("POST", "/api/v1/sal/estimates/" + estId + ":accept", map(), SALES, "SALES", null);
        JsonNode contract = call("POST", "/api/v1/sal/contracts", map("opportunityId", oppId, "estimateId", estId,
                "title", "(합성) 한빛타워 정밀안전진단 용역", "contractMethod", "PRIVATE_SECTOR",
                "amount", est.get("offerAmount").decimalValue(), "signDate", today.toString(),
                "startDate", today.minusWeeks(3).toString(), "endDate", today.plusDays(100).toString()),
                SALES, "SALES", null);
        JsonNode pending = call("POST", "/api/v1/sal/contracts/" + id(contract) + ":approve",
                map("signers", List.of("(합성) 발주처 대표")), EXEC, "EXEC", etag(contract));
        JsonNode signed = call("POST", "/api/v1/sal/contracts/" + id(contract) + ":sign",
                map("signedOn", today.toString()), SALES, "SALES", etag(pending));
        UUID project = UUID.fromString(signed.get("projectId").asText());

        // 두 번째 영업기회(견적 작성 화면 연습용 — QUALIFYING)
        JsonNode opp2 = call("POST", "/api/v1/sal/opportunities", map("source", "REFERRAL", "clientId", client,
                "serviceType", "PRECISE_DIAG", "title", "(합성) 푸른물류센터 정밀점검", "ownerId", SALES), SALES,
                "SALES", null);
        move(id(opp2), opp2.get("version").asLong(), "QUALIFYING");

        // 2. 투입 지정(EXEC) — PM RESP, ENG · PM2 PART
        String from = today.minusMonths(1).toString();
        String to = today.plusMonths(6).toString();
        call("POST", "/api/v1/core/projects/" + project + "/members",
                map("employeeId", PM, "roleInProject", "RESP", "fromDate", from, "toDate", to), EXEC, "EXEC", null);
        call("POST", "/api/v1/core/projects/" + project + "/members",
                map("employeeId", ENG, "roleInProject", "PART", "fromDate", from, "toDate", to), PM, "PM", null);
        call("POST", "/api/v1/core/projects/" + project + "/members",
                map("employeeId", PM2, "roleInProject", "PART", "fromDate", from, "toDate", to), PM, "PM", null);

        // 3. 예산 초안 대기(Relay 500ms) → EXEC 승인 → 진척 10%
        JsonNode budgets = null;
        for (int i = 0; i < 40; i++) {
            budgets = call("GET", "/api/v1/cst/projects/" + project + "/budget-revisions", null, EXEC, "EXEC", null);
            if (budgets.size() > 0) {
                break;
            }
            Thread.sleep(250);
        }
        if (budgets != null && budgets.size() > 0) {
            JsonNode draft = budgets.get(0);
            JsonNode approved = call("POST", "/api/v1/cst/projects/" + project + "/budget-revisions/"
                    + draft.get("id").asText() + ":approve", map(), EXEC, "EXEC",
                    "\"v" + draft.get("version").asLong() + "\"");
            for (JsonNode w : approved.get("wbs")) {
                if (w.get("code").asText().endsWith("-FIELD")) {
                    call("POST", "/api/v1/cst/projects/" + project + "/progress", map("wbsId", w.get("id").asText(),
                            "pctComplete", 10, "reason", "(합성) 현장조사 착수"), PM, "PM", null);
                }
            }
        }

        // 4-0. CST 표준 노임 일 단가(⚠️ 예시 · 합성 값, 실제 노임단가 아님) — ADMIN 초안 → EXEC 승인(4-eyes, V0038).
        //      없으면 승인 타임시트가 원장에 못 올라간다. 마이그레이션에는 값을 넣지 않는다(ADMIN 입력만).
        Object[][] std = {{"JUNIOR", 207000}, {"INTER", 271000}, {"SENIOR", 336000}, {"MASTER", 418000}};
        for (Object[] r : std) {
            JsonNode draft = call("POST", "/api/v1/cst/rates", map("rateKind", "STD_LABOR", "keyCode", r[0],
                    "value", r[1], "validFrom", "2000-01-01", "sourceRef", "⚠️ 예시 — (합성) 개발 시드 단가"), ADMIN,
                    "ADMIN", null);
            call("POST", "/api/v1/cst/rates/" + id(draft) + ":approve", map(), EXEC, "EXEC", etag(draft));
        }

        // 4. 타임시트 — 지난주 제출 → PM 승인(원가 원장 반영), 이번 주는 비워 둔다
        LocalDate lastMon = today.minusWeeks(1).with(DayOfWeek.MONDAY);
        String lastWeek = isoWeek(lastMon);
        JsonNode rows = call("PUT", "/api/v1/hrq/timesheets/" + ENG + "/weeks/" + lastWeek, map("submit", true,
                "entries", List.of(
                        map("projectId", project, "workDate", lastMon.toString(), "workType", "FIELD", "hours", 8),
                        map("projectId", project, "workDate", lastMon.plusDays(1).toString(), "workType", "FIELD",
                                "hours", 8),
                        map("projectId", project, "workDate", lastMon.plusDays(2).toString(), "workType", "REPORT",
                                "hours", 6))), ENG, "ENG", null);
        call("POST", "/api/v1/hrq/timesheets/approvals",
                map("entryIds", List.of(rows.get(0).get("id").asText(), rows.get(1).get("id").asText())), PM, "PM",
                null);

        // 5. 법인카드 — 카드 등록 · 명세서(CSV) 업로드 · 확정(자동 점수) · 검토 큐(THRESHOLD) · 소명 요청 1건
        call("POST", "/api/v1/fcd/cards", map("cardCompany", "ACARD", "pan", luhnPan("999999", "1234"),
                "cardType", "PERSONAL_ASSIGNED", "holderEmployeeId", ENG, "dept", "진단1팀", "limitPerTxn", 300000,
                "limitDaily", 450000, "limitMonthly", 5000000), FIN, "FIN", null);
        String csv = """
                (가상)에이카드 법인카드 이용내역(승인)
                조회기간,합성,출력일,2026.10.19
                이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고
                2026.05.06,21:10,999999******1234,31000002,(예시)룸라운지,,유흥주점,"150,000",KRW,,
                2026.05.07,14:00,999999******1234,31000003,(예시)대성공구상사,999-81-00004,철물공구,"280,000",KRW,,
                2026.05.07,14:40,999999******1234,31000004,(예시)대성공구상사,999-81-00004,철물공구,"270,000",KRW,,
                2026.05.12,12:10,999999******1234,31000005,(예시)한식당,999-81-00005,일반음식점,"38,000",KRW,,
                합계,,,,,,,"738,000",,,
                """;
        JsonNode batch = multipart("/api/v1/fcd/imports", "statement-2026-05.csv", csv.getBytes(StandardCharsets.UTF_8),
                FIN, "FIN");
        call("POST", "/api/v1/fcd/imports/" + id(batch) + ":commit", map(), FIN, "FIN", null);
        JsonNode queue = call("POST", "/api/v1/fcd/review-queue/2026-05:run", map("policy", "THRESHOLD"), FIN, "FIN",
                null);
        if (queue.get("casesOpened").size() > 0) {
            call("POST", "/api/v1/fcd/cases/" + queue.get("casesOpened").get(0).asText() + ":request-explanation",
                    map(), FIN, "FIN", null);
        }
        // 6~10. 두 번째 화면 묶음(FAC · EQP · FLD · DEM · PTL) — 모두 API
        new DevSeedRound2(this, today, evidenceRoot).run(client, clientB, project);
        log("완료 — 프로젝트 " + signed.get("projectNo").asText() + " (" + project + ")");
    }

    // ------------------------------------------------------------------ helpers

    private long move(UUID opp, long version, String stage) throws Exception {
        return call("POST", "/api/v1/sal/opportunities/" + opp + ":move", map("toStage", stage), SALES, "SALES",
                "\"v" + version + "\"").get("version").asLong();
    }

    private Map<String, Object> feeRequest(double discount) {
        return map("asOfDate", today.toString(), "serviceType", "PRECISE_DIAG",
                "facility", map("facilityClass", "BUILDING", "grossAreaM2", 12000.5, "structure", "RC",
                        "basementFloors", 1, "floors", 5, "completionYear", 2001, "inUse", true),
                "expenses", List.of(map("item", "TRAVEL", "quantity", 4, "unitPrice", 150000)),
                "discountRate", discount);
    }

    private static String isoWeek(LocalDate d) {
        return "%d-W%02d".formatted(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    /** 합성 PAN(Luhn 유효) — 실제 카드 아님 */
    static String luhnPan(String prefix, String last4) {
        for (int mid = 0; mid < 1_000_000; mid++) {
            String pan = prefix + "%06d".formatted(mid) + last4;
            if (luhn(pan)) {
                return pan;
            }
        }
        throw new IllegalStateException();
    }

    private static boolean luhn(String s) {
        int sum = 0;
        boolean alt = false;
        for (int i = s.length() - 1; i >= 0; i--) {
            int n = s.charAt(i) - '0';
            if (alt) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alt = !alt;
        }
        return sum % 10 == 0;
    }

    static String etag(JsonNode n) {
        return "\"v" + n.get("version").asLong() + "\"";
    }

    static UUID id(JsonNode n) {
        return UUID.fromString(n.get("id").asText());
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1] instanceof UUID u ? u.toString() : kv[i + 1]);
        }
        return m;
    }

    JsonNode call(String method, String path, Object body, UUID actor, String roles, String ifMatch)
            throws Exception {
        return parse(method, path, send(method, path, body, actor, roles, ifMatch, Map.of()));
    }

    /** 응답 그대로(상태 · 헤더 확인용) — extra 헤더(X-Client-Org-Id 등) 지정 가능 */
    HttpResponse<String> send(String method, String path, Object body, UUID actor, String roles, String ifMatch,
                              Map<String, String> extra) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).header("X-Actor-Id", actor.toString())
                .header("X-Roles", roles).header("Accept", "application/json");
        if (ifMatch != null) {
            b.header("If-Match", ifMatch);
        }
        extra.forEach(b::header);
        if (body == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.header("Content-Type", "application/json");
            if ("POST".equals(method)) {
                b.header("Idempotency-Key", UUID.randomUUID().toString());
            }
            b.method(method, HttpRequest.BodyPublishers.ofString(om.writeValueAsString(body)));
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** 원시 바이트 PATCH(FLD tus 업로드) */
    HttpResponse<String> patchBytes(String path, byte[] bytes, Map<String, String> headers, UUID actor, String roles)
            throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base + path)).header("X-Actor-Id", actor.toString())
                .header("X-Roles", roles).header("Accept", "application/json")
                .header("Content-Type", "application/offset+octet-stream");
        headers.forEach(b::header);
        return http.send(b.method("PATCH", HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** GET 응답의 ETag */
    String etagOf(String path, UUID actor, String roles) throws Exception {
        var r = send("GET", path, null, actor, roles, null, Map.of());
        if (r.statusCode() >= 300) {
            throw new IllegalStateException("GET " + path + " → " + r.statusCode() + " " + r.body());
        }
        return r.headers().firstValue("ETag").orElseThrow();
    }

    JsonNode tree(String body) throws Exception {
        return body == null || body.isBlank() ? om.createObjectNode() : om.readTree(body);
    }

    private JsonNode multipart(String path, String filename, byte[] content, UUID actor, String roles)
            throws Exception {
        String boundary = "----atomdev" + UUID.randomUUID();
        var out = new java.io.ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename
                + "\"\r\nContent-Type: text/csv\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var req = HttpRequest.newBuilder(URI.create(base + path)).header("X-Actor-Id", actor.toString())
                .header("X-Roles", roles).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return parse("POST", path, http.send(req, HttpResponse.BodyHandlers.ofString()));
    }

    JsonNode parse(String method, String path, HttpResponse<String> r) throws Exception {
        if (r.statusCode() >= 300) {
            throw new IllegalStateException(method + " " + path + " → " + r.statusCode() + " " + r.body());
        }
        return r.body() == null || r.body().isBlank() ? om.createObjectNode() : om.readTree(r.body());
    }

    static void log(String s) {
        System.out.println("[dev-seed] " + s);
    }
}
