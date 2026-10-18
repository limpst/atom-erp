package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.common.events.EventNames;
import com.atomeng.erp.cst.api.CstSubscriptionContracts;
import com.atomeng.erp.eqp.api.EqpEvents;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EQP ↔ CST 이벤트 계약 테스트 — CST 가 먼저 만든 구독 스텁(cst::api CstSubscriptionContracts.EqpEquipmentReturned)에
 * 실제 EQP 발행 이벤트(eqp::api EqpEvents.EquipmentReturned)가 이름 · 필드 · 값까지 맞는지 JSON 왕복으로 확인한다.
 * 알 수 없는 필드는 실패(FAIL_ON_UNKNOWN_PROPERTIES)라 EQP 쪽 필드 추가 · 이름 변경이 바로 드러난다.
 */
class EqpCstContractTest {

    static final ObjectMapper OM = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    @Test
    @DisplayName("eqp.equipment.returned (EQP 발행) = CST 구독 계약 — rentalId(=checkoutId) · equipmentId · equipmentCode · projectId · days · returnedOn")
    void eqpReturnedToCst() throws Exception {
        var ev = new EqpEvents.EquipmentReturned(UUID.randomUUID(), UUID.randomUUID(), "EQ-NDT-RH-0007", UUID.randomUUID(),
                3, LocalDate.of(2026, 10, 17));
        String json = OM.writeValueAsString(ev);
        var c = OM.readValue(json, CstSubscriptionContracts.EqpEquipmentReturned.class);
        assertThat(EqpEvents.EquipmentReturned.TYPE).isEqualTo(CstSubscriptionContracts.EqpEquipmentReturned.TYPE);
        assertThat(List.of(c.rentalId(), c.equipmentId(), c.equipmentCode(), c.projectId(), c.days(), c.returnedOn()))
                .isEqualTo(List.of(ev.rentalId(), ev.equipmentId(), ev.equipmentCode(), ev.projectId(), ev.days(),
                        ev.returnedOn()));
        // 반대 방향도 왕복(필드 집합 동일)
        assertThat(OM.readValue(OM.writeValueAsString(c), EqpEvents.EquipmentReturned.class)).isEqualTo(ev);
        assertThat(OM.readTree(json).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("rentalId", "equipmentId", "equipmentCode", "projectId", "days", "returnedOn");
    }

    @Test
    @DisplayName("EQP 발행 이벤트명 9종 = 설계 04 B6 이름 · {mod}.{aggregate}.{과거형} 규칙 통과")
    void eventNames() {
        for (String t : List.of(EqpEvents.EquipmentRegistered.TYPE, EqpEvents.CalibrationRecorded.TYPE,
                EqpEvents.CalibrationDueSoon.TYPE, EqpEvents.CalibrationExpired.TYPE, EqpEvents.EquipmentCheckedOut.TYPE,
                EqpEvents.CheckoutConflicted.TYPE, EqpEvents.EquipmentReturned.TYPE, EqpEvents.FaultReported.TYPE,
                EqpEvents.FlightLogged.TYPE)) {
            assertThat(EventNames.requireValid(t)).startsWith("eqp.");
        }
        assertThat(List.of(EqpEvents.CalibrationDueSoon.TYPE, EqpEvents.CalibrationExpired.TYPE,
                EqpEvents.EquipmentReturned.TYPE, EqpEvents.FlightLogged.TYPE, EqpEvents.EquipmentCheckedOut.TYPE))
                .containsExactly("eqp.calibration.due-soon", "eqp.calibration.expired", "eqp.equipment.returned",
                        "eqp.flight.logged", "eqp.equipment.checked-out");
    }
}
