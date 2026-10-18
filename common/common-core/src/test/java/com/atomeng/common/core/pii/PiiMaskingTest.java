package com.atomeng.common.core.pii;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PiiMaskingTest {

    record Contact(String id, @Pii(Pii.Kind.NAME) String name, @Pii(Pii.Kind.PHONE) String phone,
                   @Pii(Pii.Kind.EMAIL) String email, @Pii boolean disabledWorker) {}

    @ParameterizedTest(name = "{0}: {1} → {2}")
    @CsvSource({
            "PHONE, 010-1234-5678, 010-****-5678",
            "PHONE, 01012345678,   010-****-5678",
            "EMAIL, synthetic.user@example.com, s***@example.com",
            "NAME,  홍길동, 홍*동",
            "NAME,  김철, 김*",
            "NAME,  남궁합성, 남**성",
            "RRN,   900101-1234567, 900101-*******",
            "GENERIC, true, ***"
    })
    @DisplayName("Step6: PII 마스킹 규칙 (설계 14-1, 00-IMPL Step6 010-****-5678)")
    void rules(Pii.Kind kind, String raw, String masked) {
        assertThat(PiiMasking.mask(kind, raw)).isEqualTo(masked);
    }

    @Test
    @DisplayName("Step6: maskingMapper 는 @Pii 레코드 컴포넌트만 가리고 원 매퍼는 그대로")
    void mapper() {
        var base = new ObjectMapper();
        var c = new Contact("C-1", "홍길동", "010-9876-5432", "hong@example.com", true);
        var masked = PiiMasking.maskingMapper(base).valueToTree(c);
        assertThat(masked.get("id").asText()).isEqualTo("C-1");
        assertThat(masked.get("name").asText()).isEqualTo("홍*동");
        assertThat(masked.get("phone").asText()).isEqualTo("010-****-5432");
        assertThat(masked.get("email").asText()).isEqualTo("h***@example.com");
        assertThat(masked.get("disabledWorker").asText()).isEqualTo("***");
        assertThat(masked.toString()).doesNotContain("홍길동", "9876", "hong@");
        assertThat(base.valueToTree(c).get("name").asText()).isEqualTo("홍길동");
    }
}
