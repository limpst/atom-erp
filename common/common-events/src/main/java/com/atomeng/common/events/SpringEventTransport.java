package com.atomeng.common.events;

import org.springframework.context.ApplicationEventPublisher;

/**
 * 1단계 전송: 같은 JVM 의 @EventListener 에 동기 전달.
 * Relay 가 이벤트 1건마다 새 트랜잭션을 열고 그 안에서 send → published_at 갱신을 하므로,
 * 리스너의 DB 작업·processed_event INSERT 와 발행 표시는 함께 커밋되거나 함께 롤백된다.
 */
public class SpringEventTransport implements EventTransport {

    private final ApplicationEventPublisher publisher;

    public SpringEventTransport(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void send(EventEnvelope envelope) {
        publisher.publishEvent(envelope);
    }
}
