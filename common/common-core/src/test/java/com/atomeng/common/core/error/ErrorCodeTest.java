package com.atomeng.common.core.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ErrorCodeTest {

    @ParameterizedTest(name = "{0} → HTTP {1}")
    @CsvSource({"CORE-428-001,428", "CORE-409-010,409", "CORE-422-009,422", "FLD-422-003,422", "SAL-409-001,409"})
    @DisplayName("설계 9.3: {MOD}-{HTTP}-{nnn} 에서 HTTP 상태 추출")
    void parsesHttpStatus(String code, int status) {
        assertThat(ErrorCode.httpStatusOf(code)).isEqualTo(status);
        assertThat(ErrorCode.parse(code).toString()).isEqualTo(code);
    }

    @Test
    @DisplayName("잘못된 코드 형식은 거부")
    void rejectsMalformed() {
        assertThatThrownBy(() -> ErrorCode.parse("CORE-4280-01")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DomainException("oops", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("설계 9.4: 멱등 오류 코드 3종이 카탈로그에 존재하고 모두 형식이 유효")
    void catalogIsValid() {
        assertThat(CoreErrors.IDEMPOTENCY_KEY_REQUIRED).isEqualTo("CORE-428-001");
        assertThat(CoreErrors.IDEMPOTENCY_IN_PROGRESS).isEqualTo("CORE-409-010");
        assertThat(CoreErrors.IDEMPOTENCY_KEY_REUSED).isEqualTo("CORE-422-009");
        Arrays.stream(CoreErrors.class.getDeclaredFields())
                .filter(f -> Modifier.isStatic(f.getModifiers()) && f.getType() == String.class)
                .forEach(f -> {
                    try {
                        assertThat(ErrorCode.parse((String) f.get(null)).module()).isEqualTo("CORE");
                    } catch (IllegalAccessException e) {
                        throw new AssertionError(e);
                    }
                });
    }

    @Test
    void domainExceptionCarriesStatusAndSlug() {
        var ex = new DomainException(CoreErrors.VERSION_MISMATCH, "conflict");
        assertThat(ex.httpStatus()).isEqualTo(412);
        assertThat(ex.slug()).isEqualTo("core-412-001");
    }
}
