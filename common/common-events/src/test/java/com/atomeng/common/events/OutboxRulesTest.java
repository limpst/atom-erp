package com.atomeng.common.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OutboxRulesTest {

    @Test
    @DisplayName("설계 10.3-3: 지수 백오프 10s → 20s → 40s → 80s, 5회째 DLQ")
    void exponentialBackoff() {
        assertThat(OutboxRelay.backoff(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(OutboxRelay.backoff(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(OutboxRelay.backoff(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(OutboxRelay.backoff(4)).isEqualTo(Duration.ofSeconds(80));
        assertThat(OutboxRelay.MAX_ATTEMPTS).isEqualTo(5);
        assertThatThrownBy(() -> OutboxRelay.backoff(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sal.contract.signed", "fld.survey.synced", "fld.defect.recorded",
            "rpt.report.published", "fac.inspection.due-approaching", "hrq.credential.expired",
            "core.project.created", "fac.inspection.overdue", "fac.grade.critical-detected"})
    @DisplayName("설계 10.1 v0.3: 정식 이벤트명은 {mod}.{aggregate}.{과거형} 규칙 통과")
    void validNames(String name) {
        assertThat(EventNames.requireValid(name)).isEqualTo(name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ContractSigned", "sal.contract", "sal.contract.sign", "SAL.contract.signed",
            "sal.Contract.signed", "salesx.contract.signed"})
    @DisplayName("규칙 위반 이벤트명은 발행 거부")
    void invalidNames(String name) {
        assertThatThrownBy(() -> EventNames.requireValid(name)).isInstanceOf(IllegalArgumentException.class);
    }
}
