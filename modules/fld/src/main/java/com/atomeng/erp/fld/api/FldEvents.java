package com.atomeng.erp.fld.api;

import com.atomeng.common.events.DomainEvent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * FLD 발행 이벤트 (설계 05 v0.2 B6.2 정본 이름). payload = 레코드 컴포넌트.
 * 구독 계약: {@code fld.defect.recorded} → FAC(fac.node_defect_ref, defectId · locationNodeId),
 * {@code fld.survey.completed} → RPT · CST, {@code fld.ai-suggestion.discrepancy} → QAR. 계약 테스트(apps FldContractTest)가
 * 소비자 쪽 레코드와 JSON 왕복(알 수 없는 필드 = 실패)으로 확인한다.
 */
public final class FldEvents {

    private FldEvents() {}

    /** fld.defect.recorded — 결함이 필수값을 갖춰 RECORDED 가 되었거나(최초) 위치 노드가 바뀜. 소비: FAC(BR-FAC-010 삭제 검사) */
    public record DefectRecorded(UUID defectId, UUID roundId, UUID projectId, UUID buildingId, UUID locationNodeId,
                                 String code, String typeCode) implements DomainEvent {
        public static final String TYPE = "fld.defect.recorded";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Defect"; }
        @Override public UUID aggregateId() { return defectId; }
    }

    /** fld.defect.changed — push 배치 1회 = 1건(회차 단위 묶음). 소비: RPT(망도 · 물량표 캐시 무효화) */
    public record DefectChanged(UUID roundId, UUID projectId, List<UUID> defectIds, long fromSeq, long toSeq,
                                int eventCount) implements DomainEvent {
        public static final String TYPE = "fld.defect.changed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "SurveyRound"; }
        @Override public UUID aggregateId() { return roundId; }
    }

    /** fld.photo.verified — 이어 올리기 완료 · 서버 SHA-256 = 기기 해시 · core 첨부(WORM · 체인) 확정. 소비: AIP(정밀 분석 큐) */
    public record PhotoVerified(UUID photoId, UUID roundId, UUID projectId, UUID defectId, UUID attachmentId,
                                String sha256, String chainHash) implements DomainEvent {
        public static final String TYPE = "fld.photo.verified";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Photo"; }
        @Override public UUID aggregateId() { return photoId; }
    }

    /** fld.zone.locked — PM 검수 잠금. 소비: RPT · QAR · CST */
    public record ZoneLocked(UUID zoneId, UUID roundId, UUID projectId, String zoneName, int defectCount)
            implements DomainEvent {
        public static final String TYPE = "fld.zone.locked";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Zone"; }
        @Override public UUID aggregateId() { return zoneId; }
    }

    /**
     * fld.survey.completed (v0.2) — 회차의 모든 구역이 잠기면 1회만. 소비: RPT(보고서 작성 가능 신호 · 스냅샷 원천),
     * CST(진척 마일스톤 "현장조사 완료"). lastSeq = 완료 시점 이벤트 커서(스냅샷 워터마크).
     */
    public record SurveyCompleted(UUID roundId, UUID projectId, UUID buildingId, int roundNo, String surveyType,
                                  int zoneCount, int defectCount, long lastSeq, LocalDate completedOn)
            implements DomainEvent {
        public static final String TYPE = "fld.survey.completed";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "SurveyRound"; }
        @Override public UUID aggregateId() { return roundId; }
    }

    /** fld.ai-suggestion.discrepancy (v0.2) — AI 균열폭과 사람 측정값 차이 2배 이상(A8-8). 소비: QAR(검토 시 강조) */
    public record AiSuggestionDiscrepancy(UUID suggestionId, UUID defectId, UUID roundId, UUID projectId,
                                          String defectCode, BigDecimal humanWidthMm, BigDecimal aiWidthMm,
                                          BigDecimal ratio) implements DomainEvent {
        public static final String TYPE = "fld.ai-suggestion.discrepancy";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "Defect"; }
        @Override public UUID aggregateId() { return defectId; }
    }

    /** fld.ai-suggestion.decided — 수락 · 수정 · 거절(라벨 데이터, diff). 소비: AIP */
    public record AiSuggestionDecided(UUID suggestionId, UUID defectId, String decision, BigDecimal aiWidthMm,
                                      BigDecimal decidedWidthMm) implements DomainEvent {
        public static final String TYPE = "fld.ai-suggestion.decided";

        @Override public String eventType() { return TYPE; }
        @Override public String aggregateType() { return "AiSuggestion"; }
        @Override public UUID aggregateId() { return suggestionId; }
    }
}
