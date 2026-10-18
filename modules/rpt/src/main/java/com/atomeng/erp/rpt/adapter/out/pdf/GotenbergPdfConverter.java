package com.atomeng.erp.rpt.adapter.out.pdf;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.rpt.application.port.PdfConverterPort;
import com.atomeng.erp.rpt.domain.RptCodes;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Gotenberg 어댑터 (설계 06 B8: HTTP multipart {@code POST /forms/libreoffice/convert}, 06-IMPL Step 9).
 * Gotenberg 커스텀 이미지(LibreOffice + H2Orestart HWPX import + 한글 폰트)를 전제로 한다.
 * PDF/A-2b({@code pdfa}) · 섹션 병렬 변환 · 품질 검사(폰트 임베딩)는 렌더 워커 단계에서 추가한다.
 */
public class GotenbergPdfConverter implements PdfConverterPort {

    private static final Logger log = LoggerFactory.getLogger(GotenbergPdfConverter.class);
    private static final Pattern PAGE = Pattern.compile("/Type\\s*/Page[^s]");

    private final RestClient client;

    public GotenbergPdfConverter(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) Math.min(Integer.MAX_VALUE, Duration.ofSeconds(10).toMillis()));
        f.setReadTimeout((int) Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    @Override
    public Result convert(String fileName, byte[] hwpx, int expectedPages) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("files", new ByteArrayResource(hwpx) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        byte[] pdf;
        try {
            pdf = client.post().uri("/forms/libreoffice/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException e) {
            log.warn("Gotenberg 변환 실패: {}", e.getMessage());
            throw new DomainException(RptCodes.PDF_UNAVAILABLE, "PDF 변환기(Gotenberg) 오류: " + e.getMessage());
        }
        if (pdf == null || pdf.length < 5 || !new String(pdf, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) {
            throw new DomainException(RptCodes.PDF_UNAVAILABLE, "PDF 변환 결과가 PDF 가 아님");
        }
        Matcher m = PAGE.matcher(new String(pdf, StandardCharsets.ISO_8859_1));
        int pages = 0;
        while (m.find()) {
            pages++;
        }
        return new Result(pdf, "GOTENBERG", pages == 0 ? -1 : pages);
    }
}
