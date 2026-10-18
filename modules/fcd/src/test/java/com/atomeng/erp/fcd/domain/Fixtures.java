package com.atomeng.erp.fcd.domain;

import com.atomeng.erp.fcd.domain.rules.PyOps;
import com.atomeng.erp.fcd.domain.rules.RuleSet;
import com.atomeng.erp.fcd.domain.rules.RuleSetRegistry;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 테스트 픽스처 로더 — 패리티 JSON(NaN 허용) · 클래스패스 룰셋 */
public final class Fixtures {

    private Fixtures() {}

    public static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();

    public static final String[] RULESET_FILES = {"_draft_2026.2.0-cal.20261018.yaml", "_draft_2026.3.0-cal.20261018.yaml",
            "fcd-2025.2.0.yaml", "fcd-2026.1.0.yaml", "fcd-2026.2.0.yaml", "fcd-2026.3.0.yaml", "fcd-2026.4.0.yaml",
            "fcd-2026.5.0.yaml"};

    @SuppressWarnings("unchecked")
    public static Map<String, Object> json(String resource) {
        try (InputStream in = Objects.requireNonNull(Fixtures.class.getResourceAsStream(resource), resource)) {
            return (Map<String, Object>) PyOps.normalize(JSON.readValue(in, Map.class));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String text(String resource) {
        try (InputStream in = Objects.requireNonNull(Fixtures.class.getResourceAsStream(resource), resource)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 프로토타입 RuleSetRegistry.from_dir 와 같게: _draft* 는 항상 DRAFT */
    public static Map<String, RuleSet> rulesets() {
        Map<String, RuleSet> out = new TreeMap<>();
        for (String f : RULESET_FILES) {
            RuleSet rs = RuleSet.parse(text("/fcd/rulesets/" + f), "fcd");
            if (f.startsWith("_draft")) {
                rs.markDraft();
            }
            out.put(f, rs);
        }
        return out;
    }

    public static RuleSetRegistry registry() {
        RuleSetRegistry reg = new RuleSetRegistry();
        rulesets().values().forEach(reg::add);
        return reg;
    }
}
