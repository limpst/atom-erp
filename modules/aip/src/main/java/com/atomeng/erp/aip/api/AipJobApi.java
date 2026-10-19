package com.atomeng.erp.aip.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.UUID;

/**
 * 동기 추론 Job (설계 11 v0.3 B6 — RPT AI 초안 · QAR AI 검토는 이벤트가 아니라 요청자가 타임아웃 · 재시도를 통제하는 동기 호출).
 * 호출마다 aip.job 행(mode SYNC)과 inference_audit 이 남고, 성공하면 {@code aip.inference.completed} 가 발행된다.
 * 워커 미설정(기본) → {@link AipDisabledException}, 워커 장애 → {@link AipUnavailableException}, 마감 초과 → {@link AipTimeoutException}.
 * 같은 idempotencyKey 로 다시 부르면 이미 성공한 Job 의 결과를 그대로 돌려준다(중복 추론 없음, TC-AIP-001).
 */
public interface AipJobApi {

    SyncResult runSync(SyncRequest request);

    /** 워커 연결 설정 여부(비활성이면 호출 전에 스킵 판단 가능) */
    boolean enabled();

    /**
     * @param requesterModule 호출 모듈(RPT · QAR …) — 감사 · 통계용
     * @param projectId 프로젝트(없으면 null)
     * @param idempotencyKey 멱등 키(없으면 null — 매번 새 Job)
     * @param payload 워커 요청 본문(서비스별 계약: services/aip-worker/contract/openapi.json)
     * @param timeout 요청자 마감(이 시간 안에 워커 응답이 없으면 TIMEOUT)
     */
    record SyncRequest(AipServiceCode serviceCode, String requesterModule, UUID projectId, String idempotencyKey,
                       JsonNode payload, Duration timeout) {}

    /** output = 워커 응답 본문, model · modelVersion = 레지스트리 production 별칭 해석 결과, replayed = 멱등 재사용 */
    record SyncResult(UUID jobId, String status, String model, String modelVersion, JsonNode output, long latencyMs,
                      boolean replayed) {}

    /** 워커 미설정 — 메시지는 "DISABLED" 로 시작(QAR 스킵 사유 DISABLED) */
    class AipDisabledException extends AipUnavailableException {
        private static final long serialVersionUID = 1L;

        public AipDisabledException(String message) {
            super(message.startsWith("DISABLED") ? message : "DISABLED: " + message, null);
        }
    }

    /** 워커 장애 · 오류 응답 */
    class AipUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AipUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 마감 초과 */
    class AipTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AipTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
