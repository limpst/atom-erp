package com.atomeng.erp.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.atomeng.erp.core.application.AttachmentService;
import com.atomeng.erp.fld.application.FldProperties;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** 보안 검토 FLD-02b — 업로드 수치 한도 확정값(50MB · 세션 20 · 7일 · 첨부 50MB)과 설정 재정의 */
class FldUploadLimitsPolicyTest {

    static final long MB50 = 50L * 1024 * 1024;

    private static Binder applicationYml() throws Exception {
        List<PropertySource<?>> yml = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yml"));
        return new Binder(ConfigurationPropertySources.from(yml.get(0)));   // 첫 문서 = 기본(프로필 없음)
    }

    @Test
    @DisplayName("FLD-02b: 확정 기본값 — 사진 50MB · 사용자당 열린 세션 20 · 세션 7일 · 첨부 50MB (코드 기본값 = application.yml)")
    void defaultsAreFinal() throws Exception {
        FldProperties.Upload code = new FldProperties(null, null, null).upload();
        assertThat(code.maxFileBytes()).isEqualTo(MB50);
        assertThat(code.maxOpenSessionsPerUser()).isEqualTo(20);
        assertThat(code.sessionTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(code.maxChunkBytes()).isEqualTo(5 * 1024 * 1024);
        assertThat(AttachmentService.DEFAULT_MAX_BYTES).isEqualTo(MB50);

        Binder yml = applicationYml();
        FldProperties.Upload fromYml = yml.bind("atom.fld", FldProperties.class).get().upload();
        assertThat(fromYml).isEqualTo(code);
        assertThat(yml.bind("atom.attachment.max-bytes", Long.class).get()).isEqualTo(AttachmentService.DEFAULT_MAX_BYTES);
    }

    @Test
    @DisplayName("FLD-02b: 수치는 설정(atom.fld.upload.* · atom.attachment.max-bytes)으로 재정의 가능")
    void overridable() {
        Binder b = new Binder(new MapConfigurationPropertySource(Map.of(
                "atom.fld.upload.max-file-bytes", "104857600",
                "atom.fld.upload.max-open-sessions-per-user", "5",
                "atom.fld.upload.session-ttl", "3d",
                "atom.attachment.max-bytes", "10485760")));
        FldProperties.Upload u = b.bind("atom.fld", FldProperties.class).get().upload();
        assertThat(u.maxFileBytes()).isEqualTo(100L * 1024 * 1024);
        assertThat(u.maxOpenSessionsPerUser()).isEqualTo(5);
        assertThat(u.sessionTtl()).isEqualTo(Duration.ofDays(3));
        assertThat(u.maxChunkBytes()).isEqualTo(FldProperties.Upload.DEFAULT_MAX_CHUNK_BYTES);   // 지정 안 한 값은 기본
        assertThat(b.bind("atom.attachment.max-bytes", Long.class).get()).isEqualTo(10L * 1024 * 1024);
    }
}
