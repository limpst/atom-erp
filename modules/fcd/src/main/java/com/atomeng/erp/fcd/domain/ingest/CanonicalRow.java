package com.atomeng.erp.fcd.domain.ingest;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 표준 레코드 — 세 채널(API · CSV · EXCEL) 모두 이 형태로 바뀐 뒤에야 중복 제거 · 탐지로 넘어간다.
 * 카드는 토큰으로 식별된 card_id 만 갖고 PAN 은 없다. raw 는 PAN 마스킹된 원문.
 */
public record CanonicalRow(String channel, String kind, UUID cardId, String approvalNo, LocalDateTime approvedAt,
                           String precision, long amountKrw, String merchantName, String bizRegNo,
                           String categoryHint, String currency, Double amountForeign, Double fxRate,
                           Long cancelAmount, Boolean partial, LocalDateTime cancelAt, Boolean isOnline,
                           String country, Integer rowNo, Map<String, Object> raw, String rawHash) {

    public String merchantNorm() {
        return MerchantText.normalize(merchantName, true);
    }

    public boolean isOverseas() {
        return !"KRW".equals(currency);
    }

    public CanonicalRow withRawHash(String h) {
        return new CanonicalRow(channel, kind, cardId, approvalNo, approvedAt, precision, amountKrw, merchantName, bizRegNo,
                categoryHint, currency, amountForeign, fxRate, cancelAmount, partial, cancelAt, isOnline, country, rowNo,
                raw, h);
    }
}
