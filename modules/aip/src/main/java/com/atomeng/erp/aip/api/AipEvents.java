package com.atomeng.erp.aip.api;

import com.atomeng.common.events.DomainEvent;
import java.util.List;
import java.util.UUID;

/**
 * AIP 발행 이벤트 (설계 11 v0.3 B6 이벤트 표 정본 이름). payload = 레코드 컴포넌트.
 * aip.job.queued / started / failed(모니터링) · aip.inference.completed(FLD · RPT · QAR · DEM) · aip.suggestion.decided(FLD 결함 확정)
 * · aip.dataset.committed(trainer 스케줄) · aip.model.promoted(감사 · 알림).
 * ※ 설계의 aip.model.rolled-back 은 공통 이벤트 이름 규칙(EventNames: 과거형 구동사 out|in|up|off|over|down)에 맞지 않아
 *   롤백 API 와 함께 후속(이름 규칙 확장 또는 rolledback 표기 결정 필요).
 */
public final class AipEvents {

    private AipEvents() {}

    public record JobQueued(UUID jobId, String serviceCode, int priority) implements DomainEvent {
        public static final String TYPE = "aip.job.queued";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipJob"; }
        @Override public UUID aggregateId() { return jobId; }
    }

    public record JobStarted(UUID jobId, String serviceCode, int priority) implements DomainEvent {
        public static final String TYPE = "aip.job.started";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipJob"; }
        @Override public UUID aggregateId() { return jobId; }
    }

    /** status = FAILED(재시도 예정) · DEAD(재시도 소진 · 마감 초과), reason = 오류 코드 */
    public record JobFailed(UUID jobId, String serviceCode, int priority, String status, String reason)
            implements DomainEvent {
        public static final String TYPE = "aip.job.failed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipJob"; }
        @Override public UUID aggregateId() { return jobId; }
    }

    /** resultRef = 결과 위치(이번 단계: "db:aip.job/{jobId}#result_ref" — MinIO 이관 전), suggestionIds = 생성된 제안 */
    public record InferenceCompleted(UUID jobId, String serviceCode, String resultRef, List<UUID> suggestionIds)
            implements DomainEvent {
        public static final String TYPE = "aip.inference.completed";

        public InferenceCompleted {
            suggestionIds = List.copyOf(suggestionIds);
        }

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipJob"; }
        @Override public UUID aggregateId() { return jobId; }
    }

    public record SuggestionDecided(UUID suggestionId, String decision, String reasonCode) implements DomainEvent {
        public static final String TYPE = "aip.suggestion.decided";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipSuggestion"; }
        @Override public UUID aggregateId() { return suggestionId; }
    }

    public record DatasetCommitted(UUID datasetVersionId, String name, String tag, int counts) implements DomainEvent {
        public static final String TYPE = "aip.dataset.committed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipDataset"; }
        @Override public UUID aggregateId() { return datasetVersionId; }
    }

    public record ModelPromoted(UUID modelVersionId, String modelVersion, String alias, int trafficPct)
            implements DomainEvent {
        public static final String TYPE = "aip.model.promoted";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AipModel"; }
        @Override public UUID aggregateId() { return modelVersionId; }
    }
}
