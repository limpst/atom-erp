package com.atomeng.common.core.pii;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 개인정보·민감정보 표시 (설계 14-1, 00-IMPL Step 6). 감사 로그·애플리케이션 로그 직렬화 시
 * {@link PiiMasking#maskingMapper} 가 값을 가린다. API 응답 자체는 가리지 않는다(권한 있는 조회).
 */
@Target({ElementType.FIELD, ElementType.METHOD, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface Pii {

    Kind value() default Kind.GENERIC;

    enum Kind {
        /** 010-1234-5678 → 010-****-5678 */
        PHONE,
        /** user@example.com → u***@example.com */
        EMAIL,
        /** 홍길동 → 홍*동, 김철 → 김* */
        NAME,
        /** 주민·외국인등록번호 → 앞 6자리만 */
        RRN,
        /** 장애 여부 등 민감정보: 값 자체를 숨김 → "***" */
        GENERIC
    }
}
