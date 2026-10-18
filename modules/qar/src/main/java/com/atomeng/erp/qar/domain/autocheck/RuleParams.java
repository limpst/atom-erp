package com.atomeng.erp.qar.domain.autocheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** 규칙 파라미터(qar.autocheck_rule_def.params) 읽기 도우미 — 세트 버전마다 값이 다를 수 있다(BR-QAR-007) */
public record RuleParams(JsonNode node) {

    public static final RuleParams EMPTY = new RuleParams(JsonNodeFactory.instance.objectNode());

    public RuleParams {
        node = node == null ? JsonNodeFactory.instance.objectNode() : node;
    }

    public int integer(String key, int dflt) {
        return node.path(key).isNumber() ? node.path(key).asInt() : dflt;
    }

    public BigDecimal decimal(String key, BigDecimal dflt) {
        return node.path(key).isNumber() ? node.path(key).decimalValue() : dflt;
    }

    public List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        node.path(key).forEach(n -> out.add(n.asText()));
        return out;
    }

    public JsonNode get(String key) {
        return node.path(key);
    }
}
