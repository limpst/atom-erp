package com.atomeng.common.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {

    private static String c(String s) {
        return CanonicalJson.canonicalize(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Step5 함정·팁: 키 순서·공백이 달라도 같은 정규형 → 같은 요청 해시")
    void keyOrderIndependent() {
        String a = c("{\"name\":\"합성고객\",\"clientType\":\"PRIVATE\",\"tags\":[{\"b\":1,\"a\":2}]}");
        String b = c("{ \"tags\":[{\"a\":2,\"b\":1}], \"clientType\":\"PRIVATE\", \"name\":\"합성고객\" }");
        assertThat(a).isEqualTo(b);
        assertThat(Hashing.sha256Hex(a)).isEqualTo(Hashing.sha256Hex(b)).hasSize(64);
    }

    @Test
    @DisplayName("배열 순서·값이 다르면 다른 정규형")
    void valuesMatter() {
        assertThat(c("{\"a\":[1,2]}")).isNotEqualTo(c("{\"a\":[2,1]}"));
    }

    @Test
    void emptyAndNonJson() {
        assertThat(CanonicalJson.canonicalize(new byte[0])).isEmpty();
        assertThat(c("not json")).isEqualTo("not json");
    }
}
