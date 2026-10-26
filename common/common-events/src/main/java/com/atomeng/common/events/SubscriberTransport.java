package com.atomeng.common.events;

import java.util.List;

/**
 * 구독자 단위 전달을 지원하는 전송 (보안 검토 CMN-14). {@link OutboxRelay} 는 이 전송이면 이벤트를 구독자마다
 * 별도 트랜잭션으로 전달하고, 구독자별로 재시도 · DLQ 한다. 이 인터페이스가 아닌 {@link EventTransport} 는
 * 예전처럼 이벤트 1건 = 트랜잭션 1개(전체 구독자 함께)로 처리한다.
 */
public interface SubscriberTransport extends EventTransport {

    /** 이 이벤트를 받을 구독자(조건 일치분만, 실행 순서대로). 구독자 id 는 재기동 후에도 같아야 한다. */
    List<Subscriber> subscribersFor(EventEnvelope envelope);

    interface Subscriber {
        /** 안정된 구독자 id(@IdempotentListener.consumer 또는 빈 이름#메서드) — core.outbox_delivery.subscriber */
        String id();

        /** 호출자 트랜잭션 안에서 전달. 예외 = 이 구독자만 실패 */
        void deliver(EventEnvelope envelope) throws Exception;
    }
}
