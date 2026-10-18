package com.atomeng.common.events;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;

/** {@link IdempotentListener} 처리: processed_event INSERT … ON CONFLICT DO NOTHING → 0행이면 스킵. */
@Aspect
public class IdempotentListenerAspect {

    private final ProcessedEvents processed;

    public IdempotentListenerAspect(ProcessedEvents processed) {
        this.processed = processed;
    }

    @Around("@annotation(il)")
    public Object around(ProceedingJoinPoint pjp, IdempotentListener il) throws Throwable {
        Object[] args = pjp.getArgs();
        if (args.length == 0 || !(args[0] instanceof EventEnvelope env)) {
            throw new IllegalStateException("@IdempotentListener 메서드의 첫 인자는 EventEnvelope 여야 합니다");
        }
        if (!processed.markProcessed(il.consumer(), env.eventId())) {
            return null; // 이미 처리됨
        }
        return pjp.proceed();
    }
}
