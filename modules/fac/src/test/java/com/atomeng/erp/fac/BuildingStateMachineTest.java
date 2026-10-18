package com.atomeng.erp.fac;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fac.domain.model.Building;
import com.atomeng.erp.fac.domain.model.FacTypes.BuildingStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 02-IMPL Step 2 BuildingStateMachineTest — 설계 A6.1 */
class BuildingStateMachineTest {

    static Building draft() {
        return Building.register(UUID.randomUUID(), new Building.Spec("합성 동", "", null, null, null, null, null,
                null, null, null, null, null));
    }

    @Test
    @DisplayName("A6.1 정상 경로: DRAFT → VERIFIED → CLASSIFIED → ACTIVE → DEMOLISHED")
    void happyPath() {
        var b = draft();
        b.verify();
        b.classify();
        b.activate();
        b.classify(); // ACTIVE 재분류는 상태 유지
        assertThat(b.getStatus()).isEqualTo(BuildingStatus.ACTIVE);
        b.markDemolished();
        assertThat(b.getStatus()).isEqualTo(BuildingStatus.DEMOLISHED);
    }

    @Test
    @DisplayName("A6.1 금지 전이 → FAC-409-002")
    void illegalTransitions() {
        var b = draft();
        assertThatThrownBy(b::activate).isInstanceOf(DomainException.class).extracting("code").isEqualTo("FAC-409-002");
        assertThatThrownBy(b::markDemolished).isInstanceOf(DomainException.class);
        b.verify();
        assertThatThrownBy(b::verify).isInstanceOf(DomainException.class);
        b.markMerged(UUID.randomUUID());
        assertThatThrownBy(() -> b.markMerged(UUID.randomUUID())).isInstanceOf(DomainException.class);
        assertThatThrownBy(b::classify).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> b.change(new Building.Spec("x", null, null, null, null, null, null, null, null,
                null, null, null))).isInstanceOf(DomainException.class);
    }
}
