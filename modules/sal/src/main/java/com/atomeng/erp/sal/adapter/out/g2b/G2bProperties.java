package com.atomeng.erp.sal.adapter.out.g2b;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 조달청 나라장터 입찰공고정보서비스(공공데이터포털) 설정 (설계 B8, 01-IMPL Step 10).
 * 키는 환경변수 · Vault 로만 주입(커밋 금지). 키가 비면 수집은 NOT_CONFIGURED 실패로 기록된다.
 * ⚠️ 오퍼레이션명 · 파라미터 · 응답 필드 · 호출 한도는 활용신청 후 명세서로 확정 — 지금 값은 가정(확인 필요).
 */
@ConfigurationProperties("atom.sal.g2b")
public record G2bProperties(String baseUrl, String serviceKey, String noticeOperation, Integer pageSize,
                            Duration connectTimeout, Duration readTimeout, Integer maxAttempts,
                            Duration initialBackoff) {

    public G2bProperties {
        baseUrl = baseUrl == null ? "https://apis.data.go.kr/1230000/ad/BidPublicInfoService" : baseUrl;
        noticeOperation = noticeOperation == null ? "getBidPblancListInfoServc" : noticeOperation;
        pageSize = pageSize == null ? 100 : pageSize;
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(3) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(10) : readTimeout;
        maxAttempts = maxAttempts == null ? 3 : maxAttempts;
        initialBackoff = initialBackoff == null ? Duration.ofSeconds(2) : initialBackoff;
    }
}
