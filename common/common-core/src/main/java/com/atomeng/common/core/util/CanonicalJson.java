package com.atomeng.common.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 키를 정렬한 정규(Canonical) JSON. 멱등 키 요청 해시가 JSON 키 순서·공백에 흔들리지 않게 한다
 * (00-IMPL Step 5 함정·팁). JSON이 아니면 원문 문자열을 그대로 쓴다.
 */
public final class CanonicalJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CanonicalJson() {}

    public static String canonicalize(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            JsonNode node = MAPPER.readTree(body);
            if (node == null || node.isMissingNode()) {
                return "";
            }
            return MAPPER.writeValueAsString(sort(node));
        } catch (IOException e) {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static JsonNode sort(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String n : names) {
                sorted.set(n, sort(node.get(n)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = MAPPER.createArrayNode();
            node.forEach(child -> arr.add(sort(child)));
            return arr;
        }
        return node;
    }
}
