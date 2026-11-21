package com.atomeng.erp.cst;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.erp.cst.domain.billing.BankStatementParser;
import com.atomeng.erp.cst.domain.billing.BankStatementParser.Direction;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 통장 명세 파서 — 합성 명세 3종(CP949 CSV · UTF-8 BOM 안내 3줄 · XLSX 금액+구분 열). 실제 은행 자료 아님 */
class BankStatementParserTest {

    static final String CP949_CSV = """
            거래일자,구분,거래내용,출금액,입금액,잔액
            2026.09.01,입금,합성건설 기성,0,"11,000,000","11,000,000"
            2026.09.03,출금,급여 이체,"3,000,000",0,"8,000,000"
            2026.09.10,입금,합성관리단,,"2,200,000","10,200,000"
            합계,,,"3,000,000","13,200,000",
            """;

    @Test
    @DisplayName("CP949 CSV: 머리글 1행 · 입금 2 · 출금 1 · 합계 줄 건너뜀 · 잔액 검증 일치 · 같은 파일은 같은 줄 키")
    void cp949Csv() {
        byte[] data = CP949_CSV.getBytes(Charset.forName("MS949"));
        var r = BankStatementParser.parse(data);
        assertThat(r.encoding()).isEqualTo("MS949");
        assertThat(r.kind()).isEqualTo("CSV");
        assertThat(r.headerRow()).isEqualTo(1);
        assertThat(r.lines()).hasSize(3);
        assertThat(r.inflowCount()).isEqualTo(2);
        assertThat(r.rowsSkipped()).isEqualTo(1);
        var first = r.lines().get(0);
        assertThat(first.direction()).isEqualTo(Direction.IN);
        assertThat(first.valueDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(first.amountKrw()).isEqualByComparingTo("11000000");
        assertThat(first.memo()).isEqualTo("합성건설 기성");
        assertThat(r.balanceMismatchRows()).isEmpty();
        assertThat(first.lineKey()).isEqualTo(BankStatementParser.parse(data).lines().get(0).lineKey());
        assertThat(first.lineKey()).matches("[0-9a-f]{16}:2");
    }

    @Test
    @DisplayName("UTF-8 BOM · 안내 3줄 뒤 머리글 · 탭 구분 · yy.mm.dd 날짜 · 잔액 불일치 줄 보고")
    void bomWithPreamble() {
        String text = "﻿계좌 별칭: SYN-운영\n조회기간 2026-09-01 ~ 2026-09-30\n(단위: 원)\n"
                + "거래일\t입금\t출금\t거래후잔액\t적요\n"
                + "26.09.02\t5,500,000\t\t5,500,000\t합성빌딩관리단\n"
                + "26.09.05\t\t500,000\t5,000,000\t사무실 임대료\n"
                + "26.09.09\t1,100,000\t\t6,200,000\t합성 엔지니어링\n";   // 5,000,000 + 1,100,000 = 6,100,000 ≠ 6,200,000
        var r = BankStatementParser.parse(text.getBytes(StandardCharsets.UTF_8));
        assertThat(r.encoding()).isEqualTo("UTF-8-BOM");
        assertThat(r.headerRow()).isEqualTo(4);
        assertThat(r.lines()).hasSize(3);
        assertThat(r.lines().get(0).valueDate()).isEqualTo(LocalDate.of(2026, 9, 2));
        assertThat(r.balanceMismatchRows()).containsExactly(7);
        assertThat(r.warnings()).anyMatch(w -> w.contains("잔액 검증 불일치 1줄"));
    }

    @Test
    @DisplayName("XLSX: 날짜 셀 · 금액 한 열 + 구분 열(입금/출금) · 숫자 셀의 선행 0 없는 정수")
    void xlsxAmountWithSign() throws Exception {
        byte[] data;
        try (var wb = new XSSFWorkbook(); var out = new ByteArrayOutputStream()) {
            var sh = wb.createSheet("명세");
            String[] head = {"거래일시", "거래구분", "거래금액", "잔액", "기재내용"};
            var h = sh.createRow(0);
            for (int i = 0; i < head.length; i++) { h.createCell(i).setCellValue(head[i]); }
            var style = wb.createCellStyle();
            style.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            Object[][] rows = {
                {LocalDate.of(2026, 9, 1), "입금", 3300000d, 3300000d, "합성건설㈜"},
                {LocalDate.of(2026, 9, 2), "출금", 200000d, 3100000d, "통신비"},
                {LocalDate.of(2026, 9, 3), "입금", 770000d, 3870000d, "합성 관리단"},
            };
            for (int i = 0; i < rows.length; i++) {
                var row = sh.createRow(i + 1);
                var c0 = row.createCell(0); c0.setCellValue((LocalDate) rows[i][0]); c0.setCellStyle(style);
                row.createCell(1).setCellValue((String) rows[i][1]);
                row.createCell(2).setCellValue((Double) rows[i][2]);
                row.createCell(3).setCellValue((Double) rows[i][3]);
                row.createCell(4).setCellValue((String) rows[i][4]);
            }
            wb.write(out);
            data = out.toByteArray();
        }
        var r = BankStatementParser.parse(data);
        assertThat(r.kind()).isEqualTo("XLSX");
        assertThat(r.lines()).hasSize(3);
        assertThat(r.lines().get(0).direction()).isEqualTo(Direction.IN);
        assertThat(r.lines().get(0).amountKrw()).isEqualByComparingTo("3300000");
        assertThat(r.lines().get(1).direction()).isEqualTo(Direction.OUT);
        assertThat(r.lines().get(1).amountKrw()).isEqualByComparingTo("200000");
        assertThat(r.lines().get(2).valueDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(r.balanceMismatchRows()).isEmpty();
    }

    @Test
    @DisplayName("머리글이 없거나 입출금 열이 없으면 FormatException")
    void rejectsUnknownLayout() {
        assertThatThrownBy(() -> BankStatementParser.parse("a,b,c\n1,2,3\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BankStatementParser.FormatException.class);
        assertThatThrownBy(() -> BankStatementParser.parse("거래일,거래내용,잔액\n2026-09-01,x,1\n".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(BankStatementParser.FormatException.class).hasMessageContaining("입금 · 출금");
        assertThatThrownBy(() -> BankStatementParser.parse(new byte[0])).isInstanceOf(BankStatementParser.FormatException.class);
    }
}
