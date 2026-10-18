package com.atomeng.erp.fcd.domain.features;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 내부 업종 사전 — 코드 → 표시명, 카드사 업종명 · MCC → 내부 업종(설계 fcd.category_mapping 시드).
 * 값은 프로토타입 합성 세계의 CATEGORY_INFO / CATEGORY_FROM_HINT 내보내기(⚠️ 합성 예시, 실제 카드사 코드표 확인 필요).
 */
public final class CategoryCatalog {

    private final Map<String, String> names;
    private final Map<String, String> hints;

    public CategoryCatalog(Map<String, String> names, Map<String, String> hints) {
        this.names = Map.copyOf(names);
        this.hints = Map.copyOf(hints);
    }

    @SuppressWarnings("unchecked")
    public static CategoryCatalog fromClasspath() {
        Map<String, String> names = new LinkedHashMap<>();
        try (InputStream in = Objects.requireNonNull(CategoryCatalog.class.getResourceAsStream("/fcd/categories.csv"));
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
             InputStream h = Objects.requireNonNull(CategoryCatalog.class.getResourceAsStream("/fcd/category-hints.json"))) {
            r.lines().skip(1).filter(l -> !l.isBlank()).forEach(l -> {
                String[] c = l.split(",", -1);
                names.put(c[0], c[1]);
            });
            Map<String, String> hints = new ObjectMapper().readValue(h, Map.class);
            return new CategoryCatalog(names, hints);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Python CATEGORY_INFO.get(cat, (cat, ""))[0] */
    public String name(String code) {
        return names.getOrDefault(code, code);
    }

    public Optional<String> fromHint(String hint) {
        return Optional.ofNullable(hints.get(hint));
    }

    public Map<String, String> names() {
        return names;
    }
}
