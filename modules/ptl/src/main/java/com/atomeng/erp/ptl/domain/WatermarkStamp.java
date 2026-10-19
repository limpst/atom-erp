package com.atomeng.erp.ptl.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * 다운로드 워터마크 내용 (설계 09 B7.1 · BR-PTL-003): 고객사 id · 다운로드 시각(KST) · 다운로드 고유 id.
 * PDF 각 쪽의 보이는 문구와 문서 메타데이터(Info · XMP)에 같은 값을 찍는다. PDF 기본 글꼴(WinAnsi)로 그릴 수 있게 ASCII 만 쓴다.
 */
public record WatermarkStamp(UUID customerId, UUID downloadId, Instant downloadedAt) {

    private static final DateTimeFormatter KST = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")
            .withZone(ZoneId.of("Asia/Seoul"));

    public String timestamp() {
        return KST.format(downloadedAt);
    }

    /** 각 쪽 하단 문구(2줄 — 좁은 쪽에서도 잘리지 않게) */
    public java.util.List<String> footerLines() {
        return java.util.List.of("ATOM ENG licensed copy - do not redistribute | download-id " + downloadId,
                "customer " + customerId + " | downloaded " + timestamp());
    }

    /** 메타데이터 · 감사 로그에 남기는 한 줄 요약 */
    public String summary() {
        return "customer=" + customerId + ";downloadedAt=" + timestamp() + ";downloadId=" + downloadId;
    }

    /** 각 쪽 대각선 반투명 문구 */
    public String diagonal() {
        return "customer " + customerId + " | " + timestamp();
    }
}
