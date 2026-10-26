package com.atomeng.erp.ptl.application;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.erp.ptl.domain.WatermarkStamp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Component;

/**
 * 다운로드마다 고객별 동적 워터마크 (설계 09 B7.1 · BR-PTL-003 · TC-PTL-04), PDFBox 3.
 * <ol>
 *   <li>모든 쪽: 대각선 반투명 문구(고객사 · 시각) + 하단 2줄(download-id · 고객사 · 시각)</li>
 *   <li>문서 Info 사용자 키 {@code AtomCustomerId · AtomDownloadId · AtomDownloadedAt} + Keywords + XMP 패킷(atom: 네임스페이스)</li>
 *   <li>권한: 인쇄 허용, 수정 · 내용 복사 금지(접근성 추출은 허용), AES-256 소유자 암호(다운로드마다 무작위 — 보관하지 않음)</li>
 * </ol>
 * 결과 SHA-256 은 다운로드 감사(ptl.report_download.output_sha256)에 남겨 유출본을 역추적한다.
 * 원본에 전자서명(PAdES)이 있으면 워터마크가 서명을 깨므로 고객 다운로드본은 "열람용 사본"(09-IMPL Step 7 함정).
 * <p>보안 검토 PTL-03: 문서 스트림 캐시는 임시 파일(MemoryUsageSetting.setupTempFileOnly)이고, 호출 빈도는
 * {@link PortalDownloadRateLimiter}(고객사별 분당 한도)가 제한한다.
 */
@Component
public class PdfWatermarker {

    private static final SecureRandom RANDOM = new SecureRandom();

    public record Result(byte[] pdf, String sha256, int pageCount) {}

    public Result apply(byte[] source, WatermarkStamp stamp) {
        // PTL-03: 스트림 캐시를 임시 파일로(MemoryUsageSetting temp-file) — 페이지 스트림 · 폰트 등 해제한 객체가 힙에 쌓이지 않게
        try (PDDocument doc = Loader.loadPDF(source, "", null, null,
                org.apache.pdfbox.io.MemoryUsageSetting.setupTempFileOnly().streamCache)) {
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (PDPage page : doc.getPages()) {
                stampPage(doc, page, font, stamp);
            }
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setCustomMetadataValue("AtomCustomerId", stamp.customerId().toString());
            info.setCustomMetadataValue("AtomDownloadId", stamp.downloadId().toString());
            info.setCustomMetadataValue("AtomDownloadedAt", stamp.timestamp());
            info.setKeywords(stamp.summary());
            PDMetadata xmp = new PDMetadata(doc);
            xmp.importXMPMetadata(xmpPacket(stamp).getBytes(StandardCharsets.UTF_8));
            doc.getDocumentCatalog().setMetadata(xmp);

            AccessPermission perm = new AccessPermission();
            perm.setCanPrint(true);
            perm.setCanModify(false);
            perm.setCanExtractContent(false);
            perm.setCanExtractForAccessibility(true);   // 스크린리더 접근성(NFR-PTL-07)
            byte[] owner = new byte[24];
            RANDOM.nextBytes(owner);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(HexFormat.of().formatHex(owner), "", perm);
            policy.setEncryptionKeyLength(256);
            doc.protect(policy);

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 4096);
            doc.save(out);
            byte[] pdf = out.toByteArray();
            return new Result(pdf, Hashing.sha256Hex(pdf), doc.getNumberOfPages());
        } catch (IOException e) {
            throw new IllegalStateException("워터마크 적용 실패: " + e.getMessage(), e);
        }
    }

    private static void stampPage(PDDocument doc, PDPage page, PDFont font, WatermarkStamp stamp) throws IOException {
        PDRectangle box = page.getMediaBox();
        try (PDPageContentStream cs = new PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            PDExtendedGraphicsState faint = new PDExtendedGraphicsState();
            faint.setNonStrokingAlphaConstant(0.12f);
            cs.setGraphicsStateParameters(faint);
            cs.beginText();
            cs.setFont(font, 14);
            cs.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(35), box.getWidth() * 0.12f, box.getHeight() * 0.25f));
            cs.showText(stamp.diagonal());
            cs.endText();

            PDExtendedGraphicsState solid = new PDExtendedGraphicsState();
            solid.setNonStrokingAlphaConstant(0.85f);
            cs.setGraphicsStateParameters(solid);
            List<String> lines = stamp.footerLines();
            float y = 18;
            for (String line : lines) {
                cs.beginText();
                cs.setFont(font, 6);
                cs.newLineAtOffset(20, y);
                cs.showText(line);
                cs.endText();
                y -= 8;
            }
        }
    }

    private static String xmpPacket(WatermarkStamp s) {
        return """
                <?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
                <x:xmpmeta xmlns:x="adobe:ns:meta/">
                 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                  <rdf:Description rdf:about="" xmlns:atom="https://atom-eng.co.kr/ns/portal/1.0/">
                   <atom:customerId>%s</atom:customerId>
                   <atom:downloadId>%s</atom:downloadId>
                   <atom:downloadedAt>%s</atom:downloadedAt>
                  </rdf:Description>
                 </rdf:RDF>
                </x:xmpmeta>
                <?xpacket end="w"?>""".formatted(s.customerId(), s.downloadId(), s.timestamp());
    }
}
