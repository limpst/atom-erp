package com.atomeng.devserver;

import static com.atomeng.devserver.DevSeed.ADMIN;
import static com.atomeng.devserver.DevSeed.ENG;
import static com.atomeng.devserver.DevSeed.EXEC;
import static com.atomeng.devserver.DevSeed.PM;
import static com.atomeng.devserver.DevSeed.PM2;
import static com.atomeng.devserver.DevSeed.QA;
import static com.atomeng.devserver.DevSeed.etag;
import static com.atomeng.devserver.DevSeed.id;
import static com.atomeng.devserver.DevSeed.log;
import static com.atomeng.devserver.DevSeed.map;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;

/**
 * 웹 2차 화면(FAC · EQP · FLD · DEM · PTL)용 합성 시드 — 실제 HTTP API 로만(직접 SQL 없음).
 * 건물 · 장비 · 사람 · 회사는 모두 가짜. 첨부 파일은 로컬 저장소의 "서명 URL 업로드"를 흉내 내 임시 키에 쓴다(S3 PUT 대역).
 *
 * <ul>
 *   <li>FAC: 한빛타워(주소 조회 · 대장 자동 입력 · 2종) · 별관(수기) · 별관 중복 등록(병합 연습) · 푸른아파트(B 고객)</li>
 *   <li>EQP: 교정 유효 · 60일 이내 · 만료 · 교정 불필요(반출 중) · 미교정 장비, 예약 2건</li>
 *   <li>FLD: 한빛타워 1회차 — 구역 3개(잠금 1 · 충돌 · 필수 측정 누락 · 사진 검증 실패), 동기화 충돌 2건</li>
 *   <li>DEM: 푸른아파트 해체 감리(B 고객) — 감리자(자격 만료 임박 보조), 감리일지(잠금 2 · 제출 1), 도서 · 트랜스미털 · 검토의견</li>
 *   <li>PTL: A 고객 보고서(1판 → 정정 2판, 대체 배너) · B 고객 보고서 1건, 서비스 요청 1건</li>
 * </ul>
 * 보안 검토 2026-10-19 정책(QAR-01 · 03b · RPT-07 · DEM-03): 등급 확정은 RESP 최책임(HRQ 건축기사 자격 유효), QA 는 자동 배정
 * (자기 배정 금지), 최종 승인은 작성에 참여하지 않은 두 번째 PM 문책임(PART). 감리일지 정정 요청 1건(정참여 → 승인 대기).
 */
final class DevSeedRound2 {

    /** 고객 포털 dev 사용자(헤더 X-Actor-Id) — apps/erp-web/src/auth/devUsers.ts 와 같다 */
    static final UUID CUSTOMER_A = DevSeed.user(11), CUSTOMER_B = DevSeed.user(12);

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final DevSeed s;
    private final LocalDate today;
    private final Path evidenceRoot;
    private final AtomicInteger hlcCounter = new AtomicInteger();
    /** RPT 보고서 대상(현장조사 회차가 없는 동 — 회차 사진이 스냅샷에 섞이지 않게) */
    private UUID annex;

    DevSeedRound2(DevSeed s, LocalDate today, Path evidenceRoot) {
        this.s = s;
        this.today = today;
        this.evidenceRoot = evidenceRoot;
    }

    void run(UUID clientA, UUID clientB, UUID projectA) throws Exception {
        // ---------------------------------------------------------------- 6. FAC
        UUID bA = building(map("lookupAddress", "합성로 1101", "ownerClientId", clientA));
        classify(bA, "CLASS2");
        call("POST", "/api/v1/fac/buildings/" + bA + "/nodes:generate", map("floorFrom", 1, "floorTo", 2,
                "xGrid", List.of("X1", "X2"), "yGrid", List.of("Y1", "Y2"), "memberTypes", List.of("COL")), PM, "PM");
        JsonNode tree = call("GET", "/api/v1/fac/buildings/" + bA + "/nodes", null, PM, "PM");
        annex = building(map("name", "(합성) 한빛타워 별관", "roadAddress", "서울 합성구 합성로 1101-2",
                "mainPurposeNm", "업무시설", "structureNm", "철근콘크리트구조", "grossFloorArea", 2100.0,
                "floorsAbove", 3, "floorsBelow", 0, "approvalDate", "1998-03-01", "dataSource", "MANUAL",
                "ownerClientId", clientA));
        classify(annex, "CLASS3");
        // 같은 주소 · 비슷한 이름의 중복 등록(사유 남기고 강제) — 시설물 상세의 병합 대화상자 연습용
        building(map("name", "(합성) 한빛타워 별관동", "roadAddress", "서울 합성구 합성로 1101-2",
                "floorsAbove", 3, "floorsBelow", 0, "dataSource", "MANUAL", "ownerClientId", clientA,
                "force", true, "forceReason", "(합성) 현장 담당자 이중 등록 — 병합 예정"));
        UUID bB = building(map("lookupAddress", "합성로 1202", "ownerClientId", clientB));
        classify(bB, "CLASS2");
        log("FAC 시설물 4동");

        // ---------------------------------------------------------------- 7. EQP
        UUID eqCrack = equipment("MSR-CRK", "SYN-CRK-01", "(합성) 균열폭 측정기", today.minusDays(100), today.plusDays(200));
        equipment("NDT-UPV", "SYN-UPV-01", "(합성) 초음파 탐상기", today.minusDays(345), today.plusDays(20));
        equipment("NDT-RH", "SYN-RH-01", "(합성) 반발경도 시험기", today.minusDays(375), today.minusDays(10));
        UUID eqCore = equipment("NDT-CORE", "SYN-CORE-01", "(합성) 코어 채취기", null, null);
        equipment("SRV-LVL", "SYN-LVL-01", "(합성) 레벨 측량기", null, null);
        // 장비 일 손료(⚠️ 예시 · 합성 값) — 없으면 반납 이벤트(eqp.equipment.returned)를 CST 가 원가로 못 올려 재시도 · DLQ
        JsonNode eqList = call("GET", "/api/v1/eqp/equipment?size=100", null, ADMIN, "ADMIN");
        for (JsonNode e : eqList.has("content") ? eqList.get("content") : eqList) {
            JsonNode rate = call("POST", "/api/v1/cst/rates", map("rateKind", "EQUIP_DAILY", "keyCode",
                    e.get("code").asText(), "value", 30000, "validFrom", "2000-01-01",
                    "sourceRef", "⚠️ 예시 — (합성) 개발 시드 장비 손료"), ADMIN, "ADMIN");
            s.call("POST", "/api/v1/cst/rates/" + id(rate) + ":approve", map(), EXEC, "EXEC", etag(rate));
        }
        JsonNode r1 = call("POST", "/api/v1/eqp/reservations", map("equipmentId", eqCrack, "projectId", projectA,
                "from", today.plusDays(5).toString(), "to", today.plusDays(7).toString()), ENG, "ENG");
        call("POST", "/api/v1/eqp/reservations/" + id(r1) + ":approve", map(), ADMIN, "ADMIN");
        call("POST", "/api/v1/eqp/reservations", map("equipmentId", eqCrack, "projectId", projectA,
                "from", today.plusDays(10).toString(), "to", today.plusDays(11).toString()), ENG, "ENG");
        String coreQr = call("POST", "/api/v1/eqp/equipment/" + eqCore + "/labels", map("rotate", false), ADMIN,
                "ADMIN").get("token").asText();
        call("POST", "/api/v1/eqp/checkouts", map("clientOpId", UUID.randomUUID(), "qrToken", coreQr,
                "projectId", projectA, "preCheck", map("exterior", "OK")), ENG, "ENG");
        log("EQP 장비 5대 · 예약 2 · 반출 1");

        // ---------------------------------------------------------------- 8. FLD
        seedFld(projectA, bA, tree);

        // ---------------------------------------------------------------- 9. DEM
        UUID projectB = seedDem(clientB, bB);

        // ---------------------------------------------------------------- 10. RPT → PTL
        // RPT-07: 등급 확정자 = 프로젝트 RESP + HRQ 자격 유효(합성 번호, 무기한)
        credential(PM, "ARCH_ENG", "SYN-RPT-PM", "2012-03-02", null);
        // A 고객: 1판 납품 → 정정 2판 발행(1판 대체) → 2판 납품. B 고객: 1판 납품
        UUID[] repA = report(projectA, annex, "RT_PRC", "(합성) 한빛타워 별관 정밀안전점검 보고서", "B");
        deliver(repA[1]);
        JsonNode rev2 = call("POST", "/api/v1/rpt/reports/" + repA[0] + "/revisions",
                map("changeNote", "(합성) 균열폭 측정 오기 정정", "reuseSnapshot", true), PM, "PM");
        call("POST", "/api/v1/rpt/reports/" + repA[0] + "/snapshots", s.tree(snapshotBody(annex, "C")), PM, "PM");
        composeToPublish(id(rev2), repA[2]);
        deliver(id(rev2));
        UUID[] repB = report(projectB, bB, "RT_REG", "(합성) 푸른아파트 정기안전점검 보고서", "B");
        deliver(repB[1]);
        // A 고객 서비스 요청(고객 포털)
        for (int i = 0; i < 80; i++) {   // 납품 이벤트 → PTL 공개본(Relay 비동기)
            if (customer("GET", "/api/v1/ptl/reports", null, CUSTOMER_A, clientA).size() >= 2
                    && customer("GET", "/api/v1/ptl/reports", null, CUSTOMER_B, clientB).size() >= 1) {
                break;
            }
            Thread.sleep(250);
        }
        customer("POST", "/api/v1/ptl/service-requests", map("kind", "VERIFY_REPAIR", "facilityId", annex,
                "description", "(합성) 2층 기둥 균열 보수 완료 — 보수 확인 점검 요청", "contactName", "(합성) 김담당"),
                CUSTOMER_A, clientA);
        log("PTL 보고서 A 2건(1건 대체) · B 1건 · 서비스 요청 1건");
    }

    // ==================================================================== FLD

    private void seedFld(UUID project, UUID building, JsonNode tree) throws Exception {
        UUID round = id(call("POST", "/api/v1/fld/rounds", map("projectId", project, "buildingId", building,
                "surveyType", "PRECISE_INSPECTION"), PM, "PM"));
        UUID z1 = id(call("POST", "/api/v1/fld/rounds/" + round + "/zones", map("zoneName", "1F-전체",
                "floorNodeId", node(tree, "1F"), "assigneeId", ENG), PM, "PM"));
        UUID z2 = id(call("POST", "/api/v1/fld/rounds/" + round + "/zones", map("zoneName", "2F-전체",
                "floorNodeId", node(tree, "2F"), "assigneeId", ENG), PM, "PM"));
        UUID z3 = id(call("POST", "/api/v1/fld/rounds/" + round + "/zones", map("zoneName", "1F-계단실",
                "floorNodeId", node(tree, "1F"), "assigneeId", ENG), PM, "PM"));
        UUID devA = UUID.fromString("00000000-0000-7000-8000-00000000da0a");
        UUID devB = UUID.fromString("00000000-0000-7000-8000-00000000db0b");
        UUID d1 = UUID.randomUUID(), d2 = UUID.randomUUID(), d3 = UUID.randomUUID(), d4 = UUID.randomUUID();
        push(ENG, "ENG", devA, round, 0,
                op(d1, "DefectCreated", created(z1, node(tree, "1F-C-X1Y1"))),
                op(d1, "DefectMeasured", measured("width_mm", "0.20")),
                op(d1, "DefectMeasured", measured("length_m", "1.10")),
                op(d2, "DefectCreated", created(z2, node(tree, "2F-C-X1Y2"))),
                op(d2, "DefectMeasured", measured("width_mm", "0.15")),   // 길이 미측정 → 필수 측정 누락(DRAFT)
                op(d3, "DefectCreated", created(z2, node(tree, "2F-C-X2Y2"))),
                op(d3, "DefectMeasured", measured("width_mm", "0.35")),
                op(d3, "DefectMeasured", measured("length_m", "2.10")),
                op(d4, "DefectCreated", created(z3, node(tree, "1F-C-X2Y1"))),
                op(d4, "DefectMeasured", measured("width_mm", "0.10")),
                op(d4, "DefectMeasured", measured("length_m", "0.60")));
        // 사진: d4 검증 완료(업로드) · d1 업로드 대기(PENDING) · d3 해시 불일치(REJECTED → 재업로드 요청)
        photo(round, d4, devA, jpeg(4), false);
        UUID pending = UUID.randomUUID();
        byte[] p1 = jpeg(1);
        push(ENG, "ENG", devA, round, 0, op(d1, "PhotoAttached", map("photoId", pending, "sha256", sha(p1),
                "kind", "NEAR", "sizeBytes", p1.length)));
        photo(round, d3, devA, jpeg(3), true);
        // 잠금: 계단실 구역(문제 없음)만 잠근다 → 회차는 진행 중 유지
        call("POST", "/api/v1/fld/zones/" + z3 + "/lock", map(), PM, "PM");
        // 동기화 충돌 2건: 기기 A(ENG) 와 기기 B(PM) 가 같은 마지막 동기화 지점에서 같은 측정 항목을 다르게 올림
        long seq = call("GET", "/api/v1/fld/rounds/" + round, null, PM, "PM").get("lastSeq").asLong();
        push(ENG, "ENG", devA, round, seq, op(d1, "DefectMeasured", measured("width_mm", "0.25")));
        push(PM, "PM", devB, round, seq, op(d1, "DefectMeasured", measured("width_mm", "0.30")));
        push(ENG, "ENG", devA, round, seq, op(d3, "DefectMeasured", measured("length_m", "2.40")));
        push(PM, "PM", devB, round, seq, op(d3, "DefectMeasured", measured("length_m", "2.55")));
        int conflicts = call("GET", "/api/v1/fld/sync/conflicts?roundId=" + round, null, PM, "PM").size();
        log("FLD 회차 1 · 구역 3(잠금 1) · 결함 4 · 사진 3 · 충돌 " + conflicts);
    }

    private String hlc(String node) {
        return "%016d:%04d:%s".formatted(System.currentTimeMillis(), hlcCounter.incrementAndGet() % 10000, node);
    }

    private Map<String, Object> op(UUID defect, String type, Map<String, Object> payload) {
        return map("opId", UUID.randomUUID(), "defectId", defect, "eventType", type, "payload", payload);
    }

    private Map<String, Object> created(UUID zone, UUID node) {
        return map("zoneId", zone, "typeCode", "CRK", "subtypeCode", "FLEXURE", "sheetVersion", 1,
                "geomNorm", map("type", "LineString", "coords", List.of(List.of(0.41, 0.33), List.of(0.46, 0.34))),
                "locationNodeId", node);
    }

    private Map<String, Object> measured(String item, String value) {
        return map("item", item, "value", Double.parseDouble(value), "unit", item.endsWith("_mm") ? "mm" : "m",
                "method", "VISUAL", "measuredAt", OffsetDateTime.now(SEOUL).toString());
    }

    @SafeVarargs
    private JsonNode push(UUID actor, String role, UUID device, UUID round, long lastPulled,
                          Map<String, Object>... ops) throws Exception {
        List<Map<String, Object>> list = new ArrayList<>();
        for (Map<String, Object> o : ops) {
            o.put("hlc", hlc(device.toString().substring(32)));
            list.add(o);
            Thread.sleep(2);   // HLC 단조 증가
        }
        JsonNode r = call("POST", "/api/v1/fld/sync/push", map("deviceId", device, "roundId", round,
                "clientLastPulledSeq", lastPulled, "ops", list), actor, role);
        for (JsonNode x : r.get("results")) {
            if ("REJECTED".equals(x.get("status").asText())) {
                throw new IllegalStateException("FLD op 거부: " + x);
            }
        }
        return r;
    }

    /** PhotoAttached → 업로드 세션 → 한 번에 PATCH. corrupt=true 면 같은 길이의 다른 바이트(해시 불일치 → REJECTED) */
    private void photo(UUID round, UUID defect, UUID device, byte[] img, boolean corrupt) throws Exception {
        UUID photoId = UUID.randomUUID();
        String sha = sha(img);
        push(ENG, "ENG", device, round, 0, op(defect, "PhotoAttached", map("photoId", photoId, "sha256", sha,
                "kind", "NEAR", "sizeBytes", img.length)));
        JsonNode up = call("POST", "/api/v1/fld/uploads", map("photoId", photoId, "roundId", round, "defectId", defect,
                "deviceId", device, "sha256", sha, "sizeBytes", img.length, "kind", "NEAR",
                "takenAt", OffsetDateTime.now(SEOUL).toInstant().toString()), ENG, "ENG");
        byte[] body = img.clone();
        if (corrupt) {
            body[body.length / 2] ^= 0x5A;
        }
        var r = s.patchBytes("/api/v1/fld/uploads/" + up.get("uploadId").asText(), body,
                Map.of("Upload-Offset", "0"), ENG, "ENG");
        if (corrupt ? r.statusCode() != 409 : r.statusCode() != 200) {
            throw new IllegalStateException("FLD 사진 업로드 " + r.statusCode() + " " + r.body());
        }
    }

    private static byte[] jpeg(int seed) throws Exception {
        BufferedImage img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        Random rnd = new Random(seed);
        for (int y = 0; y < 120; y++) {
            for (int x = 0; x < 160; x++) {
                int g = 110 + rnd.nextInt(40) + (Math.abs(y - 60 - (x - 80) / 3) < 2 ? -90 : 0);   // 회색 벽 + 균열 선
                img.setRGB(x, y, new Color(g, g, g).getRGB());
            }
        }
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    // ==================================================================== DEM

    private UUID seedDem(UUID clientB, UUID bB) throws Exception {
        UUID project = id(call("POST", "/api/v1/core/projects", map("name", "(합성) 푸른아파트 해체공사 감리",
                "serviceType", "DEMOLITION_SUPERVISION", "clientId", clientB, "startDate",
                today.minusMonths(1).toString(), "endDate", today.plusMonths(5).toString()), PM, "PM"));
        String from = today.minusMonths(1).toString();
        String to = today.plusMonths(6).toString();
        call("POST", "/api/v1/core/projects/" + project + "/members",
                map("employeeId", PM, "roleInProject", "RESP", "fromDate", from, "toDate", to), EXEC, "EXEC");
        for (UUID e : List.of(ENG, PM2)) {
            call("POST", "/api/v1/core/projects/" + project + "/members",
                    map("employeeId", e, "roleInProject", "PART", "fromDate", from, "toDate", to), PM, "PM");
        }
        // HRQ 자격(합성 번호): 정참여 건축기사(무기한) · 문책임 안전진단 자격(20일 뒤 만료 → 만료 임박) · 한품질 만료 자격
        credential(ENG, "ARCH_ENG", "SYN-DEM-ENG", "2019-01-01", null);
        credential(PM2, "SAFETY_DIAG_CERT", "SYN-DEM-PM2", "2016-05-01", today.plusDays(20).toString());
        credential(QA, "SAFETY_DIAG_CERT", "SYN-DEM-QA", "2015-03-01", today.minusDays(30).toString());

        JsonNode dem = call("POST", "/api/v1/dem/projects", map("projectId", project, "buildingId", bB,
                "packages", List.of(map("packageType", "DEM_SUPERVISION"),
                        map("packageType", "STR_DESIGN", "designCode", "KDS 41"))), PM, "PM");
        UUID supWp = null, designWp = null;
        for (JsonNode w : dem.get("workPackages")) {
            if ("DEM_SUPERVISION".equals(w.get("packageType").asText())) {
                supWp = id(w);
            } else {
                designWp = id(w);
            }
        }
        String validFrom = today.minusDays(10).toString();
        call("POST", "/api/v1/dem/work-packages/" + supWp + "/supervisors", map("employeeId", ENG, "role", "CHIEF",
                "validFrom", validFrom, "reason", "(합성) 책임 감리원 지정"), PM, "PM");
        call("POST", "/api/v1/dem/work-packages/" + supWp + "/supervisors", map("employeeId", PM2,
                "role", "ASSISTANT", "validFrom", validFrom, "reason", "(합성) 보조 감리원"), PM, "PM");
        for (String st : List.of("PLANNING", "REVIEWING", "PERMIT_PENDING", "IN_PROGRESS")) {
            call("POST", "/api/v1/dem/projects/" + project + ":transition", map("to", st), PM, "PM");
        }
        // 감리일지: 그제 · 어제 제출 → 자정 잠금 배치(수동 실행) → 오늘 일지는 제출 상태(잠금 전)
        UUID oldest = null;
        for (int back : new int[] {2, 1}) {
            JsonNode l = dailyLog(supWp, today.minusDays(back), 30 + (2 - back) * 10, back == 1);
            if (back == 2) {
                oldest = id(l);
            }
        }
        call("POST", "/api/v1/dem/daily-logs:lock-due", map(), ADMIN, "ADMIN");
        dailyLog(supWp, today, 55, false);
        // DEM-03(b): 감리원 본인 일지 정정은 즉시 반영되지 않고 정정 요청(PENDING) → PM(최책임 · 문책임) 승인 대기
        call("POST", "/api/v1/dem/daily-logs/" + oldest + "/amendments", map("fieldPath", "workersCount",
                "newValue", 13, "reason", "(합성) 출역 인원 오기 — 13명으로 정정 요청"), ENG, "ENG");

        // 구조 도서: S-101 A판 검토용 발행(0) → 검토의견 2건(1건 미결) → B판 승인(시공용 발행은 미결 의견으로 막힘)
        //           S-102 A판 승인(미결 없음 → 시공용 발행 가능)
        UUID s101 = id(call("POST", "/api/v1/dem/work-packages/" + designWp + "/documents",
                map("docNo", "S-101", "title", "(합성) 지하 1층 구조평면도"), ENG, "ENG"));
        UUID s101a = approvedRevision(s101, designWp, "S-101 A", "(합성) 최초 작성");
        call("POST", "/api/v1/dem/transmittals", map("workPackageId", designWp, "purpose", "FOR_REVIEW",
                "revisionIds", List.of(s101a), "recipientName", "(합성) 푸른시설공단 시설팀"), PM, "PM");
        UUID c1 = id(call("POST", "/api/v1/dem/revisions/" + s101a + "/comments", map("source", "CLIENT",
                "severity", "MAJOR", "body", "(합성) 지하 외벽 철근 피복 두께 근거 보완 요청"), QA, "QA"));
        UUID c2 = id(call("POST", "/api/v1/dem/revisions/" + s101a + "/comments", map("source", "INTERNAL",
                "severity", "MINOR", "body", "(합성) 도면 범례 오탈자"), QA, "QA"));
        call("POST", "/api/v1/dem/comments/" + c2 + "/replies", map("body", "(합성) 수정 반영",
                "resultingStatus", "RESPONDED"), ENG, "ENG");
        call("POST", "/api/v1/dem/comments/" + c2 + "/replies", map("body", "(합성) 확인", "resultingStatus",
                "ACCEPTED"), QA, "QA");
        call("POST", "/api/v1/dem/comments/" + c2 + "/replies", map("body", "(합성) 종결", "resultingStatus",
                "CLOSED"), QA, "QA");
        call("POST", "/api/v1/dem/comments/" + c1 + "/replies", map("body", "(합성) 피복 계산서 첨부 예정",
                "resultingStatus", "RESPONDED"), ENG, "ENG");
        approvedRevision(s101, designWp, "S-101 B", "(합성) 검토의견 반영");
        UUID s102 = id(call("POST", "/api/v1/dem/work-packages/" + designWp + "/documents",
                map("docNo", "S-102", "title", "(합성) 지상 1층 구조평면도"), ENG, "ENG"));
        approvedRevision(s102, designWp, "S-102 A", "(합성) 최초 작성");
        log("DEM 해체 감리 프로젝트 1 · 감리일지 3(잠금 2, 정정 요청 1) · 도서 2 · 검토의견 2(미결 1)");
        return project;
    }

    private void credential(UUID emp, String type, String certNo, String acquired, String expires) throws Exception {
        Map<String, Object> b = map("typeCode", type, "certNo", certNo, "acquiredOn", acquired);
        if (expires != null) {
            b.put("expiresOn", expires);
        }
        UUID cred = id(call("POST", "/api/v1/hrq/employees/" + emp + "/credentials", b, emp, "ENG"));
        call("POST", "/api/v1/hrq/credentials/" + cred + ":approve", map(), ADMIN, "ADMIN");
    }

    private JsonNode dailyLog(UUID wp, LocalDate date, int progress, boolean withFail) throws Exception {
        List<Map<String, Object>> checklist = new ArrayList<>();
        checklist.add(map("templateItemId", "0192d000-0000-7000-8000-000000001035", "result", withFail ? "FAIL" : "PASS",
                "severity", withFail ? "MEDIUM" : null, "note", withFail ? "(합성) 해체 순서 일부 역순 — 시정 지시" : null));
        return call("PUT", "/api/v1/dem/daily-logs/" + wp + "/" + date, map("clientOpId", UUID.randomUUID(),
                "phaseCode", "UPPER_DEMOLITION", "progressPct", progress, "workersCount", 12,
                "weather", map("sky", "맑음", "tempC", 17), "equipment", List.of(map("kind", "굴삭기(합성)", "count", 2)),
                "checklist", checklist, "remarks", "(합성) " + date + " 상부 해체 진행", "submit", true), ENG, "ENG");
    }

    private UUID approvedRevision(UUID doc, UUID wp, String label, String summary) throws Exception {
        UUID file = pdfAttachment(wp, label);
        UUID rev = id(call("POST", "/api/v1/dem/documents/" + doc + "/revisions", map("fileAttachmentId", file,
                "changeSummary", summary), ENG, "ENG"));
        call("POST", "/api/v1/dem/revisions/" + rev + ":request-review", map(), ENG, "ENG");
        call("POST", "/api/v1/dem/revisions/" + rev + ":approve", map(), PM, "PM");
        return rev;
    }

    /** core 첨부: presign → 임시 키에 파일 쓰기(로컬 저장소의 서명 URL PUT 대역) → :complete */
    private UUID pdfAttachment(UUID wp, String label) throws Exception {
        byte[] pdf = ("%PDF-1.4\n% (synthetic) drawing " + label + " " + UUID.randomUUID() + "\n%%EOF\n")
                .getBytes(StandardCharsets.US_ASCII);
        JsonNode p = call("POST", "/api/v1/core/attachments:presign", map("fileName",
                label.replace(' ', '-') + ".pdf", "mimeType", "application/pdf", "sizeBytes", pdf.length,
                "sha256", sha(pdf), "ownerModule", "DEM", "ownerType", "Revision", "ownerId", UUID.randomUUID(),
                "chainScope", wp), ENG, "ENG");
        Path tmp = evidenceRoot.resolve("temp-upload").resolve(p.get("tempKey").asText());
        Files.createDirectories(tmp.getParent());
        Files.write(tmp, pdf);
        JsonNode stored = call("POST", "/api/v1/core/attachments/uploads/" + p.get("uploadId").asText() + ":complete",
                map(), ENG, "ENG");
        return UUID.fromString(stored.get("attachmentId").asText());
    }

    // ==================================================================== RPT · QAR

    private static final Map<String, String> WIDTH = Map.of("A", "0.05", "B", "0.15", "C", "0.25", "D", "0.50");

    private static String snapshotBody(UUID buildingId, String grade) {
        List<String> defects = new ArrayList<>();
        int n = 0;
        for (String floor : List.of("1F", "2F")) {
            for (int k = 1; k <= 2; k++) {
                n++;
                defects.add(("{\"id\":\"D-%04d\",\"buildingCode\":\"B01\",\"floorCode\":\"%s\",\"memberType\":\"COLUMN\","
                        + "\"memberId\":\"%s-COL-%02d\",\"defectType\":\"CRACK\",\"widthMm\":%s,\"lengthM\":1.20,"
                        + "\"polyline\":[[%d,2000],[%d,2600]]}").formatted(n, floor, floor, k, WIDTH.get(grade), 1000 * n,
                        1000 * n + 400));
            }
        }
        return """
                {"facilityName":"(합성) 점검 대상","buildings":[{"code":"B01","name":"본관","buildingId":"%s","grossAreaM2":12000.5}],
                 "memberGroups":[{"buildingCode":"B01","floorCode":"1F","memberType":"COLUMN","memberCount":10},
                                 {"buildingCode":"B01","floorCode":"2F","memberType":"COLUMN","memberCount":10}],
                 "defects":[%s]}""".formatted(buildingId, String.join(",", defects));
    }

    /** 보고서 생성 → 스냅샷 → … → 발행. @return {reportId, revisionId} */
    private UUID[] report(UUID project, UUID building, String type, String title, String grade)
            throws Exception {
        JsonNode rep = call("POST", "/api/v1/rpt/reports", map("projectId", project, "facilityId", building,
                "reportType", type, "title", title, "inspectionDate", today.minusDays(3).toString()), PM, "PM");
        UUID rev = UUID.fromString(rep.get("currentRevisionId").asText());
        call("POST", "/api/v1/rpt/reports/" + id(rep) + "/snapshots", s.tree(snapshotBody(building, grade)), PM, "PM");
        UUID review = composeToPublish(rev, null);
        return new UUID[] {id(rep), rev, review};
    }

    /** @param existingReview 정정본이면 기존 QAR 검토(REOPEN_FOR_CORRECTION 으로 새 라운드) @return 검토 id */
    private UUID composeToPublish(UUID rev, UUID existingReview) throws Exception {
        String g = call("POST", "/api/v1/rpt/revisions/" + rev + "/compose", map(), PM, "PM").get("computedGrade")
                .asText();
        s.call("PUT", "/api/v1/rpt/revisions/" + rev + "/grade/decision", map("finalGrade", g, "reason", "",
                "evidenceDefectIds", List.of()), PM, "PM", s.etagOf("/api/v1/rpt/revisions/" + rev, PM, "PM"));
        call("POST", "/api/v1/rpt/revisions/" + rev + "/render", map(), PM, "PM");
        call("POST", "/api/v1/rpt/revisions/" + rev + "/review-request", map(), PM, "PM");
        // QAR: 자체검토 → 제출 → AI(미설정 → 건너뜀) → QA 배정 · 체크리스트 · 통과 → 책임기술자 승인
        JsonNode review;
        if (existingReview == null) {
            review = call("POST", "/api/v1/qar/reviews", map("revisionId", rev), PM, "PM");
        } else {
            String p = "/api/v1/qar/reviews/" + existingReview + "/reopen-for-correction";
            review = s.call("POST", p, map(), PM, "PM", s.etagOf("/api/v1/qar/reviews/" + existingReview, PM, "PM"));
        }
        UUID reviewId = id(review);
        String reviewPath = "/api/v1/qar/reviews/" + reviewId;
        UUID round = UUID.fromString(review.get("round").get("id").asText());
        call("POST", reviewPath + "/autocheck-runs", map(), PM, "PM");
        answerChecklist(round, "SELF", PM, "PM");
        s.call("POST", reviewPath + "/submit", map(), PM, "PM", s.etagOf(reviewPath, PM, "PM"));
        for (int i = 0; i < 40; i++) {
            if ("QA_REVIEWING".equals(call("GET", reviewPath, null, PM, "PM").get("status").asText())) {
                break;
            }
            call("POST", "/api/v1/qar/ai-jobs:run", map(), QA, "QA");
            Thread.sleep(250);
        }
        // QA 는 자동 배정(검토자 프로필) — QAR-03b 자기 배정 금지라 수동 배정하지 않는다
        boolean assigned = false;
        for (JsonNode a : call("GET", reviewPath, null, PM, "PM").get("round").get("assignments")) {
            assigned |= "QA_REVIEW".equals(a.get("step").asText()) && QA.toString().equals(a.get("assigneeId").asText())
                    && a.get("completedAt").isNull();
        }
        if (!assigned) {
            throw new IllegalStateException("QA 자동 배정 안 됨: " + reviewPath);
        }
        answerChecklist(round, "QA", QA, "QA");
        s.call("POST", "/api/v1/qar/rounds/" + round + "/decision", map("decision", "PASS"), QA, "QA",
                s.etagOf(reviewPath, PM, "PM"));
        // QAR-01: 최종 승인자 ≠ 작성 · 제출 · 등급 결정자(최책임) → 두 번째 PM 문책임(PART)
        s.call("POST", reviewPath + "/approve", map(), PM2, "PM", s.etagOf(reviewPath, PM2, "PM"));
        waitRevision(rev, "APPROVED");
        call("POST", "/api/v1/rpt/revisions/" + rev + "/publish", map(), PM, "PM");
        return reviewId;
    }

    private void answerChecklist(UUID round, String stage, UUID actor, String role) throws Exception {
        JsonNode cl = call("GET", "/api/v1/qar/rounds/" + round + "/checklist", null, actor, role);
        List<String> missing = new ArrayList<>();
        cl.get("SELF".equals(stage) ? "missingSelf" : "missingQa").forEach(n -> missing.add(n.asText()));
        for (JsonNode item : cl.get("items")) {
            if (missing.contains(item.get("itemNo").asText())) {
                call("PUT", "/api/v1/qar/rounds/" + round + "/checklist/" + item.get("itemId").asText(),
                        map("stage", stage, "answer", "PASS", "note", "(합성) 확인"), actor, role);
            }
        }
    }

    private void waitRevision(UUID rev, String status) throws Exception {
        for (int i = 0; i < 120; i++) {
            if (status.equals(call("GET", "/api/v1/rpt/revisions/" + rev, null, PM, "PM").get("status").asText())) {
                return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("RPT 리비전 " + rev + " 상태 " + status + " 대기 시간 초과");
    }

    private void deliver(UUID rev) throws Exception {
        waitRevision(rev, "PUBLISHED");
        call("POST", "/api/v1/rpt/revisions/" + rev + "/delivery-confirmation",
                map("recipientName", "(합성) 고객 시설 담당자"), PM, "PM");
    }

    // ==================================================================== 공통

    private UUID building(Map<String, Object> body) throws Exception {
        return id(call("POST", "/api/v1/fac/buildings", body, PM, "PM"));
    }

    private void classify(UUID building, String cls) throws Exception {
        call("PUT", "/api/v1/fac/buildings/" + building + "/classification", map("sitbeopClass", cls,
                "bmaTarget", false, "evidence", map("note", "(합성) 연면적 · 층수 기준 분류 근거")), PM, "PM");
    }

    private UUID equipment(String type, String serial, String model, LocalDate calibratedOn, LocalDate nextDue)
            throws Exception {
        UUID eq = id(call("POST", "/api/v1/eqp/equipment", map("typeCode", type, "serialNo", serial, "model", model,
                "manufacturer", "(합성) 예시계측", "homeLocation", "(합성) 본사 장비실"), ADMIN, "ADMIN"));
        if (calibratedOn != null) {
            call("POST", "/api/v1/eqp/equipment/" + eq + "/calibrations", map("calibratedOn", calibratedOn.toString(),
                    "nextDueOn", nextDue.toString(), "result", "PASS", "certNo", "KC-" + serial,
                    "labName", "(합성) 교정기관", "labAccreditationNo", "KC-000"), ADMIN, "ADMIN");
        }
        return eq;
    }

    private static UUID node(JsonNode nodes, String code) {
        for (JsonNode n : nodes) {
            if (code.equals(n.get("code").asText())) {
                return UUID.fromString(n.get("id").asText());
            }
            if (n.has("children")) {
                try {
                    return node(n.get("children"), code);
                } catch (IllegalStateException ignored) {
                    // 다른 가지
                }
            }
        }
        throw new IllegalStateException("위치 노드 없음: " + code);
    }

    private JsonNode call(String method, String path, Object body, UUID actor, String roles) throws Exception {
        return s.call(method, path, body, actor, roles, null);
    }

    private JsonNode customer(String method, String path, Object body, UUID actor, UUID clientOrg) throws Exception {
        var r = s.send(method, path, body, actor, "CUSTOMER", null, Map.of("X-Client-Org-Id", clientOrg.toString()));
        return s.parse(method, path, r);
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
