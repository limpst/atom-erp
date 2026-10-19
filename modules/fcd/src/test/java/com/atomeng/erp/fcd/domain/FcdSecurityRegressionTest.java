package com.atomeng.erp.fcd.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.atomeng.erp.fcd.domain.ingest.FileParser;
import com.atomeng.erp.fcd.domain.ingest.FileParser.ParseResult;
import com.atomeng.erp.fcd.domain.ingest.RowSource;
import com.atomeng.erp.fcd.domain.rules.PyExpr;
import com.atomeng.erp.fcd.domain.rules.PyFormat;
import com.atomeng.erp.fcd.domain.rules.PyOps;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 보안 검토 2026-10-19 회귀 시험 (SEC-FCD-003·005·006·007·009·016). 모든 값은 합성. */
class FcdSecurityRegressionTest {

    @Test
    @DisplayName("SEC-FCD-005: 1 ** (2^63-1) · (-1) ** 홀수 · 0 ** 큰 수는 반복 없이 즉시 · 2 ** 64 는 기존처럼 넘침")
    void powDoesNotLoop() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(PyExpr.evaluate("1 ** 9223372036854775807", Map.of())).isEqualTo(1L);
            assertThat(PyExpr.evaluate("(-1) ** 9223372036854775807", Map.of())).isEqualTo(-1L);
            assertThat(PyExpr.evaluate("(-1) ** 9223372036854775806", Map.of())).isEqualTo(1L);
            assertThat(PyExpr.evaluate("0 ** 9223372036854775807", Map.of())).isEqualTo(0L);
            assertThat(PyExpr.evaluate("0 ** 0", Map.of())).isEqualTo(1L);
            assertThat(PyExpr.evaluate("2 ** 10", Map.of())).isEqualTo(1024L);
            assertThatThrownBy(() -> PyExpr.evaluate("2 ** 9223372036854775807", Map.of()))
                    .isInstanceOf(ArithmeticException.class);
        });
    }

    @Test
    @DisplayName("SEC-FCD-006: round(0.5, 999999999) 는 CPython 처럼 계산 없이 0.5 · 큰 음수 자릿수는 0 · 서식 폭 1000 초과 거부")
    void roundAndFormatBounds() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(PyExpr.evaluate("round(0.5, 999999999)", Map.of())).isEqualTo(0.5);
            assertThat(PyExpr.evaluate("round(1234.5, -999999999)", Map.of())).isEqualTo(0.0);
            assertThat(PyExpr.evaluate("round(1234, -999999999)", Map.of())).isEqualTo(0L);
            assertThat(PyExpr.evaluate("round(1250, -2)", Map.of())).isEqualTo(1200L);   // 짝수 반올림 유지
            assertThat(PyExpr.evaluate("round(2.675, 2)", Map.of())).isEqualTo(2.67);    // CPython 과 같음
        });
        assertThat(PyFormat.format(12L, ">5")).isEqualTo("   12");
        assertThatThrownBy(() -> PyFormat.format(12L, ">999999999")).isInstanceOf(PyOps.PyValueError.class);
        assertThatThrownBy(() -> PyFormat.format(1.5, ".99999")).isInstanceOf(PyOps.PyValueError.class);
    }

    @Test
    @DisplayName("SEC-FCD-003: 괄호 10,000 단 중첩 · 4,000 자 초과 식은 StackOverflowError 대신 ExprError")
    void deepNestingRejected() {
        String deep = "(".repeat(10_000) + "1" + ")".repeat(10_000);
        assertThatThrownBy(() -> PyExpr.compile(deep)).isInstanceOf(PyExpr.ExprError.class);
        String deepNot = "not ".repeat(1000) + "True";
        assertThatThrownBy(() -> PyExpr.compile(deepNot)).isInstanceOf(PyExpr.ExprError.class);
        String deepNeg = "-".repeat(3000) + "1";
        assertThatThrownBy(() -> PyExpr.compile(deepNeg)).isInstanceOf(PyExpr.ExprError.class);
        // 평범한 중첩(20 단)은 그대로 동작
        assertThat(PyExpr.evaluate("(".repeat(20) + "1 + 2" + ")".repeat(20), Map.of())).isEqualTo(3L);
    }

    @Test
    @DisplayName("SEC-FCD-009: errors.csv 셀이 = + - @ 탭 CR 로 시작하면 ' 를 붙여 Excel 수식 실행 차단")
    void csvFormulaInjectionNeutralized() {
        byte[] csv = FileParser.errorsCsv(List.of(
                new FileParser.RowError(2, "merchant_name", "E-X", "=HYPERLINK(\"http://evil\",\"x\")", "m"),
                new FileParser.RowError(3, "amount", "E-X", "+1+1", "@SUM(A1)"),
                new FileParser.RowError(4, "amount", "E-X", "-2", "\tTAB"),
                new FileParser.RowError(5, "amount", "E-X", "12,000", "정상")));
        String s = new String(csv, StandardCharsets.UTF_8);
        assertThat(s).contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\"");
        assertThat(s).contains(",'+1+1,'@SUM(A1)");
        assertThat(s).contains(",'-2,'\tTAB");
        assertThat(s).contains(",\"12,000\",정상");
        assertThat(s).doesNotContain(",=HYPERLINK");
    }

    @Test
    @DisplayName("SEC-FCD-016: 가맹점명 자유 텍스트 안 PAN(룬 검사 통과 16자리)은 저장 전 마스킹")
    void merchantNamePanMasked() {
        String csv = "이용일자,이용시간,카드번호,승인번호,가맹점명,사업자번호,업종,이용금액,통화,현지금액,비고\n"
                + "2026.09.03,10:00,999999******0001,44444444,(예시)D 4111111111111111,,한식,\"12,000\",KRW,,\n";
        ParseResult r = FileParser.parse(csv.getBytes(StandardCharsets.UTF_8), IngestTest.ONE_CARD,
                IngestTest.profiles(), null);
        assertThat(r.rows()).hasSize(1);
        assertThat(r.rows().get(0).merchantName()).doesNotContain("4111111111111111").contains("411111******1111");
    }

    @Test
    @DisplayName("SEC-FCD-007: CSV 행 상한(200,000) 초과 → FormatException(E-TOO-MANY-ROWS) · 상한 이하 정상")
    void rowCap() {
        StringBuilder sb = new StringBuilder(RowSource.MAX_ROWS * 4 + 16);
        for (int i = 0; i <= RowSource.MAX_ROWS; i++) {
            sb.append("a,b\n");
        }
        byte[] tooMany = sb.toString().getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> RowSource.read(tooMany)).isInstanceOf(RowSource.FormatException.class)
                .hasMessageContaining("행 수");
        assertThat(RowSource.read("a,b\n1,2\n".getBytes(StandardCharsets.UTF_8)).rows()).hasSize(2);
        String wide = "x,".repeat(RowSource.MAX_COLS + 1) + "x\n";
        assertThatThrownBy(() -> RowSource.read(wide.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RowSource.FormatException.class);
    }
}
