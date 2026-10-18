package com.atomeng.erp.rpt.adapter.out.pdf;

import com.atomeng.common.core.util.Hashing;
import com.atomeng.erp.rpt.application.port.PdfConverterPort;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 스텁 PDF 변환기 — Gotenberg 가 없는 개발 PC · 테스트용. HWPX 를 실제로 그리지 않고, 레이아웃 추정 쪽수만큼
 * 빈 A4 쪽에 "STUB" 표식과 HWPX 해시를 적은 <b>구조상 유효한 PDF 1.4</b>(xref 오프셋 정확)를 만든다.
 * 운영에서는 atom.rpt.pdf.gotenberg-url 을 설정해 {@link GotenbergPdfConverter} 를 쓴다.
 */
public class StubPdfConverter implements PdfConverterPort {

    @Override
    public Result convert(String fileName, byte[] hwpx, int expectedPages) {
        int pages = Math.max(1, expectedPages);
        String sha = Hashing.sha256Hex(hwpx);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        int fontObj = 3;
        int firstPage = 4;
        // 1 catalog
        offsets.add(out.size());
        write(out, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
        // 2 pages
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages; i++) {
            kids.append(firstPage + i * 2).append(" 0 R ");
        }
        offsets.add(out.size());
        write(out, "2 0 obj\n<< /Type /Pages /Kids [" + kids.toString().strip() + "] /Count " + pages + " >>\nendobj\n");
        // 3 font
        offsets.add(out.size());
        write(out, fontObj + " 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");
        for (int i = 0; i < pages; i++) {
            int pageObj = firstPage + i * 2;
            int contentObj = pageObj + 1;
            offsets.add(out.size());
            write(out, pageObj + " 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 "
                    + fontObj + " 0 R >> >> /Contents " + contentObj + " 0 R >>\nendobj\n");
            String text = "BT /F1 10 Tf 56 800 Td (ATOM-ERP RPT STUB PDF page " + (i + 1) + "/" + pages + ") Tj ET\n"
                    + "BT /F1 7 Tf 56 786 Td (" + sanitize(fileName) + " sha256 " + sha + ") Tj ET\n";
            byte[] stream = text.getBytes(StandardCharsets.US_ASCII);
            offsets.add(out.size());
            write(out, contentObj + " 0 obj\n<< /Length " + stream.length + " >>\nstream\n");
            out.writeBytes(stream);
            write(out, "endstream\nendobj\n");
        }
        int xref = out.size();
        int count = offsets.size() + 1;
        StringBuilder x = new StringBuilder("xref\n0 " + count + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            x.append(String.format("%010d 00000 n \n", off));
        }
        x.append("trailer\n<< /Size ").append(count).append(" /Root 1 0 R >>\nstartxref\n").append(xref)
                .append("\n%%EOF\n");
        write(out, x.toString());
        return new Result(out.toByteArray(), "STUB", pages);
    }

    private static String sanitize(String s) {
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static void write(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }
}
