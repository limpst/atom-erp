package com.atomeng.erp.fld.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** fld.defect_event 1행(서버 확정 이벤트) */
public record StoredEvent(long seq, UUID opId, UUID defectId, UUID roundId, UUID zoneId, String eventType,
                          JsonNode payload, String hlc, UUID deviceId, UUID actorId, boolean afterLock,
                          Instant receivedAt) {}
