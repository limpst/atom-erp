package com.atomeng.erp.rpt.domain;

import com.atomeng.common.core.util.Hashing;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 정규(Canonical) JSON — 스냅샷 · 등급 결과 · 본문 해시의 재현성(NFR-RPT-03, 06-IMPL Step 3 팁).
 * 키 정렬, 소수는 BigDecimal(0.30 = 0.3, 지수 표기 금지), 날짜는 ISO 문자열. double 은 쓰지 않는다.
 */
public final class RptJson {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** 읽기 · 변환용(소수 → BigDecimal) */
    public static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private RptJson() {}

    public static JsonNode tree(Object value) {
        return MAPPER.valueToTree(value);
    }

    public static byte[] canonicalBytes(Object value) {
        JsonNode n = value instanceof JsonNode j ? j : tree(value);
        try {
            return MAPPER.writeValueAsString(normalize(n)).getBytes(StandardCharsets.UTF_8);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(Object value) {
        return Hashing.sha256Hex(canonicalBytes(value));
    }

    /** 블록 텍스트 해시(anchors.json textHash · rpt.block.text_hash): NFC · 공백 정리 후 SHA-256 */
    public static String textHash(String text) {
        String t = text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFC).replaceAll("\s+", " ").strip();
        return Hashing.sha256Hex(t);
    }

    public static JsonNode normalize(JsonNode n) {
        if (n == null || n.isNull()) {
            return NODES.nullNode();
        }
        if (n.isObject()) {
            ObjectNode out = NODES.objectNode();
            List<String> names = new ArrayList<>();
            n.properties().forEach(e -> names.add(e.getKey()));
            Collections.sort(names);
            for (String k : names) {
                out.set(k, normalize(n.get(k)));
            }
            return out;
        }
        if (n.isArray()) {
            ArrayNode out = NODES.arrayNode();
            n.forEach(c -> out.add(normalize(c)));
            return out;
        }
        if (n.isNumber() && !n.isIntegralNumber()) {
            return NODES.numberNode(plain(n.decimalValue()));
        }
        if (n.isBigInteger() || n.isLong() || n.isInt()) {
            return NODES.numberNode(new BigDecimal(n.bigIntegerValue()));
        }
        return n;
    }

    public static BigDecimal plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        return s.scale() < 0 ? s.setScale(0) : s;
    }
}
