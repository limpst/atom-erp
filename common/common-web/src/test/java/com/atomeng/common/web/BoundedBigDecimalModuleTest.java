package com.atomeng.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atomeng.common.web.json.BoundedBigDecimalModule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedBigDecimalModuleTest {

    record Box(BigDecimal v) {}

    private final ObjectMapper om = new ObjectMapper().registerModule(new BoundedBigDecimalModule());

    @Test
    @DisplayName("SEC-001: 지수 · 정밀도 폭탄 거부, 업무 범위 숫자 · 문자열 숫자 · null 통과")
    void bounds() throws Exception {
        for (String bad : new String[] {"\"1e999999999\"", "1e999999999", "1E-60", "\"" + "9".repeat(65) + "\"",
                "\"" + "1".repeat(201) + "\"", "\"abc\""}) {
            assertThatThrownBy(() -> om.readValue("{\"v\":" + bad + "}", Box.class))
                    .as(bad).isInstanceOf(InvalidFormatException.class);
        }
        assertThat(om.readValue("{\"v\":1e50}", Box.class).v()).isEqualByComparingTo("1e50");
        assertThat(om.readValue("{\"v\":\"-0.0005\"}", Box.class).v()).isEqualByComparingTo("-0.0005");
        assertThat(om.readValue("{\"v\":48338000}", Box.class).v()).isEqualByComparingTo("48338000");
        assertThat(om.readValue("{\"v\":null}", Box.class).v()).isNull();
    }
}
