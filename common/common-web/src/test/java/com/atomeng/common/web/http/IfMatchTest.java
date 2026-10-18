package com.atomeng.common.web.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.core.error.DomainException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IfMatchTest {

    @Test
    @DisplayName("설계 9.1: ETag \"v{version}\" ↔ If-Match 파싱")
    void roundTrip() {
        assertThat(IfMatch.etagOf(3)).isEqualTo("\"v3\"");
        assertThat(IfMatch.require("\"v3\"")).isEqualTo(3);
        assertThat(IfMatch.require("W/\"v7\"")).isEqualTo(7);
    }

    @Test
    @DisplayName("If-Match 누락 → CORE-428-002, 형식 오류 → CORE-400-001, 불일치 → CORE-412-001")
    void errors() {
        assertThatThrownBy(() -> IfMatch.require(null)).isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("CORE-428-002");
        assertThatThrownBy(() -> IfMatch.require("abc")).extracting("code").isEqualTo("CORE-400-001");
        assertThatThrownBy(() -> IfMatch.check(0, 1)).extracting("code").isEqualTo("CORE-412-001");
    }
}
