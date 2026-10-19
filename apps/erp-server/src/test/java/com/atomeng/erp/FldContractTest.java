package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.cst.api.CstSubscriptionContracts;
import com.atomeng.erp.fld.api.FldEvents;
import com.atomeng.erp.qar.api.QarSubscriptionContracts;
import com.atomeng.erp.rpt.api.RptSubscriptionContracts;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FLD → RPT · CST · QAR · FAC 이벤트 계약 테스트 (설계 05 v0.2 B6.2). 소비자 쪽 구독 레코드에 실제 FLD 발행 레코드(fld::api FldEvents)가
 * 이름 · 필드 · 값까지 맞는지 JSON 왕복으로 확인한다(알 수 없는 필드 = 실패). FAC 는 fld.defect.recorded 의
 * defectId · locationNodeId 두 필드를 payload 경로로 읽는다(fac DefectReferenceListener).
 */
class FldContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("fld.survey.completed (FLD 발행) = RPT · CST 구독 계약 — 양방향 왕복 · 필드 집합 일치")
    void surveyCompletedToRptAndCst() throws Exception {
        var ev = new FldEvents.SurveyCompleted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2,
                "PRECISE_INSPECTION", 3, 42, 1250L, LocalDate.of(2026, 10, 19));
        String json = OM.writeValueAsString(ev);
        var rpt = OM.readValue(json, RptSubscriptionContracts.FldSurveyCompleted.class);
        var cst = OM.readValue(json, CstSubscriptionContracts.FldSurveyCompleted.class);
        assertThat(OM.readValue(OM.writeValueAsString(rpt), FldEvents.SurveyCompleted.class)).isEqualTo(ev);
        assertThat(OM.readValue(OM.writeValueAsString(cst), FldEvents.SurveyCompleted.class)).isEqualTo(ev);
        assertThat(FldEvents.SurveyCompleted.TYPE).isEqualTo(RptSubscriptionContracts.FldSurveyCompleted.TYPE)
                .isEqualTo(CstSubscriptionContracts.FldSurveyCompleted.TYPE).isEqualTo("fld.survey.completed");
        assertThat(rpt.lastSeq()).isEqualTo(1250L);
        assertThat(cst.completedOn()).isEqualTo(LocalDate.of(2026, 10, 19));
    }

    @Test
    @DisplayName("fld.ai-suggestion.discrepancy (FLD 발행) = QAR 구독 계약")
    void discrepancyToQar() throws Exception {
        var ev = new FldEvents.AiSuggestionDiscrepancy(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "CRK-0007", new BigDecimal("0.30"), new BigDecimal("0.90"), new BigDecimal("3.000"));
        String json = OM.writeValueAsString(ev);
        var qar = OM.readValue(json, QarSubscriptionContracts.FldAiDiscrepancy.class);
        assertThat(OM.readValue(OM.writeValueAsString(qar), FldEvents.AiSuggestionDiscrepancy.class)).isEqualTo(ev);
        assertThat(FldEvents.AiSuggestionDiscrepancy.TYPE).isEqualTo(QarSubscriptionContracts.FldAiDiscrepancy.TYPE);
    }

    @Test
    @DisplayName("fld.defect.recorded: FAC 가 읽는 defectId · locationNodeId 포함(기존 스텁 계약 유지)")
    void defectRecordedForFac() throws Exception {
        UUID defect = UUID.randomUUID();
        UUID node = UUID.randomUUID();
        var ev = new FldEvents.DefectRecorded(defect, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), node,
                "CRK-0001", "CRK");
        var tree = OM.readTree(OM.writeValueAsString(ev));
        assertThat(tree.path("defectId").asText()).isEqualTo(defect.toString());
        assertThat(tree.path("locationNodeId").asText()).isEqualTo(node.toString());
        assertThat(tree.properties()).extracting(Map.Entry::getKey).containsExactlyInAnyOrder("defectId", "roundId",
                "projectId", "buildingId", "locationNodeId", "code", "typeCode");
    }

    @Test
    @DisplayName("FLD 발행 이벤트명 = 설계 05 v0.2 B6.2 정식 이름, 이름 규칙 통과")
    void eventNames() {
        List<String> names = List.of(FldEvents.DefectRecorded.TYPE, FldEvents.DefectChanged.TYPE,
                FldEvents.PhotoVerified.TYPE, FldEvents.ZoneLocked.TYPE, FldEvents.SurveyCompleted.TYPE,
                FldEvents.AiSuggestionDiscrepancy.TYPE, FldEvents.AiSuggestionDecided.TYPE);
        names.forEach(EventNames::requireValid);
        assertThat(names).containsExactly("fld.defect.recorded", "fld.defect.changed", "fld.photo.verified",
                "fld.zone.locked", "fld.survey.completed", "fld.ai-suggestion.discrepancy", "fld.ai-suggestion.decided");
    }
}
