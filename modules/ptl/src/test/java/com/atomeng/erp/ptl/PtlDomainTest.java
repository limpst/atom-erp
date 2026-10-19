package com.atomeng.erp.ptl;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.ptl.domain.InspectionSeverity;
import com.atomeng.erp.ptl.domain.ServiceRequestKind;
import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 09 B7.2 심각도 경계 · 서비스 요청 종류 규칙 */
class PtlDomainTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

    @ParameterizedTest(name = "D{0} → {1}")
    @CsvSource({"-1,OVERDUE", "0,RED", "30,RED", "31,YELLOW", "90,YELLOW", "91,GREEN", "400,GREEN"})
    @DisplayName("B7.2: 경과 OVERDUE · D-30 이하 RED · D-90 이하 YELLOW · 그 외 GREEN")
    void severityBoundaries(int days, InspectionSeverity expected) {
        assertThat(InspectionSeverity.of(TODAY.plusDays(days), TODAY)).isEqualTo(expected);
        assertThat(InspectionSeverity.dDay(TODAY.plusDays(days), TODAY)).isEqualTo(days);
    }

    @Test
    @DisplayName("BR-PTL-007: 긴급점검만 사유 필수 · SLA 2시간, 종류별 기본 용역 종류")
    void serviceRequestKinds() {
        assertThat(ServiceRequestKind.EMERGENCY.requiresReason()).isTrue();
        assertThat(ServiceRequestKind.QUOTE.requiresReason()).isFalse();
        assertThat(ServiceRequestKind.EMERGENCY_SLA_HOURS).isEqualTo(2);
        assertThat(ServiceRequestKind.QUOTE.defaultServiceType()).isEqualTo("INQUIRY");
    }
}
