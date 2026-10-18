package com.atomeng.erp.qar.application.port;

import com.atomeng.erp.qar.domain.ai.CitationGuard;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

/**
 * Atom AI Core(AIP) 검토 포트 (설계 B4 AIP /v1/review 계약, B8). 실제 구현은 로컬 vLLM(OpenAI 호환, guided JSON) 어댑터
 * {@code VllmAtomAiCoreClient}, base-url 미설정이면 {@code DisabledAtomAiCore}(항상 미가용 → AI 단계 스킵).
 * 테스트는 대역(stub)만 쓴다 — 실제 vLLM 은 호출하지 않는다.
 */
public interface AtomAiCorePort {

    Response review(Request request);

    record BlockIn(UUID id, String text) {}

    record SectionIn(String key, List<BlockIn> blocks) {}

    /** facts = 스냅샷에서 추린 근거 데이터(quantityTable · grade · defects) — CitationGuard 가 같은 facts 로 data 인용을 대조 */
    record Request(UUID roundId, String reportType, String locale, List<SectionIn> sections, JsonNode facts,
                   List<String> checks) {}

    record Response(String model, List<CitationGuard.Draft> findings) {}

    /** AIP 장애 · 비활성 → FAILED, AI 단계 스킵(A8) */
    class AiUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AiUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** AIP 응답 시간 초과 → TIMEOUT, AI 단계 스킵(TC-QAR-006) */
    class AiTimeoutException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public AiTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
