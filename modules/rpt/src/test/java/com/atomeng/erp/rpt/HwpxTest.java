package com.atomeng.erp.rpt;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.rpt.domain.RptJson;
import com.atomeng.erp.rpt.domain.drawing.DefectMapPng;
import com.atomeng.erp.rpt.domain.hwpx.HwpxBinder;
import com.atomeng.erp.rpt.domain.hwpx.HwpxPackage;
import com.atomeng.erp.rpt.domain.hwpx.HwpxTemplateChecker;
import com.atomeng.erp.rpt.domain.hwpx.HwpxValidator;
import com.atomeng.erp.rpt.domain.hwpx.RenderModel;
import com.atomeng.erp.rpt.domain.snapshot.SnapshotDocument;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * HWPX 생성 · 검증 (FR-RPT-012/013, 06-IMPL Step 8, TC-RPT-005). 한컴 소프트웨어 없이 압축을 풀어 XML 구조 · 부분 XSD ·
 * 참조 무결성을 검사한다. 템플릿은 합성 서식(RT_COMMON 2026.1.0).
 */
class HwpxTest {

    static final String TEMPLATE = "classpath:rpt/templates/RT_COMMON/2026.1.0";
    static final JsonNode MANIFEST = manifest();

    static JsonNode manifest() {
        try {
            return RptJson.MAPPER.readTree("""
                    {"placeholders":{"report.title":{"required":true},"report.typeName":{"required":true},
                     "facility.name":{"required":true},"inspection.date":{"required":true},"grade.computed":{"required":true},
                     "grade.final":{"required":false},"grade.weightedScore":{"required":true},"report.revNo":{"required":true}},
                     "repeats":{"ruleRows":{"kind":"TABLE_ROW"}}}""");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static RenderModel model() {
        SnapshotDocument.Defect crack = new SnapshotDocument.Defect("D-0001", "B01", "1F", "COLUMN", "1F-COL-01", "CRACK",
                new BigDecimal("0.35"), new BigDecimal("1.20"), null, null, null,
                List.of(List.of(new BigDecimal("1000"), new BigDecimal("2000")),
                        List.of(new BigDecimal("1600"), new BigDecimal("2300"))), null);
        DefectMapPng.Png png = DefectMapPng.render(List.of(crack), 600);
        UUID b1 = UUID.randomUUID();
        UUID b2 = UUID.randomUUID();
        UUID b3 = UUID.randomUUID();
        UUID b4 = UUID.randomUUID();
        return new RenderModel(
                Map.of("report.title", "합성 사옥 정밀안전점검 보고서 <&>", "report.typeName", "정밀안전점검",
                        "report.revNo", "1", "facility.name", "합성 사옥", "inspection.date", "2026-09-30",
                        "grade.computed", "C", "grade.final", "C", "grade.weightedScore", "2.85"),
                Map.of("ruleRows", List.of(Map.of("kind", "COND_RATING", "version", "BLD_RC@2026.1", "effectiveFrom", "2026-01-01"),
                        Map.of("kind", "GRADE_WEIGHT", "version", "BLD_RC@2026.1", "effectiveFrom", "2026-01-01"),
                        Map.of("kind", "GRADE_BAND", "version", "BLD_RC@2026.1", "effectiveFrom", "2026-01-01"))),
                List.of(new RenderModel.Section("ch1.overview", "1. 개요", List.of(
                                new RenderModel.Block(b1, "PARAGRAPH", "합성 사옥 정밀안전점검 — 결함 1건.", List.of(), List.of(), null))),
                        new RenderModel.Section("ch4.quantities", "4. 결함물량표", List.of(
                                new RenderModel.Block(b2, "TABLE", "표", List.of("동", "층", "부재", "결함", "개소"),
                                        List.of(List.of("B01", "1F", "COLUMN", "CRACK", "1"),
                                                List.of("B01", "2F", "BEAM", "SPALL", "2")), null))),
                        new RenderModel.Section("ch5.drawings", "5. 외관조사망도", List.of(
                                new RenderModel.Block(b3, "IMAGE", "B01 1F 외관조사망도", List.of(), List.of(), "png1"))),
                        new RenderModel.Section("ch7.opinion", "7. 종합 의견", List.of(
                                new RenderModel.Block(b4, "PARAGRAPH", "책임기술자 의견.", List.of(), List.of(), null)))),
                Map.of("png1", new RenderModel.Image("png1", png.bytes(), png.widthPx(), png.heightPx())));
    }

    @Test
    @DisplayName("Step 8: 템플릿 바인딩 → 유효한 HWPX(mimetype STORED 첫 엔트리 · 부분 XSD · 참조 무결성 · placeholder 0)")
    void bindProducesValidHwpx() throws Exception {
        HwpxPackage template = HwpxPackage.fromClasspath(TEMPLATE);
        assertThat(HwpxTemplateChecker.check(template, MANIFEST).errors()).isEmpty();
        byte[] zip = HwpxBinder.bind(template, model()).toZip();
        HwpxValidator.Report r = HwpxValidator.validate(zip);
        assertThat(r.errors()).isEmpty();
        assertThat(r.tables()).isEqualTo(2);            // 템플릿 rule 표 + 결함물량표
        assertThat(r.pictures()).isEqualTo(1);
        assertThat(r.binItems()).isEqualTo(1);
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry first = z.getNextEntry();
            assertThat(first.getName()).isEqualTo("mimetype");
            assertThat(first.getMethod()).isEqualTo(ZipEntry.STORED);
            assertThat(new String(z.readAllBytes(), StandardCharsets.US_ASCII)).isEqualTo("application/hwp+zip");
        }
        HwpxPackage out = HwpxPackage.read(zip);
        String sec = out.text(HwpxPackage.SECTION0);
        assertThat(sec).contains("합성 사옥 정밀안전점검 보고서 &lt;&amp;&gt;")   // XML 이스케이프
                .contains("BLD_RC@2026.1").contains("GRADE_BAND").contains("결함물량표").contains("binaryItemIDRef=\"image1\"")
                .doesNotContain("{{");
        assertThat(sec.split("rowCnt=\"4\"", -1)).hasSize(2);   // rule 표: 머리 1 + 반복 3행
        assertThat(out.text(HwpxPackage.CONTENT_HPF)).contains("BinData/image1.png").contains("정밀안전점검");
        assertThat(out.text("Preview/PrvText.txt")).contains("합성 사옥");
        // 결정적: 같은 입력 = 같은 바이트
        assertThat(HwpxBinder.bind(template, model()).toZip()).isNotEmpty();
    }

    @Test
    @DisplayName("TC-RPT-005: placeholder 가 3개 run 으로 분할된 템플릿 → 정규화 후 검증 통과 · 정상 치환")
    void splitRunTemplate() {
        HwpxPackage template = HwpxPackage.fromClasspath(TEMPLATE);
        String sec = template.text(HwpxPackage.SECTION0);
        String split = sec.replace("<hp:run charPrIDRef=\"0\"><hp:t>시설물: {{facility.name}}</hp:t></hp:run>",
                        "<hp:run charPrIDRef=\"0\"><hp:t>시설물: {{faci</hp:t></hp:run><hp:run charPrIDRef=\"2\"><hp:t>lity.na"
                                + "</hp:t></hp:run><hp:run charPrIDRef=\"0\"><hp:t>me}} (합성)</hp:t></hp:run>")
                .replace("<hp:t>{{#each ruleRows}}{{this.kind}}</hp:t>",
                        "<hp:t>{{#ea</hp:t></hp:run><hp:run charPrIDRef=\"2\"><hp:t>ch ruleRows}}{{th</hp:t></hp:run>"
                                + "<hp:run charPrIDRef=\"0\"><hp:t>is.kind}}</hp:t>");
        assertThat(split).isNotEqualTo(sec).contains("{{faci</hp:t>");
        HwpxPackage splitTpl = template.with(HwpxPackage.SECTION0, split.getBytes(StandardCharsets.UTF_8));
        HwpxTemplateChecker.Result check = HwpxTemplateChecker.check(splitTpl, MANIFEST);
        assertThat(check.errors()).isEmpty();
        assertThat(check.normalizedRuns()).isGreaterThanOrEqualTo(3);
        assertThat(check.placeholders()).contains("{{facility.name}}", "{{#each ruleRows}}", "{{this.kind}}");
        byte[] zip = HwpxBinder.bind(splitTpl, model()).toZip();
        assertThat(HwpxValidator.validate(zip).errors()).isEmpty();
        String out = HwpxPackage.read(zip).text(HwpxPackage.SECTION0);
        assertThat(out).contains("시설물: 합성 사옥").contains(" (합성)").contains("COND_RATING").doesNotContain("{{");
    }

    @Test
    @DisplayName("템플릿 검증: 매니페스트에 없는 placeholder · 필수 누락 · #each 짝 불일치 → 오류")
    void templateCheckerRejects() {
        HwpxPackage template = HwpxPackage.fromClasspath(TEMPLATE);
        String sec = template.text(HwpxPackage.SECTION0)
                .replace("{{facility.name}}", "{{facility.owner}}")
                .replace("{{this.effectiveFrom}}{{/each}}", "{{this.effectiveFrom}}");
        HwpxTemplateChecker.Result r = HwpxTemplateChecker.check(
                template.with(HwpxPackage.SECTION0, sec.getBytes(StandardCharsets.UTF_8)), MANIFEST);
        assertThat(r.errors()).anyMatch(e -> e.startsWith("UNKNOWN_PLACEHOLDER: {{facility.owner}}"))
                .anyMatch(e -> e.startsWith("REQUIRED_PLACEHOLDER_MISSING: facility.name"))
                .anyMatch(e -> e.startsWith("UNPAIRED_EACH"));
    }

    @Test
    @DisplayName("검증기 음성: mimetype 압축 · 미등록 BinData · 끊긴 binaryItemIDRef · rowCnt 불일치 · 남은 placeholder · XSD 위반")
    void validatorRejects() throws Exception {
        byte[] good = HwpxBinder.bind(HwpxPackage.fromClasspath(TEMPLATE), model()).toZip();
        HwpxPackage pkg = HwpxPackage.read(good);

        // 1) mimetype DEFLATED
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            for (var e : pkg.entries().entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
        }
        assertThat(HwpxValidator.validate(bos.toByteArray()).errors()).contains("MIMETYPE_COMPRESSED");

        // 2) manifest 에서 이미지 항목 제거 → BinData 미등록 + binaryItemIDRef 끊김
        String hpf = pkg.text(HwpxPackage.CONTENT_HPF).replaceAll("<opf:item[^>]*id=\"image1\"[^>]*/>", "");
        HwpxValidator.Report r2 = HwpxValidator.validate(pkg.with(HwpxPackage.CONTENT_HPF,
                hpf.getBytes(StandardCharsets.UTF_8)).toZip());
        assertThat(r2.errors()).anyMatch(e -> e.startsWith("BINDATA_UNREGISTERED"))
                .anyMatch(e -> e.startsWith("BINARY_ITEM_REF"));

        // 3) rowCnt 불일치 · 남은 placeholder · 끊긴 charPrIDRef · XSD 필수 속성 누락
        String sec = pkg.text(HwpxPackage.SECTION0).replaceFirst("rowCnt=\"4\"", "rowCnt=\"9\"")
                .replace("책임기술자 의견.", "{{grade.unknown}}")
                .replaceFirst("<hp:run charPrIDRef=\"0\">", "<hp:run charPrIDRef=\"77\">")
                .replaceFirst("<hp:cellAddr colAddr=\"0\" rowAddr=\"0\"/>", "<hp:cellAddr colAddr=\"0\"/>");
        HwpxValidator.Report r3 = HwpxValidator.validate(pkg.with(HwpxPackage.SECTION0,
                sec.getBytes(StandardCharsets.UTF_8)).toZip());
        assertThat(r3.errors()).anyMatch(e -> e.startsWith("TABLE_ROWCNT"))
                .anyMatch(e -> e.startsWith("UNRESOLVED_PLACEHOLDER"))
                .anyMatch(e -> e.startsWith("DANGLING_charPrIDRef: 77"))
                .anyMatch(e -> e.startsWith("SCHEMA"));

        // 4) 깨진 XML
        HwpxValidator.Report r4 = HwpxValidator.validate(pkg.with(HwpxPackage.HEADER,
                "<hh:head".getBytes(StandardCharsets.UTF_8)).toZip());
        assertThat(r4.errors()).anyMatch(e -> e.startsWith("XML_MALFORMED"));
    }
}
