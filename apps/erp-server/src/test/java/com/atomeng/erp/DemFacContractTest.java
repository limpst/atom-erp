package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.dem.api.DemEvents;
import com.atomeng.erp.fac.api.DemolitionCompletedContract;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DEM ↔ FAC 이벤트 계약 테스트 (설계 10 v0.3 B6 dem.demolition.completed · 구독자 FAC). FAC 구독 계약(fac::api
 * DemolitionCompletedContract)에 실제 DEM 발행 레코드(dem::api DemEvents.DemolitionCompleted)가 이름 · 필드 · 값까지 맞는지
 * JSON 왕복으로 확인한다. 알 수 없는 필드는 실패. DEM 이 구독하는 HRQ 이벤트 레코드(hrq::api)도 같은 방식으로 역직렬화된다.
 */
class DemFacContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("dem.demolition.completed (DEM 발행) = FAC 구독 계약 — 양방향 왕복 · 필드 집합 일치(buildingId 포함)")
    void demolitionCompletedToFac() throws Exception {
        var ev = new DemEvents.DemolitionCompleted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.of(2026, 10, 15), UUID.randomUUID());
        String json = OM.writeValueAsString(ev);
        var c = OM.readValue(json, DemolitionCompletedContract.class);
        assertThat(DemEvents.DemolitionCompleted.TYPE).isEqualTo(DemolitionCompletedContract.TYPE)
                .isEqualTo("dem.demolition.completed");
        assertThat(OM.readValue(OM.writeValueAsString(c), DemEvents.DemolitionCompleted.class)).isEqualTo(ev);
        assertThat(c.buildingId()).isEqualTo(ev.buildingId());
        assertThat(c.completedOn()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(OM.readTree(json).properties()).extracting(java.util.Map.Entry::getKey).containsExactlyInAnyOrder(
                "projectId", "workPackageId", "buildingId", "completedOn", "submissionId");
    }

    @Test
    @DisplayName("DEM 구독: hrq.credential.expired · hrq.employee.terminated payload 가 hrq::api 레코드로 왕복")
    void hrqEventsConsumedByDem() throws Exception {
        var exp = new HrqEvents.CredentialExpired(UUID.randomUUID(), UUID.randomUUID(), "SAFETY_DIAG_CERT",
                LocalDate.of(2026, 10, 19));
        assertThat(OM.readValue(OM.writeValueAsString(exp), HrqEvents.CredentialExpired.class)).isEqualTo(exp);
        var term = new HrqEvents.EmployeeTerminated(UUID.randomUUID(), "E-1", LocalDate.of(2026, 12, 31),
                List.of(UUID.randomUUID()), "MANUAL");
        assertThat(OM.readValue(OM.writeValueAsString(term), HrqEvents.EmployeeTerminated.class)).isEqualTo(term);
    }

    @Test
    @DisplayName("DEM 발행 이벤트명 = 설계 10 v0.3 B6 정식 세트, 이름 규칙 통과")
    void eventNames() {
        List<String> names = List.of(DemEvents.WorkPackageCreated.TYPE, DemEvents.SupervisorAssigned.TYPE,
                DemEvents.SupervisorReleased.TYPE, DemEvents.SupervisorGapDetected.TYPE, DemEvents.DailyLogSubmitted.TYPE,
                DemEvents.DailyLogLocked.TYPE, DemEvents.CorrectiveOrderIssued.TYPE, DemEvents.EvidencePhotoRegistered.TYPE,
                DemEvents.RevisionIssued.TYPE, DemEvents.CommentClosed.TYPE, DemEvents.DemolitionCompleted.TYPE,
                DemEvents.ProjectClosed.TYPE);
        names.forEach(EventNames::requireValid);
        assertThat(names).contains("dem.demolition.completed", "dem.project.closed", "dem.evidence-photo.registered",
                "dem.revision.issued", "dem.comment.closed");
    }
}
