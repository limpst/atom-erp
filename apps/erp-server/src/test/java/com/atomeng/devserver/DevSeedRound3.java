package com.atomeng.devserver;

import static com.atomeng.devserver.DevSeed.ADMIN;
import static com.atomeng.devserver.DevSeed.ENG;
import static com.atomeng.devserver.DevSeed.EXEC;
import static com.atomeng.devserver.DevSeed.FIN;
import static com.atomeng.devserver.DevSeed.PM;
import static com.atomeng.devserver.DevSeed.PM2;
import static com.atomeng.devserver.DevSeed.SYSADMIN;
import static com.atomeng.devserver.DevSeed.id;
import static com.atomeng.devserver.DevSeed.log;
import static com.atomeng.devserver.DevSeed.map;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * dev 시드 3차(웹 3차 화면 HRQ · CST · FCD · AIP) — 실제 HTTP API 로만 만든다(직접 SQL 없음). 전부 합성 데이터.
 * <ul>
 *   <li>HRQ: 승인 대기 자격 2건(정참여 · 문책임), 지지난달 월 마감(→ CST 인·일 대사)</li>
 *   <li>CST: 지지난달 회계 마감, 문책임 타임시트 정정(REVERSAL 짝), EVM 스냅샷 6주(추이 차트)</li>
 *   <li>FCD: 행 오류(수식처럼 보이는 원문 포함) 배치(확정 전), 채널 간 중복 확인 큐 1건, FIN 작성 룰셋 초안(2027 시행)</li>
 *   <li>AIP: 워커 대역을 잠깐 켜서 균열 탐지 Job 2건 성공 → 제안 2건(1건 수락), 끈 뒤 대기 · 취소 · 마감 초과(DEAD) Job</li>
 * </ul>
 */
final class DevSeedRound3 {

    private final DevSeed s;
    private final LocalDate today;

    DevSeedRound3(DevSeed s, LocalDate today) {
        this.s = s;
        this.today = today;
    }

    void run(UUID project) throws Exception {
        seedHrqCst(project);
        seedFcd();
        seedAip(project);
    }

    // ==================================================================== HRQ · CST

    private void seedHrqCst(UUID project) throws Exception {
        // 승인 대기 자격 — 화면에서 ADMIN 이 승인/반려(E2E 는 정참여 것을 승인)
        s.call("POST", "/api/v1/hrq/employees/" + ENG + "/credentials", map("typeCode", "SAFETY_DIAG_CERT",
                "certNo", "(합성) SDC-2026-0005", "acquiredOn", today.minusDays(3).toString(),
                "expiresOn", today.plusDays(200).toString()), ENG, "ENG", null);
        s.call("POST", "/api/v1/hrq/employees/" + PM2 + "/credentials", map("typeCode", "ARCH_ENG",
                "certNo", "(합성) AE-2019-0010", "acquiredOn", "2019-06-01"), PM2, "ENG", null);

        // 원가 정정 짝: 문책임(MASTER) 지난주 월 · 화 8h 제출 → PM 승인 → PM 이 월요일 8h → 6h 정정(REVERSAL + 새 TIMESHEET)
        LocalDate mon = today.minusWeeks(1).with(DayOfWeek.MONDAY);
        String week = "%d-W%02d".formatted(mon.get(IsoFields.WEEK_BASED_YEAR), mon.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
        JsonNode rows = s.call("PUT", "/api/v1/hrq/timesheets/" + PM2 + "/weeks/" + week, map("submit", true,
                "entries", List.of(
                        map("projectId", project, "workDate", mon.toString(), "workType", "FIELD", "hours", 8),
                        map("projectId", project, "workDate", mon.plusDays(1).toString(), "workType", "REVIEW",
                                "hours", 8))), PM2, "ENG", null);
        s.call("POST", "/api/v1/hrq/timesheets/approvals", map("entryIds",
                List.of(rows.get(0).get("id").asText(), rows.get(1).get("id").asText())), PM, "PM", null);
        Thread.sleep(1500);   // CST 인건비 전기(outbox) 뒤에 정정해야 원장에 원 행이 있다(없으면 서버가 재시도)
        s.call("POST", "/api/v1/hrq/timesheets/" + rows.get(0).get("id").asText() + ":correct",
                map("hours", 6, "reason", "(합성) 현장 조기 종료 — 정정 시드"), PM, "PM", null);

        // 지지난달: HRQ 월 마감 → (hrq.manday.closed → CST 인·일 대사) → CST 회계 마감
        String ym = YearMonth.from(today).minusMonths(2).format(DateTimeFormatter.ofPattern("yyyyMM"));
        s.call("POST", "/api/v1/hrq/month-close/" + ym, map(), ADMIN, "ADMIN", null);
        boolean closed = false;
        for (int i = 0; i < 60 && !closed; i++) {
            JsonNode p = s.call("GET", "/api/v1/cst/periods/" + ym, null, ADMIN, "ADMIN", null);
            if (p.path("reconciliation").path("status").asText("").equals("MATCHED")) {
                JsonNode r = s.call("POST", "/api/v1/cst/periods/" + ym + ":close", map(), ADMIN, "ADMIN", null);
                closed = r.path("closed").asBoolean();
            } else {
                Thread.sleep(250);
            }
        }

        // EVM 추이: 6주 전부터 매주 · 오늘 스냅샷(GET /evm 이 asOf 별 스냅샷을 저장)
        for (int w = 6; w >= 0; w--) {
            s.call("GET", "/api/v1/cst/projects/" + project + "/evm?asOf=" + today.minusWeeks(w), null, EXEC, "EXEC", null);
        }
        log("HRQ 자격 대기 2 · 월 마감 " + ym + " / CST 마감 " + (closed ? "완료" : "대기") + " · 정정 짝 1 · EVM 스냅샷 7");
    }

    // ==================================================================== FCD

    private void seedFcd() throws Exception {
        // 행 오류 배치(확정 전): 날짜 오류 1행 · 금액 칸에 수식처럼 보이는 값 1행 → 오류 리포트 CSV 는 ' 로 무력화
        String csv = """
                (가상)에이카드 법인카드 이용내역(승인)
                조회기간,합성,출력일,2026.10.19
                이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고
                2026.06.03,12:10,999999******1234,31000101,(예시)김밥천국,,일반음식점,"8,500",KRW,,
                2026.13.45,12:20,999999******1234,31000102,(예시)분식나라,,일반음식점,"7,000",KRW,,
                2026.06.04,18:30,999999******1234,31000103,(예시)문구사,,문구,=1+2,KRW,,
                2026.06.05,09:05,999999******1234,31000104,(예시)카페,,커피전문점,"4,500",KRW,,
                """;
        JsonNode bad = s.multipart("/api/v1/fcd/imports", "statement-2026-06-errors.csv",
                csv.getBytes(StandardCharsets.UTF_8), FIN, "FIN");

        // 채널 간 중복 확인 큐: 승인 CSV 의 (예시)한식당 38,000원(5/12) ↔ 매입 XLSX "(예시)한식당본점"(승인번호 없음, 날짜만)
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet ws = wb.createSheet("매입내역");
            String[] head = {"이용일자", "카드번호", "승인번호", "가맹점명", "매출금액", "매입일자", "구분"};
            Row h = ws.createRow(0);
            for (int i = 0; i < head.length; i++) {
                h.createCell(i).setCellValue(head[i]);
            }
            Row x = ws.createRow(1);
            x.createCell(0).setCellValue("2026-05-12");
            x.createCell(1).setCellValue("999999******1234");
            x.createCell(3).setCellValue("(예시)한식당본점");
            x.createCell(4).setCellValue(38000d);
            x.createCell(5).setCellValue("2026-05-14");
            x.createCell(6).setCellValue("매입");
            wb.write(out);
            xlsx = out.toByteArray();
        }
        JsonNode purchase = s.multipart("/api/v1/fcd/imports", "purchase-2026-05.xlsx", xlsx, FIN, "FIN");
        s.call("POST", "/api/v1/fcd/imports/" + id(purchase) + ":commit", map(), FIN, "FIN", null);
        int queue = s.call("GET", "/api/v1/fcd/dedup-candidates", null, FIN, "FIN", null).size();

        // 룰셋 초안(FIN 작성 · 2027-01-01 시행 — 승인돼도 현재 채점에 영향 없음). 원문은 저장소 YAML 을 버전만 바꿔 씀
        String yaml = new String(getClass().getResourceAsStream("/fcd/rulesets/fcd-2026.5.0.yaml").readAllBytes(),
                StandardCharsets.UTF_8)
                .replaceFirst("(?m)^version: .*$", "version: 2026.6.0-dev")
                .replaceFirst("(?m)^effective_from: .*$", "effective_from: '2027-01-01'")
                .replaceFirst("(?m)^status: .*$", "status: DRAFT");
        var r = s.sendRaw("POST", "/api/v1/fcd/rule-sets", yaml, "application/yaml", FIN, "FIN");
        if (r.statusCode() >= 300) {
            throw new IllegalStateException("룰셋 초안 " + r.statusCode() + " " + r.body());
        }
        log("FCD 오류 배치 1(행 오류 " + bad.path("rows_error").asInt() + ") · 중복 확인 큐 " + queue
                + " · 룰셋 초안 2026.6.0-dev");
    }

    // ==================================================================== AIP

    private void seedAip(UUID project) throws Exception {
        JsonNode rounds = s.call("GET", "/api/v1/fld/rounds?projectId=" + project, null, PM, "PM", null);
        JsonNode photo = null;
        for (JsonNode p : s.call("GET", "/api/v1/fld/rounds/" + rounds.get(0).get("id").asText() + "/photos", null, PM,
                "PM", null)) {
            if ("VERIFIED".equals(p.path("uploadState").asText())) {
                photo = p;
            }
        }
        if (photo == null) {
            log("AIP 건너뜀 — 검증 사진 없음");
            return;
        }
        Map<String, Object> input = map("attachmentId", photo.get("attachmentId").asText(),
                "sha256", photo.get("serverSha256").asText());
        int succeeded = 0;
        DevAipWorker.online = true;   // 시드 동안만 워커 대역 켬
        try {
            for (double minConf : new double[] {0.35, 0.5}) {
                s.call("POST", "/api/v1/aip/jobs", map("serviceCode", "CRACK_DETECT", "priority", 1,
                        "projectId", project, "inputs", List.of(input),
                        "params", map("chain", List.of("CRACK_MEASURE"), "minConfidence", minConf)), ENG, "ENG", null);
            }
            for (int i = 0; i < 40 && succeeded < 2; i++) {
                s.call("POST", "/api/v1/aip/jobs:run-next", map(), SYSADMIN, "SYSADMIN", null);
                succeeded = s.call("GET", "/api/v1/aip/jobs?status=SUCCEEDED", null, DevSeed.QA, "QA", null).size();
            }
        } finally {
            DevAipWorker.online = false;
        }
        // 제안 결정: 1건 수락(나머지는 대기)
        JsonNode sugg = s.call("GET", "/api/v1/aip/suggestions?status=SUGGESTED", null, ENG, "ENG", null);
        if (sugg.size() > 0) {
            s.call("POST", "/api/v1/aip/suggestions/" + sugg.get(sugg.size() - 1).get("id").asText() + "/decision",
                    map("decision", "ACCEPTED"), ENG, "ENG", null);
        }
        // 워커 꺼진 뒤: 대기(QUEUED) · 취소(CANCELLED) · 마감 초과(DEAD)
        s.call("POST", "/api/v1/aip/jobs", map("serviceCode", "KB_SEARCH", "priority", 3,
                "params", map("query", "(합성) 백태 동반 균열 보수 기준")), PM, "PM", null);
        s.call("POST", "/api/v1/aip/jobs", map("serviceCode", "CRACK_MEASURE", "priority", 2, "projectId", project,
                "inputs", List.of(input)), ENG, "ENG", null);
        JsonNode cancel = s.call("POST", "/api/v1/aip/jobs", map("serviceCode", "CRACK_DETECT", "priority", 4,
                "projectId", project, "inputs", List.of(input), "params", map("minConfidence", 0.9)), ENG, "ENG", null);
        s.call("DELETE", "/api/v1/aip/jobs/" + id(cancel), null, ENG, "ENG", null);
        s.call("POST", "/api/v1/aip/jobs", map("serviceCode", "CRACK_DETECT", "priority", 3, "projectId", project,
                "inputs", List.of(input), "params", map("minConfidence", 0.8), "deadlineSeconds", 1), ENG, "ENG", null);
        Thread.sleep(1500);
        s.call("POST", "/api/v1/aip/jobs:reap", map(), SYSADMIN, "SYSADMIN", null);
        log("AIP Job 성공 " + succeeded + " · 제안 " + sugg.size() + "(1 수락) · 대기 2 · 취소 1 · 마감 초과 1");
    }
}
