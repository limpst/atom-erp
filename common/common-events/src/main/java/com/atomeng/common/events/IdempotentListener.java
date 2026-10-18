package com.atomeng.common.events;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 이벤트 리스너 멱등 가드 (00-IMPL Step 7). 첫 번째 인자가 {@link EventEnvelope} 인 메서드에 붙인다.
 * 이미 처리한 (consumer, eventId) 이면 메서드를 실행하지 않는다.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentListener {
    /** 소비자 이름 — 예: cst.budget-drafter */
    String consumer();
}
