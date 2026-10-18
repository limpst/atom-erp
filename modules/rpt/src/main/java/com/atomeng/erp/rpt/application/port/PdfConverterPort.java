package com.atomeng.erp.rpt.application.port;

/**
 * HWPX → PDF 변환 포트 (설계 06 B7.3 PdfConverterClient). 1안 Gotenberg(LibreOffice + H2Orestart),
 * 2안 한컴 문서변환 서버 — 어댑터 교체로 바꾼다. PDF/A-2b · 전자서명(PAdES)은 후속 단계.
 */
public interface PdfConverterPort {

    /**
     * @param fileName      업로드 파일명(report.hwpx)
     * @param hwpx          HWPX 바이트
     * @param expectedPages 레이아웃 추정 페이지 수(스텁은 이 쪽수로 만든다)
     */
    Result convert(String fileName, byte[] hwpx, int expectedPages);

    /** converter = GOTENBERG · STUB, pageCount = 결과 PDF 쪽수(모르면 -1) */
    record Result(byte[] pdf, String converter, int pageCount) {}
}
