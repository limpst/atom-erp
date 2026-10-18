package com.atomeng.erp.sal.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * SAL 설정 (설계 01 · 01-IMPL Step 2·10).
 * discountLimits — BR-SAL-006 역할 · 그룹별 할인 한도(초과 시 EXEC 승인). 비우면 SALES 0.05 · SALES_LEAD 0.10 · EXEC 0.49.
 * collectOverlap — BR-SAL-013 직전 성공 range_to 에서 겹쳐 다시 읽는 시간(10분), initialLookback — 첫 실행 조회 구간(2일).
 */
@ConfigurationProperties("atom.sal")
public record SalProperties(Map<String, BigDecimal> discountLimits, Integer estimateValidDays, Duration collectOverlap,
                            Duration initialLookback) {

    public SalProperties {
        if (discountLimits == null || discountLimits.isEmpty()) {
            discountLimits = new LinkedHashMap<>();
            discountLimits.put("SALES", new BigDecimal("0.05"));
            discountLimits.put("SALES_LEAD", new BigDecimal("0.10"));
            discountLimits.put("EXEC", new BigDecimal("0.49"));
        }
        discountLimits = Map.copyOf(discountLimits);
        estimateValidDays = estimateValidDays == null ? 30 : estimateValidDays;
        collectOverlap = collectOverlap == null ? Duration.ofMinutes(10) : collectOverlap;
        initialLookback = initialLookback == null ? Duration.ofDays(2) : initialLookback;
    }
}
