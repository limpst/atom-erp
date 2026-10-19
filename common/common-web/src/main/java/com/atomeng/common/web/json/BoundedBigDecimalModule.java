package com.atomeng.common.web.json;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * 요청 본문의 BigDecimal 크기 제한 (보안 검토 2026-10-19, SEC-001).
 *
 * <p>{@code "1e999999999"} 처럼 짧은 문자열도 BigDecimal 로는 지수가 매우 커서, 이후 {@code setScale(0)} ·
 * {@code toBigInteger()} · {@code divide} 가 10^999,999,999 자리 정수를 만들며 CPU 를 수 분간 점유하거나 OOM 을
 * 일으킨다. 업무상 금액 · 요율 · 좌표 · 수량은 소수 이하 · 지수 모두 수십 자리를 넘지 않으므로, 역직렬화 단계에서
 * scale 과 정밀도를 제한해 400(본문 해석 불가)으로 거부한다. 원 단위 금액(10^15 이상)도 여유 있게 통과한다.
 */
public class BoundedBigDecimalModule extends SimpleModule {

    /** |scale| 상한 — 1e-50 ~ 1e+50 범위 밖 지수 거부 */
    public static final int MAX_ABS_SCALE = 50;
    /** 유효숫자 상한 */
    public static final int MAX_PRECISION = 64;

    public BoundedBigDecimalModule() {
        super("atom-bounded-bigdecimal");
        addDeserializer(BigDecimal.class, new Deserializer());
    }

    /** 범위 안이면 그대로, 아니면 예외 메시지 반환 (단위 시험용) */
    public static boolean withinBounds(BigDecimal v) {
        return Math.abs((long) v.scale()) <= MAX_ABS_SCALE && v.precision() <= MAX_PRECISION;
    }

    static final class Deserializer extends JsonDeserializer<BigDecimal> {

        @Override
        public BigDecimal deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            BigDecimal v;
            JsonToken t = p.currentToken();
            if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT) {
                v = p.getDecimalValue();
            } else if (t == JsonToken.VALUE_STRING) {
                String s = p.getText().trim();
                if (s.isEmpty()) {
                    return null;
                }
                if (s.length() > 200) {
                    return (BigDecimal) ctxt.handleWeirdStringValue(BigDecimal.class, s, "숫자 문자열이 너무 깁니다");
                }
                try {
                    v = new BigDecimal(s);
                } catch (NumberFormatException e) {
                    return (BigDecimal) ctxt.handleWeirdStringValue(BigDecimal.class, s, "숫자 형식이 아닙니다");
                }
            } else {
                return (BigDecimal) ctxt.handleUnexpectedToken(BigDecimal.class, p);
            }
            if (!withinBounds(v)) {
                return (BigDecimal) ctxt.handleWeirdNumberValue(BigDecimal.class, v,
                        "숫자 범위 초과(|scale| ≤ %d, 유효숫자 ≤ %d)".formatted(MAX_ABS_SCALE, MAX_PRECISION));
            }
            return v;
        }
    }
}
