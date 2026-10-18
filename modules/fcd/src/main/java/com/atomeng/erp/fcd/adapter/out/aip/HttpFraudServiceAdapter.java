package com.atomeng.erp.fcd.adapter.out.aip;

import com.atomeng.erp.fcd.application.port.FraudServicePort;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Python 서비스 HTTP 어댑터 — POST {base}/v1/fraud/context · /v1/fraud/score (설계 B7.5.5).
 * 타임아웃(기본 800ms) · 오류 시 맥락 없음 / l3_status=TIMEOUT|ERROR 로 L1 점수만 쓴다(설계 A8 "L3 장애 시 L1+L2 잠정 점수").
 * 요청에는 임직원 실명 · PAN 을 넣지 않는다(UUID · 가맹점명 · 금액 · 시각).
 */
public class HttpFraudServiceAdapter implements FraudServicePort {

    private static final Logger log = LoggerFactory.getLogger(HttpFraudServiceAdapter.class);
    private final RestClient client;

    public HttpFraudServiceAdapter(String baseUrl, Duration timeout) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout((int) timeout.toMillis());
        f.setReadTimeout((int) timeout.toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    private static List<Map<String, Object>> body(List<TxnRef> txns, Map<UUID, Map<String, Object>> tx) {
        return txns.stream().map(t -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("txn_id", t.txnId());
            m.put("emp_id", t.employeeId());
            m.put("card_id", t.cardId());
            m.put("approved_at", t.approvedAt().toString());
            m.put("merchant_id", t.merchantId());
            m.put("merchant_name", t.merchantName());
            m.put("amount", t.amount());
            if (tx != null) {
                m.put("tx", tx.get(t.txnId()));
            }
            return m;
        }).toList();
    }

    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException
                    || (t.getMessage() != null && t.getMessage().toLowerCase(java.util.Locale.ROOT).contains("timed out"))) {
                return true;
            }
        }
        return false;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<UUID, Map<String, Object>> context(List<TxnRef> txns) {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        try {
            Map<String, Object> res = client.post().uri("/v1/fraud/context").body(Map.of("txns", body(txns, null)))
                    .retrieve().body(Map.class);
            if (res != null && res.get("context") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(UUID.fromString(String.valueOf(k)), (Map<String, Object>) v));
            }
        } catch (RestClientException e) {
            log.warn("FCD L2 맥락 조회 실패 — 맥락 없이(판정 보류) 채점: {}", e.getMessage());
        }
        return out;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<UUID, L3Result> anomaly(List<TxnRef> txns, Map<UUID, Map<String, Object>> txFeatures) {
        Map<UUID, L3Result> out = new HashMap<>();
        String status = "ERROR";
        try {
            Map<String, Object> res = client.post().uri("/v1/fraud/score").body(Map.of("txns", body(txns, txFeatures)))
                    .retrieve().body(Map.class);
            String model = res == null ? null : (String) res.get("model_version");
            if (res != null && res.get("results") instanceof Map<?, ?> m) {
                m.forEach((k, v) -> out.put(UUID.fromString(String.valueOf(k)),
                        new L3Result((Map<String, Object>) v, "OK", model)));
            }
            status = "OK";
        } catch (RestClientException e) {
            status = isTimeout(e) ? "TIMEOUT" : "ERROR";
            log.warn("FCD L3 채점 실패({}) — L1+L2 잠정 점수: {}", status, e.getMessage());
        }
        for (TxnRef t : txns) {
            out.putIfAbsent(t.txnId(), new L3Result(Map.of(), status.equals("OK") ? "DISABLED" : status, null));
        }
        return out;
    }
}
