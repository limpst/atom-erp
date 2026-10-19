package com.atomeng.erp.ptl;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.erp.ptl.application.PdfWatermarker;
import com.atomeng.erp.ptl.domain.WatermarkStamp;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TC-PTL-04 (단위): 합성 스텁 PDF 3쪽에 워터마크 → 모든 쪽 보이는 문구에 download-id · 고객 id · 시각, Info · XMP 메타데이터,
 * 복사 금지 권한, 다운로드마다 다른 결과 해시. 실제 발행본 경로(rpt::api)는 apps PtlIntegrationTest.
 */
class PdfWatermarkerTest {

    static byte[] stubPdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 1; i <= pages; i++) {
                PDPage p = new PDPage(PDRectangle.A4);
                doc.addPage(p);
                try (PDPageContentStream cs = new PDPageContentStream(doc, p)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.newLineAtOffset(72, 760);
                    cs.showText("Synthetic inspection report page " + i);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @DisplayName("TC-PTL-04: 모든 쪽 워터마크(download-id · customer · 시각) + Info/XMP 메타데이터 + 복사 금지 + 결과 해시")
    void stampsEveryPageAndMetadata() throws Exception {
        byte[] src = stubPdf(3);
        UUID customer = UUID.randomUUID();
        UUID download = UUID.randomUUID();
        WatermarkStamp stamp = new WatermarkStamp(customer, download, Instant.parse("2026-10-19T01:02:03Z"));
        PdfWatermarker.Result r = new PdfWatermarker().apply(src, stamp);

        assertThat(r.pageCount()).isEqualTo(3);
        assertThat(r.sha256()).isEqualTo(Hashing.sha256Hex(r.pdf())).isNotEqualTo(Hashing.sha256Hex(src));
        assertThat(stamp.timestamp()).isEqualTo("2026-10-19T10:02:03+09:00");
        try (PDDocument doc = Loader.loadPDF(r.pdf())) {
            assertThat(doc.isEncrypted()).isTrue();
            assertThat(doc.getCurrentAccessPermission().canExtractContent()).isFalse();
            assertThat(doc.getCurrentAccessPermission().canModify()).isFalse();
            assertThat(doc.getCurrentAccessPermission().canPrint()).isTrue();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int p = 1; p <= 3; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc);
                assertThat(text).as("page %d", p).contains("download-id " + download)
                        .contains("customer " + customer).contains("2026-10-19T10:02:03+09:00")
                        .contains("Synthetic inspection report page " + p);
            }
            var info = doc.getDocumentInformation();
            assertThat(info.getCustomMetadataValue("AtomCustomerId")).isEqualTo(customer.toString());
            assertThat(info.getCustomMetadataValue("AtomDownloadId")).isEqualTo(download.toString());
            assertThat(info.getCustomMetadataValue("AtomDownloadedAt")).isEqualTo("2026-10-19T10:02:03+09:00");
            String xmp = new String(doc.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
            assertThat(xmp).contains("<atom:downloadId>" + download + "</atom:downloadId>")
                    .contains("<atom:customerId>" + customer + "</atom:customerId>");
        }
    }

    @Test
    @DisplayName("같은 원본 · 다른 다운로드 → 다른 download-id · 다른 결과 해시(유출본 역추적)")
    void eachDownloadIsUnique() throws Exception {
        byte[] src = stubPdf(1);
        UUID customer = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-19T00:00:00Z");
        var w = new PdfWatermarker();
        var r1 = w.apply(src, new WatermarkStamp(customer, UUID.randomUUID(), at));
        var r2 = w.apply(src, new WatermarkStamp(customer, UUID.randomUUID(), at));
        assertThat(r1.sha256()).isNotEqualTo(r2.sha256());
    }
}
