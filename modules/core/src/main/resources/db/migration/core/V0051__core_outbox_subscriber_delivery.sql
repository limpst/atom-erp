-- =====================================================================
-- 보안 검토 CMN-14 (정책 권장안 적용) — outbox 구독자별 전달 · 재시도 · DLQ
--   예전: 이벤트 1건 = 트랜잭션 1개에 모든 구독자 → 한 구독자 실패가 다른 구독자 처리까지 롤백 · 함께 재시도 · 함께 DLQ.
--   지금: 구독자(@EventListener(EventEnvelope)) 마다 별도 트랜잭션. 성공한 구독자는 DONE 으로 남아 다시 전달하지 않고,
--         실패한 구독자만 10s → 20s → 40s → 80s 백오프 후 재시도, 5회째 실패면 그 구독자만 DEAD(구독자별 DLQ).
--   이벤트 행: 남은 RETRY 구독자 중 가장 이른 next_attempt_at 으로 다시 점유(FOR UPDATE SKIP LOCKED 그대로),
--             모두 DONE 이면 published_at, RETRY 없이 DEAD 가 있으면 core.outbox_dead 로 이동(재투입 시 DEAD 구독자만 재전달).
--   구독자 처리의 중복 제거는 그대로 core.processed_event(consumer, event_id) — 구독자 트랜잭션 안(MANDATORY).
-- =====================================================================
CREATE TABLE core.outbox_delivery (
  event_id        uuid NOT NULL,                 -- core.outbox_event.id(이동 후에는 core.outbox_dead.id) — FK 없음(행 이동)
  subscriber      varchar(200) NOT NULL,         -- @IdempotentListener.consumer, 없으면 빈 이름#메서드
  status          varchar(5) NOT NULL CHECK (status IN ('DONE','RETRY','DEAD')),
  attempts        int NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  next_attempt_at timestamptz,                   -- RETRY 일 때 다음 시도 시각
  last_error      text,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (event_id, subscriber),
  CHECK (status <> 'RETRY' OR next_attempt_at IS NOT NULL)
);
CREATE INDEX ix_outbox_delivery_dead ON core.outbox_delivery (updated_at) WHERE status = 'DEAD';

-- 구독자별 DLQ 조회용(운영자 확인 · 재투입 대상)
CREATE VIEW core.outbox_subscriber_dead AS
SELECT d.event_id, d.subscriber, d.attempts, d.last_error, d.updated_at AS dead_at,
       coalesce(e.event_type, x.event_type) AS event_type
  FROM core.outbox_delivery d
  LEFT JOIN core.outbox_event e ON e.id = d.event_id
  LEFT JOIN core.outbox_dead x ON x.id = d.event_id
 WHERE d.status = 'DEAD';
