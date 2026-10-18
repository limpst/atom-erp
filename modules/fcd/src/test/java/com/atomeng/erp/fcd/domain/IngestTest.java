package com.atomeng.erp.fcd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.fcd.domain.ingest.CanonicalRow;
import com.atomeng.erp.fcd.domain.ingest.FileParser;
import com.atomeng.erp.fcd.domain.ingest.FileParser.ParseResult;
import com.atomeng.erp.fcd.domain.ingest.HeaderDetector.MappingProfile;
import com.atomeng.erp.fcd.domain.ingest.MerchantText;
import com.atomeng.erp.fcd.domain.ingest.PanTools;
import com.atomeng.erp.fcd.domain.ingest.RowSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 파일 수집(설계 B7.1 · 12-IMPL Step 2) — TC-FCD-001 · 002 · 003 · 004 · 005 · 006 · 007(파싱) · 015 · 030 +
 * 프로토타입 parse_file 과의 수집 패리티(카드사 양식 3종). 모든 파일 · 카드번호는 합성(가상 BIN 999999).
 */
class IngestTest {

    static final Charset CP949 = Charset.forName("x-windows-949");
    static final UUID CARD_A = UUID.nameUUIDFromBytes("card-a".getBytes(StandardCharsets.UTF_8));

    /** 기본 프로파일 3종(V12_0_1 시드와 같은 값) */
    static List<MappingProfile> profiles() {
        return List.of(
                new MappingProfile("P-ACARD-APPROVAL-CSV-1", "ACARD", "CSV", "APPROVAL", "(가상)에이카드",
                        List.of("이용일자", "이용시간", "카드번호", "승인번호", "가맹점명", "사업자번호", "업종", "이용금액", "통화", "현지금액", "비고"),
                        Map.ofEntries(Map.entry("approved_date", "이용일자"), Map.entry("approved_time", "이용시간"),
                                Map.entry("card_ref", "카드번호"), Map.entry("approval_no", "승인번호"),
                                Map.entry("merchant_name", "가맹점명"), Map.entry("biz_reg_no", "사업자번호"),
                                Map.entry("category", "업종"), Map.entry("amount", "이용금액"), Map.entry("currency", "통화"),
                                Map.entry("amount_foreign", "현지금액"), Map.entry("note", "비고")),
                        "%Y.%m.%d", "NEGATIVE_AMOUNT", List.of("취소", "승인취소", "매입취소"), List.of("부분취소"), "합계|소계|총계", true),
                new MappingProfile("P-BCARD-USAGE-CSV-1", "BCARD", "CSV", "APPROVAL", "(가상)비카드",
                        List.of("거래일시", "카드번호", "카드별칭", "승인번호", "이용가맹점", "가맹점사업자번호", "MCC", "승인금액", "승인구분", "통화",
                                "현지금액", "원화환산금액", "적용환율", "메모"),
                        Map.ofEntries(Map.entry("approved_date", "거래일시"), Map.entry("card_ref", "카드번호"),
                                Map.entry("card_alias", "카드별칭"), Map.entry("approval_no", "승인번호"),
                                Map.entry("merchant_name", "이용가맹점"), Map.entry("biz_reg_no", "가맹점사업자번호"),
                                Map.entry("category", "MCC"), Map.entry("amount", "승인금액"), Map.entry("status", "승인구분"),
                                Map.entry("currency", "통화"), Map.entry("amount_foreign", "현지금액"),
                                Map.entry("amount_krw_fx", "원화환산금액"), Map.entry("fx_rate", "적용환율"), Map.entry("note", "메모")),
                        "%Y-%m-%d", "STATUS_COLUMN", List.of("승인취소", "취소"), List.of("부분취소"), "합계|소계|총계", true),
                new MappingProfile("P-CCARD-PURCHASE-XLSX-1", "CCARD", "XLSX", "PURCHASE", "(가상)씨카드",
                        List.of("이용일", "카드번호", "승인번호", "가맹점명", "이용금액_원화", "이용금액_현지", "통화", "매입일", "취소여부"),
                        Map.ofEntries(Map.entry("approved_date", "이용일"), Map.entry("card_ref", "카드번호"),
                                Map.entry("approval_no", "승인번호"), Map.entry("merchant_name", "가맹점명"),
                                Map.entry("amount", "이용금액_원화"), Map.entry("amount_foreign", "이용금액_현지"),
                                Map.entry("currency", "통화"), Map.entry("purchased_on", "매입일"), Map.entry("status", "취소여부")),
                        null, "SEPARATE_ROW_FLAG", List.of("Y"), List.of("부분취소"), "합계|소계|총계", true));
    }

    /** 합성 카드 1장(999999******0001) 만 아는 해석기 */
    static final FileParser.CardResolver ONE_CARD = (ref, company, alias) -> {
        if (ref.replace(" ", "").matches("999999\\*{6}0001")) {
            return CARD_A;
        }
        throw new FileParser.CardLookupException("E-CARD-UNKNOWN", "등록되지 않은 카드");
    };

    static byte[] resource(String path) {
        try (InputStream in = Objects.requireNonNull(IngestTest.class.getResourceAsStream(path), path)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("TC-FCD-001·030: PAN 토큰(HMAC) · Luhn · 마스킹 · 스캐너 — 소수(환율)는 PAN 으로 보지 않음, 호출자 버퍼 소거")
    void panProtection() {
        String pan = PanTools.luhnComplete("999999123456789");
        char[] buf = pan.toCharArray();
        var t = PanTools.tokenize(buf, "dev-only-not-a-secret-v1".getBytes(StandardCharsets.UTF_8), 1);
        assertThat(t.token()).hasSize(64).doesNotContain(pan);
        assertThat(t.masked()).isEqualTo("999999******" + pan.substring(12));
        assertThat(new String(buf)).isEqualTo("\0".repeat(16));
        assertThat(PanTools.maskPans("카드 " + pan + " 환율 1385.123456789012345")).doesNotContain(pan)
                .contains("1385.123456789012345");
        assertThat(PanTools.scanPans("ok 999999******0001 / " + pan)).containsExactly("999999******" + pan.substring(12));
        assertThatThrownBy(() -> PanTools.tokenize("4111".toCharArray(), new byte[] {1}, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MerchantText.validBizNo("123-45-67891")).isTrue();
        assertThat(MerchantText.validBizNo("123-45-67890")).isFalse();
        assertThat(MerchantText.normalize("(주)(예시)한강설렁탕 하남점", true)).isEqualTo("한강설렁탕");
    }

    @Test
    @DisplayName("TC-FCD-002: CP949 · 쉼표 · 헤더 3행째 · 합계 행 → 인코딩 CP949, 헤더 행 2(0기준), 합계 이후 중단")
    void cp949WithTitleRowsAndFooter() {
        StringBuilder sb = new StringBuilder("(예시)카드사A 법인카드 이용내역\r\n조회기간,2026.09.01~2026.09.30\r\n");
        sb.append("이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고\r\n");
        for (int i = 0; i < 25; i++) {
            sb.append("2026.09.%02d,12:%02d,999999******0001,%08d,(예시)한강설렁탕 하남점,999-81-00001,한식,\"12,000\",KRW,,\r\n"
                    .formatted(1 + i % 28, i, 10_000_000 + i));
        }
        sb.append("합계,,,,,,,\"300,000\",,,\r\n");
        ParseResult r = FileParser.parse(sb.toString().getBytes(CP949), ONE_CARD, profiles(), null);
        assertThat(r.encoding()).isEqualTo("CP949");
        assertThat(r.headerRow()).isEqualTo(2);
        assertThat(r.profile().profileCode()).isEqualTo("P-ACARD-APPROVAL-CSV-1");
        assertThat(r.rows()).hasSize(25);
        assertThat(r.errors()).isEmpty();
        assertThat(r.rows().get(0).amountKrw()).isEqualTo(12_000);
        assertThat(r.rows().get(0).precision()).isEqualTo("MINUTE");
    }

    @Test
    @DisplayName("TC-FCD-003: UTF-8 BOM · 탭 구분 · 따옴표 안 줄바꿈 → BOM 제거, 탭 감지, 줄바꿈 필드 1개 셀")
    void utf8BomTabQuotedNewline() {
        String body = "﻿거래일시\t카드번호\t카드별칭\t승인번호\t이용가맹점\t가맹점사업자번호\tMCC\t승인금액\t승인구분\t통화\t현지금액\t원화환산금액\t적용환율\t메모\r\n"
                + "2026-09-01 00:01\t999999******0001\t영업팀-0001\t23983605\t(예시)시티택시 송파점\t999-88-43691\t4121\t27,500\t승인\tKRW\t\t\t\t\"현장 이동\n(2건 중 1)\"\r\n"
                + "2026-09-01 09:10\t999999******0001\t영업팀-0001\t23983606\t(예시)시티택시 송파점\t999-88-43691\t4121\t27,500\t승인취소\tKRW\t\t\t\t\r\n";
        ParseResult r = FileParser.parse(body.getBytes(StandardCharsets.UTF_8), ONE_CARD, profiles(), null);
        assertThat(r.encoding()).isEqualTo("UTF-8-BOM");
        assertThat(r.delimiter()).isEqualTo("\t");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).raw().get("메모")).isEqualTo("현장 이동\n(2건 중 1)");
        // TC-FCD-007(상태 컬럼): '승인취소' → CANCEL 행
        assertThat(r.rows().get(1).kind()).isEqualTo("CANCEL");
        assertThat(r.rows().get(1).cancelAmount()).isEqualTo(27_500L);
    }

    static byte[] twoLevelHeaderWorkbook(Workbook wb) throws IOException {
        Sheet ws = wb.createSheet("매입내역");
        Row h1 = ws.createRow(0);
        String[] top = {"이용일", "카드번호", "승인번호", "가맹점명", "이용금액", null, "통화", "매입일", "취소여부"};
        for (int i = 0; i < top.length; i++) {
            if (top[i] != null) {
                h1.createCell(i).setCellValue(top[i]);
            }
        }
        Row h2 = ws.createRow(1);
        h2.createCell(4).setCellValue("원화");
        h2.createCell(5).setCellValue("현지");
        ws.addMergedRegion(new CellRangeAddress(0, 0, 4, 5));
        CellStyle date = wb.createCellStyle();
        date.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
        CellStyle zeros = wb.createCellStyle();
        zeros.setDataFormat(wb.createDataFormat().getFormat("00000000"));
        Object[][] rows = {
                {LocalDateTime.of(2026, 9, 1, 0, 0), "999999******0001", 123456d, "(예시)비즈호텔", 132000d, "KRW", "N"},
                {LocalDateTime.of(2026, 9, 2, 0, 0), "999999******0001", 99631548d, "(예시)현장주유소", 88000d, "KRW", "N"},
                {LocalDateTime.of(2026, 9, 3, 0, 0), "999999******0001", 99631548d, "(예시)현장주유소", 88000d, "KRW", "Y"}};
        for (int r = 0; r < rows.length; r++) {
            Row x = ws.createRow(2 + r);
            var c0 = x.createCell(0);
            c0.setCellValue((LocalDateTime) rows[r][0]);
            c0.setCellStyle(date);
            x.createCell(1).setCellValue((String) rows[r][1]);
            var c2 = x.createCell(2);
            c2.setCellValue((Double) rows[r][2]);
            c2.setCellStyle(zeros);
            x.createCell(3).setCellValue((String) rows[r][3]);
            x.createCell(4).setCellValue((Double) rows[r][4]);
            x.createCell(6).setCellValue((String) rows[r][5]);
            var c7 = x.createCell(7);
            c7.setCellValue(((LocalDateTime) rows[r][0]).plusDays(2));
            c7.setCellStyle(date);
            x.createCell(8).setCellValue((String) rows[r][6]);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        wb.close();
        return out.toByteArray();
    }

    @Test
    @DisplayName("TC-FCD-004: XLSX 2단 병합 헤더 · Excel 날짜 셀 · 승인번호 숫자 셀 선행 0 → '00123456' 보존 (SAX 스트리밍) / "
            + "TC-FCD-007(별도 취소행 Y)")
    void xlsxTwoLevelHeader() throws IOException {
        ParseResult r = FileParser.parse(twoLevelHeaderWorkbook(new XSSFWorkbook()), ONE_CARD, profiles(), null);
        assertThat(r.fileKind()).isEqualTo("XLSX");
        assertThat(r.headerRows()).isEqualTo(2);
        assertThat(r.profile().profileCode()).isEqualTo("P-CCARD-PURCHASE-XLSX-1");
        assertThat(r.rows()).hasSize(3);
        CanonicalRow first = r.rows().get(0);
        assertThat(first.approvalNo()).isEqualTo("00123456");
        assertThat(first.approvedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
        assertThat(first.precision()).isEqualTo("DATE");
        assertThat(first.amountKrw()).isEqualTo(132_000);
        assertThat(first.kind()).isEqualTo("PURCHASE");
        assertThat(r.rows().get(2).kind()).isEqualTo("CANCEL");
        assertThat(r.rows().get(2).cancelAt()).isEqualTo(LocalDateTime.of(2026, 9, 5, 0, 0));
    }

    @Test
    @DisplayName("TC-FCD-005: 레거시 XLS(BIFF8) → POI HSSF 로 XLSX 와 같은 표준 레코드")
    void legacyXls() throws IOException {
        ParseResult xls = FileParser.parse(twoLevelHeaderWorkbook(new HSSFWorkbook()), ONE_CARD, profiles(), null);
        ParseResult xlsx = FileParser.parse(twoLevelHeaderWorkbook(new XSSFWorkbook()), ONE_CARD, profiles(), null);
        assertThat(xls.fileKind()).isEqualTo("XLS");
        assertThat(xls.rows()).hasSize(3);
        for (int i = 0; i < 3; i++) {
            CanonicalRow a = xls.rows().get(i);
            CanonicalRow b = xlsx.rows().get(i);
            assertThat(List.of(a.kind(), a.approvalNo(), a.approvedAt(), a.amountKrw(), a.merchantName()))
                    .isEqualTo(List.of(b.kind(), b.approvalNo(), b.approvedAt(), b.amountKrw(), b.merchantName()));
        }
    }

    @Test
    @DisplayName("TC-FCD-006: HTML 을 .xls 로 저장한 파일 → W-HTML-XLS 경고, HTML 표 파서 경로, 같은 결과 / "
            + "TC-FCD-007(음수 금액 취소 · 부분취소 비고)")
    void htmlAsXls() {
        String html = "<html><body><table>"
                + "<tr><th>이용일자</th><th>이용시간</th><th>카드번호</th><th>승인번호</th><th>가맹점명</th><th>사업자번호</th>"
                + "<th>업종</th><th>이용금액</th><th>통화</th><th>현지금액</th><th>비고</th></tr>"
                + "<tr><td>2026.09.01</td><td>12:05</td><td>999999******0001</td><td>00001234</td><td>(예시)르방도주점</td>"
                + "<td>999-81-00005</td><td>일반주점</td><td>88,000</td><td>KRW</td><td></td><td></td></tr>"
                + "<tr><td>2026.09.02</td><td>09:00</td><td>999999******0001</td><td>00001234</td><td>(예시)르방도주점</td>"
                + "<td>999-81-00005</td><td>일반주점</td><td>-8,000</td><td>KRW</td><td></td><td>부분취소</td></tr>"
                + "</table></body></html>";
        ParseResult r = FileParser.parse(html.getBytes(StandardCharsets.UTF_8), ONE_CARD, profiles(), null);
        assertThat(r.fileKind()).isEqualTo("HTML_AS_XLS");
        assertThat(r.warnings()).contains("W-HTML-XLS");
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).approvalNo()).isEqualTo("00001234");
        assertThat(r.rows().get(1).kind()).isEqualTo("CANCEL");
        assertThat(r.rows().get(1).cancelAmount()).isEqualTo(8_000L);
        assertThat(r.rows().get(1).partial()).isTrue();
    }

    @Test
    @DisplayName("TC-FCD-015: 행 오류 혼재(금액 '1,2a0' · 날짜 '2026-13-01' · 미등록 카드) → 오류 3행 리포트(BOM CSV), 나머지 정상")
    void rowErrorsReport() {
        String csv = "이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고\n"
                + "2026.09.01,10:00,999999******0001,11111111,(예시)A,,한식,\"1,2a0\",KRW,,\n"
                + "2026.13.01,10:00,999999******0001,22222222,(예시)B,,한식,\"12,000\",KRW,,\n"
                + "2026.09.02,10:00,999999******9999,33333333,(예시)C,,한식,\"12,000\",KRW,,\n"
                + "2026.09.03,10:00,999999******0001,44444444,(예시)D,,한식,\"12,000\",KRW,,\n";
        ParseResult r = FileParser.parse(csv.getBytes(StandardCharsets.UTF_8), ONE_CARD, profiles(), null);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rowsError()).isEqualTo(3);
        assertThat(r.errors()).extracting(FileParser.RowError::code)
                .containsExactlyInAnyOrder("E-AMOUNT", "E-DATE", "E-CARD-UNKNOWN");
        byte[] report = FileParser.errorsCsv(r.errors());
        assertThat(report[0] & 0xff).isEqualTo(0xef);   // UTF-8 BOM — Excel 에서 바로 열림
        assertThat(new String(report, StandardCharsets.UTF_8)).contains("행번호,컬럼,오류코드,원문값,메시지");
    }

    @Test
    @DisplayName("헤더를 못 찾으면 매핑 화면(needsMapping) · 인코딩 판별 실패는 E-ENCODING")
    void needsMappingAndEncoding() {
        ParseResult r = FileParser.parse("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8), ONE_CARD, profiles(), null);
        assertThat(r.needsMapping()).isTrue();
        assertThatThrownBy(() -> RowSource.detectEncoding(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff}))
                .isInstanceOf(RowSource.FormatException.class);
    }

    @Test
    @DisplayName("수집 패리티: 프로토타입 합성 명세서 3종(CP949 CSV · BOM 탭 CSV · 2단 헤더 XLSX, 3,152행) → Java 표준 레코드 = Python parse_file")
    @SuppressWarnings("unchecked")
    void ingestParityWithPrototype() {
        Map<String, Object> fx = Fixtures.json("/fcd/parity/fcd-parity-ingest.json");
        Map<String, List<UUID>> byMask = new HashMap<>();
        Map<String, UUID> byAlias = new HashMap<>();
        Map<UUID, String> back = new HashMap<>();
        for (Object o : (List<Object>) fx.get("cards")) {
            Map<String, Object> c = (Map<String, Object>) o;
            UUID id = UUID.nameUUIDFromBytes(((String) c.get("card_id")).getBytes(StandardCharsets.UTF_8));
            back.put(id, (String) c.get("card_id"));
            byMask.computeIfAbsent(c.get("card_company") + "|" + c.get("first6") + "|" + c.get("last4"), k -> new java.util.ArrayList<>()).add(id);
            if (c.get("alias") != null) {
                byAlias.put((String) c.get("alias"), id);
            }
        }
        FileParser.CardResolver resolver = (ref, company, alias) -> {
            var m = java.util.regex.Pattern.compile("^\\s*(\\d{4})[- ]?(\\d{2})[*xX\\- ]+.*?(\\d{4})\\s*$").matcher(ref);
            if (!m.matches()) {
                throw new FileParser.CardLookupException("E-CARD-FORMAT", "형식");
            }
            String key = company + "|" + m.group(1) + m.group(2) + "|" + m.group(3);
            List<UUID> ids = byMask.getOrDefault(key, List.of());
            if (ids.size() == 1) {
                return ids.get(0);
            }
            if (ids.size() > 1 && alias != null && byAlias.containsKey(alias)) {
                return byAlias.get(alias);
            }
            throw new FileParser.CardLookupException(ids.size() > 1 ? "E-CARD-AMBIGUOUS" : "E-CARD-UNKNOWN", "카드");
        };
        int total = 0;
        for (Object o : (List<Object>) fx.get("files")) {
            Map<String, Object> f = (Map<String, Object>) o;
            String name = (String) f.get("file");
            Map<String, Object> s = (Map<String, Object>) f.get("summary");
            ParseResult r = FileParser.parse(resource("/fcd/samples/" + name), resolver, profiles(), null);
            assertThat(r.profile().profileCode()).as(name).isEqualTo(s.get("profile"));
            assertThat(r.headerRow().longValue()).as(name).isEqualTo(s.get("header_row"));
            assertThat((long) r.headerRows()).as(name).isEqualTo(s.get("header_rows"));
            assertThat((long) r.rowsTotal()).as(name).isEqualTo(s.get("rows_total"));
            assertThat((long) r.rowsError()).as(name).isEqualTo(s.get("rows_error"));
            assertThat(r.delimiter()).as(name).isEqualTo(s.get("delimiter"));
            assertThat(r.warnings()).as(name).isEqualTo(s.get("warnings"));
            List<Object> exp = (List<Object>) f.get("rows");
            assertThat(r.rows()).as(name).hasSize(exp.size());
            for (int i = 0; i < exp.size(); i++) {
                Map<String, Object> e = (Map<String, Object>) exp.get(i);
                CanonicalRow a = r.rows().get(i);
                String at = name + " row " + e.get("row_no");
                assertThat(back.get(a.cardId())).as(at).isEqualTo(e.get("card_id"));
                assertThat(a.kind()).as(at).isEqualTo(e.get("kind"));
                assertThat(a.channel()).as(at).isEqualTo(e.get("channel"));
                assertThat(a.approvalNo()).as(at).isEqualTo(e.get("approval_no"));
                assertThat(a.approvedAt().toString()).as(at).isEqualTo(
                        LocalDateTime.parse((String) e.get("approved_at")).toString());
                assertThat(a.precision()).as(at).isEqualTo(e.get("precision"));
                assertThat(a.amountKrw()).as(at).isEqualTo(e.get("amount_krw"));
                assertThat(a.merchantName()).as(at).isEqualTo(e.get("merchant_name"));
                assertThat(a.bizRegNo()).as(at).isEqualTo(e.get("biz_reg_no"));
                assertThat(a.categoryHint()).as(at).isEqualTo(e.get("category_hint"));
                assertThat(a.currency()).as(at).isEqualTo(e.get("currency"));
                assertThat(a.cancelAmount()).as(at).isEqualTo(e.get("cancel_amount"));
                assertThat(a.partial()).as(at).isEqualTo(e.get("partial"));
                assertThat(a.cancelAt() == null ? null : a.cancelAt().toString()).as(at).isEqualTo(
                        e.get("cancel_at") == null ? null : LocalDateTime.parse((String) e.get("cancel_at")).toString());
                assertThat((long) a.rowNo()).as(at).isEqualTo(e.get("row_no"));
            }
            total += exp.size();
        }
        assertThat(total).isGreaterThan(3000);
    }
}
