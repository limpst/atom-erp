package com.atomeng.erp.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.core.domain.model.ProjectMember;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 전체 업무 흐름 시험에서 드러난 연결 누락(투입 지정 API) — 투입 값 검증 단위 시험 */
class ProjectMemberTest {

    static final UUID P = UUID.randomUUID();
    static final UUID E = UUID.randomUUID();
    static final LocalDate D = LocalDate.of(2026, 10, 1);

    @Test
    @DisplayName("역할 4종(RESP · PART · QA · SUPPORT)만, 기간은 양 끝 포함(같은 날 허용) · 역전 거부, RESP 만 PM")
    void validation() {
        assertThat(new ProjectMember(P, E, "RESP", D, D).isResponsible()).isTrue();
        for (String r : new String[] {"PART", "QA", "SUPPORT"}) {
            assertThat(new ProjectMember(P, E, r, D, D.plusDays(1)).isResponsible()).isFalse();
        }
        assertThatThrownBy(() -> new ProjectMember(P, E, "PM", D, D)).isInstanceOf(DomainException.class)
                .hasMessageContaining("투입 역할");
        assertThatThrownBy(() -> new ProjectMember(P, E, "PART", D, D.minusDays(1))).isInstanceOf(DomainException.class)
                .hasMessageContaining("종료일");
        assertThatThrownBy(() -> new ProjectMember(P, E, "PART", null, D)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProjectMember(null, E, "PART", D, D)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> new ProjectMember(P, null, "PART", D, D)).isInstanceOf(DomainException.class);
    }
}
