package com.atomeng.common.core.pii;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.AnnotationIntrospector;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import java.io.IOException;

/** PII 마스킹 규칙과 마스킹 전용 ObjectMapper. */
public final class PiiMasking {

    private PiiMasking() {}

    public static String mask(Pii.Kind kind, Object value) {
        if (value == null) {
            return null;
        }
        String s = String.valueOf(value);
        return switch (kind) {
            case PHONE -> maskPhone(s);
            case EMAIL -> maskEmail(s);
            case NAME -> maskName(s);
            case RRN -> s.length() <= 6 ? "******" : s.substring(0, 6) + "-*******";
            case GENERIC -> "***";
        };
    }

    static String maskPhone(String s) {
        String digits = s.replaceAll("\\D", "");
        if (digits.length() < 8) {
            return "***";
        }
        return digits.substring(0, 3) + "-****-" + digits.substring(digits.length() - 4);
    }

    static String maskEmail(String s) {
        int at = s.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return s.charAt(0) + "***" + s.substring(at);
    }

    static String maskName(String s) {
        int[] cps = s.codePoints().toArray();
        if (cps.length <= 1) {
            return "*";
        }
        if (cps.length == 2) {
            return new String(cps, 0, 1) + "*";
        }
        return new String(cps, 0, 1) + "*".repeat(cps.length - 2) + new String(cps, cps.length - 1, 1);
    }

    /** base 를 복사해 @Pii 속성을 가리는 매퍼를 만든다(모듈·설정 유지). */
    public static ObjectMapper maskingMapper(ObjectMapper base) {
        ObjectMapper m = base.copy();
        AnnotationIntrospector existing = m.getSerializationConfig().getAnnotationIntrospector();
        m.setAnnotationIntrospector(AnnotationIntrospector.pair(new PiiIntrospector(), existing));
        return m;
    }

    private static final class PiiIntrospector extends JacksonAnnotationIntrospector {
        private static final long serialVersionUID = 1L;

        @Override
        public Object findSerializer(Annotated a) {
            Pii pii = a.getAnnotation(Pii.class);
            return pii == null ? null : new MaskingSerializer(pii.value());
        }
    }

    private static final class MaskingSerializer extends JsonSerializer<Object> {
        private final Pii.Kind kind;

        MaskingSerializer(Pii.Kind kind) {
            this.kind = kind;
        }

        @Override
        public void serialize(Object value, JsonGenerator gen, SerializerProvider sp) throws IOException {
            gen.writeString(mask(kind, value));
        }
    }
}
