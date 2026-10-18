package com.atomeng.common.events;

/**
 * 전송 추상화 (설계 5장 메시징: 1단계 Spring Event → 2단계 Redpanda).
 * 예외를 던지면 Relay 가 실패로 기록하고 지수 백오프 후 재시도한다.
 */
@FunctionalInterface
public interface EventTransport {
    void send(EventEnvelope envelope) throws Exception;
}
