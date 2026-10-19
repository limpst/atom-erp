package com.atomeng.erp.aip.application.port;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

/**
 * Python AIP 워커(services/aip-worker, FastAPI) 호출 포트 — 계약은 services/aip-worker/contract/openapi.json(기록본).
 * 실패는 {@link com.atomeng.erp.aip.api.AipJobApi} 의 예외(DISABLED · UNAVAILABLE · TIMEOUT)로 알린다.
 */
public interface AipWorkerPort {

    String HEALTH = "/internal/v1/health";
    String CRACK_SCORE = "/internal/v1/crack/score";
    String FINDINGS = "/internal/v1/findings/generate";
    String CRACK_MEASURE = "/internal/v1/crack/measure";
    String KB_SEARCH = "/internal/v1/kb/search";
    String REPORT_DRAFT = "/internal/v1/report/draft";
    String REPORT_REVIEW = "/internal/v1/report/review";

    boolean enabled();

    /** POST JSON → 응답 본문. status 2xx 가 아니면 예외(422 = 입력 · 출력 형식 오류도 UNAVAILABLE 로 본다) */
    Response post(String path, JsonNode body, Duration timeout);

    record Response(int status, JsonNode body, long latencyMs) {}
}
