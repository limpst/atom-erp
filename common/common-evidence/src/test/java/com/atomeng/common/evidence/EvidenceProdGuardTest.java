package com.atomeng.common.evidence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 보안 검토 CMN-07 — 운영 프로필에서 증거 저장소를 임시 폴더에 두면 기동 거부 */
class EvidenceProdGuardTest {

    static final String TMP = System.getProperty("java.io.tmpdir");

    @Test
    @DisplayName("CMN-07: prod + local-root 미설정(tmpdir 기본) · tmpdir 아래 경로 → 거부, 영구 경로 · 개발 프로필 → 허용")
    void refuseTmpdirInProd() {
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        String def = TMP + "/atom-evidence";
        assertThatThrownBy(() -> EvidenceAutoConfiguration.requireDurableRootInProd(prod, def, TMP))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("CMN-07");
        prod.setProperty("atom.evidence.local-root", def);
        assertThatThrownBy(() -> EvidenceAutoConfiguration.requireDurableRootInProd(prod, def, TMP))
                .isInstanceOf(IllegalStateException.class);
        String durable = java.nio.file.Path.of(TMP).getRoot().resolve("srv").resolve("atom-evidence").toString();
        prod.setProperty("atom.evidence.local-root", durable);
        assertThatCode(() -> EvidenceAutoConfiguration.requireDurableRootInProd(prod, durable, TMP))
                .doesNotThrowAnyException();
        assertThatCode(() -> EvidenceAutoConfiguration.requireDurableRootInProd(new MockEnvironment(), def, TMP))
                .doesNotThrowAnyException();
    }
}
