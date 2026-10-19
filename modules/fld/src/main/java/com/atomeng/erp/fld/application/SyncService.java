package com.atomeng.erp.fld.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.core.api.AttachmentIngestApi;
import com.atomeng.erp.eqp.api.CalibrationValidityPort;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import com.atomeng.erp.eqp.api.MeasurementTracePort;
import com.atomeng.erp.fac.api.FacQueryApi;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc.Round;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc.Zone;
import com.atomeng.erp.fld.api.FldEvents;
import com.atomeng.erp.fld.domain.DefectState;
import com.atomeng.erp.fld.domain.DefectTypes;
import com.atomeng.erp.fld.domain.Hlc;
import com.atomeng.erp.fld.domain.StoredEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 오프라인 동기화 프로토콜 서버 (설계 B4 · B5.1 · B7.2, 05-IMPL Step 10 · 11 · 13 일부 · 14 일부).
 * <h3>push</h3>
 * 회차 단위 advisory lock 으로 직렬화한 한 트랜잭션에서 op 를 HLC 순(요청 순)으로 처리한다. op 별 판정:
 * <ul>
 *   <li>DUPLICATE — 같은 clientOpId 가 이미 처리됨(이벤트 중복 0, TC-FLD-002). CONFLICT · REJECTED 였던 op 는 같은 판정을 다시 돌려준다</li>
 *   <li>REJECTED — 형식 · 권한 · 잠금(BR-FLD-009) · 위치 노드(fac::api) · 장비 미기재 등. 이벤트로 저장하지 않는다</li>
 *   <li>CONFLICT — 측정값(MANUAL) · 기하(5분) · 삭제 뒤 측정(REMOVE_WINS) 필드에 clientLastPulledSeq(또는 op.baseSeq) 이후
 *       다른 기기 변경이 있으면 sync_conflict 를 만들고 이벤트는 저장하지 않는다(TC-FLD-003). 그 외 필드는 HLC LWW 로 자동 수렴(TC-FLD-004)</li>
 *   <li>ACCEPTED — defect_event INSERT + 같은 트랜잭션에서 프로젝션 갱신. 측정값은 eqp::api 로 측정일 기준 재검증 →
 *       무효면 provisional(잠정) 로 저장하고 attrs 에 반영하지 않는다(BR-FLD-008, TC-FLD-014), 유효면 eqp 측정 추적 행</li>
 * </ul>
 * <h3>pull</h3>
 * change token(또는 sinceSeq) 이후 다른 기기 이벤트를 seq 순으로, 가시성 지연 창 이전 것만 돌려준다. 재업로드 지시 ·
 * 내 미해결 충돌을 함께 싣는다.
 */
@Service
public class SyncService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    static final String SERVER_NODE = "srv";
    /** 서버가 만드는 이벤트(구역 잠금 검수 · AI 제안 첨부)의 기기 id */
    public static final UUID SERVER_DEVICE = UUID.fromString("00000000-0000-7000-8000-00000000f1d0");
    static final Set<String> CLIENT_EVENTS = Set.of("DefectCreated", "DefectGeometryChanged", "DefectClassified",
            "DefectMeasured", "DefectAttributesSet", "DefectMemberLinked", "PhotoAttached", "PhotoDetached",
            "VoiceMemoAttached", "AiSuggestionDecided", "DefectRemoved");
    static final Set<String> RECORDED_LIKE = Set.of("RECORDED", "AI_SUGGESTED", "VERIFIED");

    public record PushOp(UUID opId, UUID defectId, String eventType, String hlc, Long baseSeq,
                         String lockOverrideReason, JsonNode payload) {}

    public record PushRequest(UUID deviceId, UUID roundId, long clientLastPulledSeq, List<PushOp> ops) {}

    public record Resolve(UUID opId, UUID deviceId, String hlc, String resolution, JsonNode merged) {}

    private final FldJdbc fld;
    private final FldAccess access;
    private final FldProperties props;
    private final FacQueryApi fac;
    private final CalibrationValidityPort calibration;
    private final MeasurementTracePort traces;
    private final AttachmentIngestApi attachments;
    private final AiDiscrepancyCheck discrepancy;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final Clock clock;
    private final TransactionTemplate isolatedRead;

    public SyncService(FldJdbc fld, FldAccess access, FldProperties props, FacQueryApi fac,
                       CalibrationValidityPort calibration, MeasurementTracePort traces, AttachmentIngestApi attachments,
                       AiDiscrepancyCheck discrepancy, OutboxPublisher outbox, CurrentActor actor, Clock clock,
                       PlatformTransactionManager txManager) {
        this.fld = fld;
        this.access = access;
        this.props = props;
        this.fac = fac;
        this.calibration = calibration;
        this.traces = traces;
        this.attachments = attachments;
        this.discrepancy = discrepancy;
        this.outbox = outbox;
        this.actor = actor;
        this.clock = clock;
        this.isolatedRead = new TransactionTemplate(txManager);
        this.isolatedRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.isolatedRead.setReadOnly(true);
    }

    // ================================================================== push

    /** 배치 안에서 결함별 상태 캐시(리플레이 1회) + 배치 시작 시점 상태(fld.defect.recorded 전이 판정) */
    private final class Batch {
        final Round round;
        final UUID deviceId;
        final UUID actorId;
        final long since;
        final Instant now;
        final Map<UUID, DefectState> states = new LinkedHashMap<>();
        final Map<UUID, String[]> before = new LinkedHashMap<>();   // [status, locationNodeId]
        final Map<UUID, Map<String, Object>> results = new LinkedHashMap<>();   // opId → 첫 판정(배치 안 중복 판정용)
        final List<Map<String, Object>> ordered = new ArrayList<>();          // 요청 순서 그대로의 응답
        final List<Object[]> opRows = new ArrayList<>();
        final Set<UUID> touched = new LinkedHashSet<>();
        long fromSeq = Long.MAX_VALUE;
        long toSeq = -1;
        int accepted;
        int duplicates;
        int conflicts;
        int rejected;

        Batch(Round round, UUID deviceId, UUID actorId, long since, Instant now) {
            this.round = round;
            this.deviceId = deviceId;
            this.actorId = actorId;
            this.since = since;
            this.now = now;
        }

        DefectState state(UUID defectId) {
            return states.computeIfAbsent(defectId, id -> {
                DefectState s = DefectState.replay(id, fld.events(id));
                before.put(id, new String[] {s.created() ? s.status() : null,
                        s.locationNodeId() == null ? null : s.locationNodeId().toString()});
                return s;
            });
        }
    }

    /** op 단위 거부 — 이벤트 저장 없이 REJECTED(트랜잭션은 계속) */
    private static final class Reject extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final String reason;

        Reject(String reason, String message) {
            super(message, null, false, false);
            this.reason = reason;
        }
    }

    @Transactional
    public Map<String, Object> push(PushRequest req) {
        if (req.ops() == null || req.ops().isEmpty()) {
            throw new DomainException(FldErrors.INVALID, "ops 가 비어 있습니다");
        }
        if (req.ops().size() > props.sync().maxOps()) {
            throw new DomainException(FldErrors.INVALID, "push 배치 상한 " + props.sync().maxOps() + "건 초과");
        }
        Round round = fld.round(req.roundId());
        access.requireProject("fld:defect:update", round.projectId());
        fld.jdbc().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "fld.round:" + round.id());
        Instant now = clock.instant();
        Batch b = new Batch(round, req.deviceId(), actor.actorIdOrSystem(), req.clientLastPulledSeq(), now);
        long maxDrift = 0;
        UUID batchId = UuidV7.next();
        for (PushOp op : req.ops()) {
            if (op.opId() == null) {
                throw new DomainException(FldErrors.INVALID, "opId(clientOpId) 는 필수입니다");
            }
            if (Hlc.valid(op.hlc())) {
                long d = Hlc.parse(op.hlc()).wallMs() - now.toEpochMilli();
                if (Math.abs(d) > Math.abs(maxDrift)) {
                    maxDrift = d;
                }
            }
            Map<String, Object> r = processOp(b, op);
            b.results.putIfAbsent(op.opId(), r);
            b.ordered.add(r);
        }
        // op 로그(배치 → op) — 재전송 멱등 응답의 원천
        fld.jdbc().update("""
                INSERT INTO fld.sync_batch(id, round_id, device_id, actor_id, client_last_pulled_seq, op_count, accepted,
                                           duplicates, conflicts, rejected, clock_drift_ms, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", batchId, round.id(), req.deviceId(), b.actorId,
                req.clientLastPulledSeq(), req.ops().size(), b.accepted, b.duplicates, b.conflicts, b.rejected, maxDrift,
                Timestamp.from(now));
        for (Object[] row : b.opRows) {
            row[1] = batchId;
            fld.jdbc().update("""
                    INSERT INTO fld.sync_op(op_id, batch_id, round_id, defect_id, device_id, actor_id, event_type, hlc,
                                            status, seq, conflict_id, reason, result, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", row);
        }
        flush(b);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("batchId", batchId);
        out.put("results", b.ordered);
        out.put("serverTime", OffsetDateTime.ofInstant(now, SEOUL));
        out.put("serverHlc", new Hlc(now.toEpochMilli(), 0, SERVER_NODE).pack());
        out.put("hasMoreToPull", fld.jdbc().queryForObject("""
                SELECT EXISTS (SELECT 1 FROM fld.defect_event WHERE round_id = ? AND seq > ? AND device_id <> ?)""",
                Boolean.class, round.id(), req.clientLastPulledSeq(), req.deviceId()));
        List<String> warnings = new ArrayList<>();
        if (Math.abs(maxDrift) > props.sync().clockDriftWarn().toMillis()) {
            warnings.add("CLOCK_DRIFT");   // A8-5 · TC-FLD-015 — 증거 모드는 서버 · TSA 시각만 법적 기준
        }
        out.put("clockDriftMs", maxDrift);
        out.put("warnings", warnings);
        return out;
    }

    private Map<String, Object> processOp(Batch b, PushOp op) {
        Map<String, Object> inBatch = b.results.get(op.opId());
        if (inBatch != null) {   // 같은 배치 안 중복
            b.duplicates++;
            return replay(inBatch);
        }
        List<Map<String, Object>> prior = fld.jdbc().queryForList("SELECT status, seq, result::text AS result, actor_id, "
                + "round_id FROM fld.sync_op WHERE op_id = ?", op.opId());
        if (!prior.isEmpty()) {
            // SEC-2026-10-19: opId 는 전역 PK — 다른 사용자 · 다른 회차의 op 결과를 재반환하지 않는다(결과 노출 방지)
            if (!b.actorId.equals(prior.get(0).get("actor_id")) || !b.round.id().equals(prior.get(0).get("round_id"))) {
                b.rejected++;
                return opIdInUse(op);
            }
            b.duplicates++;
            Map<String, Object> r = new LinkedHashMap<>(asMap(fld.tree((String) prior.get(0).get("result"))));
            return replay(r);
        }
        List<Map<String, Object>> evs = fld.jdbc().queryForList(
                "SELECT seq, actor_id, round_id FROM fld.defect_event WHERE op_id = ?", op.opId());
        if (!evs.isEmpty() && (!b.actorId.equals(evs.get(0).get("actor_id"))
                || !b.round.id().equals(evs.get(0).get("round_id")))) {
            b.rejected++;
            return opIdInUse(op);
        }
        List<Long> seqs = evs.stream().map(e -> ((Number) e.get("seq")).longValue()).toList();
        if (!seqs.isEmpty()) {   // 충돌 해결 op 등 sync_op 없이 저장된 이벤트
            b.duplicates++;
            return replay(result(op, "ACCEPTED", seqs.get(0)));
        }
        try {
            Map<String, Object> r = accept(b, op);
            b.accepted++;
            log(b, op, "ACCEPTED", (Long) r.get("seq"), null, null, r);
            return r;
        } catch (ConflictRaised c) {
            b.conflicts++;
            log(b, op, "CONFLICT", null, c.conflictId, null, c.result);
            return c.result;
        } catch (Reject x) {
            b.rejected++;
            Map<String, Object> r = result(op, "REJECTED", null);
            r.put("reason", x.reason);
            r.put("message", x.getMessage());
            log(b, op, "REJECTED", null, null, x.reason, r);
            return r;
        }
    }

    /** SEC-2026-10-19: 미래 HLC 거부 기준(서버 시각 + maxFutureSkew) */
    private boolean tooFarInFuture(String hlc, Instant now) {
        return Hlc.parse(hlc).wallMs() > now.plus(props.sync().maxFutureSkew()).toEpochMilli();
    }

    private static Map<String, Object> replay(Map<String, Object> original) {
        Map<String, Object> r = new LinkedHashMap<>(original);
        if ("ACCEPTED".equals(r.get("status"))) {
            r.put("status", "DUPLICATE");
        }
        r.put("replayed", true);
        return r;
    }

    /** 남의 opId 와 겹침 — 저장 결과 없이 거부만 알린다(op 로그 PK 가 같아 기록하지 않음) */
    private static Map<String, Object> opIdInUse(PushOp op) {
        Map<String, Object> r = result(op, "REJECTED", null);
        r.put("reason", "OP_ID_IN_USE");
        r.put("message", "이미 다른 사용자 · 회차에서 사용된 opId 입니다 — 새 opId 로 다시 보내십시오");
        return r;
    }

    private static Map<String, Object> result(PushOp op, String status, Long seq) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("opId", op.opId());
        r.put("defectId", op.defectId());
        r.put("status", status);
        if (seq != null) {
            r.put("seq", seq);
        }
        return r;
    }

    private void log(Batch b, PushOp op, String status, Long seq, UUID conflictId, String reason,
                     Map<String, Object> result) {
        b.opRows.add(new Object[] {op.opId(), null, b.round.id(), op.defectId(), b.deviceId, b.actorId,
                op.eventType() == null ? null : truncate(op.eventType(), 40),
                op.hlc() == null ? null : truncate(op.hlc(), 64), status, seq, conflictId,
                reason == null ? null : truncate(reason, 60), fld.json(result), Timestamp.from(b.now)});
    }

    private static String truncate(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(JsonNode n) {
        return fld.om().convertValue(n, Map.class);
    }

    private static final class ConflictRaised extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final transient UUID conflictId;
        final transient Map<String, Object> result;

        ConflictRaised(UUID conflictId, Map<String, Object> result) {
            super(null, null, false, false);
            this.conflictId = conflictId;
            this.result = result;
        }
    }

    /** 검증 → 충돌 판정 → (측정) 장비 재검증 → 이벤트 저장 → 상태 적용 · 부속 테이블 */
    private Map<String, Object> accept(Batch b, PushOp op) {
        String type = op.eventType();
        if (type == null || !CLIENT_EVENTS.contains(type)) {
            throw new Reject("UNKNOWN_EVENT_TYPE", "앱이 보낼 수 없는 이벤트: " + type);
        }
        if (!Hlc.valid(op.hlc())) {
            throw new Reject("INVALID_HLC", "HLC 형식 오류(wallMs16:counter4:node): " + op.hlc());
        }
        if (tooFarInFuture(op.hlc(), b.now)) {
            throw new Reject("HLC_IN_FUTURE", "기기 시계가 서버보다 " + props.sync().maxFutureSkew() + " 넘게 앞섭니다: " + op.hlc());
        }
        if (op.defectId() == null || op.payload() == null || !op.payload().isObject()) {
            throw new Reject("INVALID_PAYLOAD", "defectId · payload(object) 필수");
        }
        JsonNode p = op.payload();
        if (p.has("_server")) {
            throw new Reject("INVALID_PAYLOAD", "_server 는 서버 예약 필드입니다");
        }
        DefectState s = b.state(op.defectId());
        Zone zone;
        if ("DefectCreated".equals(type)) {
            if (s.created()) {
                throw new Reject("DEFECT_EXISTS", "이미 생성된 결함 id 입니다");
            }
            UUID zoneId = uuidField(p, "zoneId");
            zone = zoneId == null ? null : fld.findZone(zoneId).orElse(null);
            if (zone == null || !zone.roundId().equals(b.round.id())) {
                throw new Reject("UNKNOWN_ZONE", "회차의 구역이 아닙니다: " + zoneId);
            }
        } else {
            if (!s.created()) {
                throw new Reject("UNKNOWN_DEFECT", "결함이 없습니다(DefectCreated 먼저): " + op.defectId());
            }
            if (!s.roundId().equals(b.round.id())) {
                throw new Reject("ROUND_MISMATCH", "다른 회차의 결함입니다");
            }
            zone = s.zoneId() == null ? null : fld.findZone(s.zoneId()).orElse(null);
        }
        boolean pm = access.isPm();
        boolean afterLock = false;
        if (zone != null && zone.locked()) {
            if (!pm) {
                throw new Reject("ZONE_LOCKED", "잠긴 구역은 PM 만 수정할 수 있습니다(BR-FLD-009)");
            }
            if (op.lockOverrideReason() == null || op.lockOverrideReason().trim().length() < 5) {
                throw new Reject("LOCK_REASON_REQUIRED", "잠긴 구역 수정은 사유(5자 이상)가 필요합니다(BR-FLD-009)");
            }
            afterLock = true;
        }
        if (!pm && zone != null && !b.actorId.equals(zone.assigneeId())
                && !(s.created() && b.actorId.equals(s.createdBy()))) {
            throw new Reject("NOT_ASSIGNED", "ENG 는 배정 구역 · 본인 작성 결함만 수정할 수 있습니다(A2)");
        }

        ObjectNode stored = p.deepCopy();
        ObjectNode server = stored.putObject("_server");
        if (afterLock) {
            server.put("lockOverrideReason", op.lockOverrideReason().trim());
        }
        Map<String, Object> extra = new LinkedHashMap<>();
        Runnable after = () -> { };
        switch (type) {
            case "DefectCreated" -> {
                String typeCode = p.path("typeCode").asText(null);
                if (!DefectTypes.known(typeCode)) {
                    throw new Reject("UNKNOWN_TYPE_CODE", "결함 유형 코드(A1.3) 아님: " + typeCode);
                }
                requireGeom(p.path("geomNorm"));
                checkNode(b.round, uuidField(p, "locationNodeId"));
                String code = fld.nextCode(b.round.id(), typeCode);
                server.put("code", code);
                extra.put("code", code);
            }
            case "DefectGeometryChanged" -> {
                requireGeom(p.path("geomNorm"));
                detectGeometryConflict(b, op, s);
            }
            case "DefectClassified" -> {
                if (!DefectTypes.known(p.path("typeCode").asText(null))) {
                    throw new Reject("UNKNOWN_TYPE_CODE", "결함 유형 코드(A1.3) 아님: " + p.path("typeCode").asText());
                }
            }
            case "DefectMemberLinked" -> {
                UUID node = uuidField(p, "locationNodeId");
                if (node == null) {
                    throw new Reject("INVALID_PAYLOAD", "locationNodeId 필수");
                }
                checkNode(b.round, node);
            }
            case "DefectAttributesSet" -> {
                JsonNode fields = p.path("fields");
                if (!fields.isObject() || fields.isEmpty()) {
                    throw new Reject("INVALID_PAYLOAD", "fields(object) 필수");
                }
                fields.fieldNames().forEachRemaining(f -> {
                    if (!DefectTypes.ATTRIBUTES.contains(f)) {
                        throw new Reject("UNKNOWN_ATTRIBUTE", DefectTypes.MEASURE_ITEMS.contains(f)
                                ? "측정값은 DefectMeasured 로만 기록합니다: " + f : "알 수 없는 속성: " + f);
                    }
                });
            }
            case "DefectMeasured" -> after = measured(b, op, s, server, extra);
            case "PhotoAttached" -> after = photoAttached(b, op, s);
            case "PhotoDetached" -> {
                if (uuidField(p, "photoId") == null) {
                    throw new Reject("INVALID_PAYLOAD", "photoId 필수");
                }
            }
            case "VoiceMemoAttached" -> after = voiceMemo(b, op, s);
            case "AiSuggestionDecided" -> after = aiDecided(b, op, s, extra);
            case "DefectRemoved" -> {
                String reason = p.path("reason").asText("");
                if (reason.trim().length() < 2) {
                    throw new Reject("REMOVE_REASON_REQUIRED", "삭제 사유 필수(BR-FLD-006)");
                }
                if (!pm && !(b.actorId.equals(s.createdBy()) && createdWithin24h(s))) {
                    throw new Reject("REMOVE_NOT_ALLOWED", "ENG 는 본인 작성 24시간 이내 결함만 삭제할 수 있습니다(A2)");
                }
            }
            default -> { }
        }
        if (server.isEmpty()) {
            stored.remove("_server");
        }
        UUID zoneId = zone == null ? null : zone.id();
        long seq = fld.appendEvent(op.opId(), op.defectId(), b.round.id(), zoneId, type, stored, op.hlc(), b.deviceId,
                b.actorId, afterLock, b.now);
        StoredEvent e = new StoredEvent(seq, op.opId(), op.defectId(), b.round.id(), zoneId, type, stored, op.hlc(),
                b.deviceId, b.actorId, afterLock, b.now);
        s.apply(e);
        b.touched.add(op.defectId());
        b.fromSeq = Math.min(b.fromSeq, seq);
        b.toSeq = Math.max(b.toSeq, seq);
        if (zone != null && "NOT_STARTED".equals(zone.status())) {
            fld.jdbc().update("UPDATE fld.zone_assignment SET status = 'IN_PROGRESS', version = version + 1 "
                    + "WHERE id = ? AND status = 'NOT_STARTED'", zone.id());
        }
        after.run();
        if ("AiSuggestionDecided".equals(type)) {
            discrepancy.check(s, uuidField(p, "suggestionId"), b.round.projectId());
        }
        Map<String, Object> r = result(op, "ACCEPTED", seq);
        r.putAll(extra);
        r.put("defectVersion", s.version());
        r.put("defectStatus", s.status());
        if (afterLock) {
            r.put("afterLock", true);
        }
        return r;
    }

    private boolean createdWithin24h(DefectState s) {
        List<StoredEvent> ev = fld.events(s.id());
        return !ev.isEmpty() && Duration.between(ev.get(0).receivedAt(), clock.instant()).toHours() < 24;
    }

    private static UUID uuidField(JsonNode p, String f) {
        if (!p.hasNonNull(f)) {
            return null;
        }
        try {
            return UUID.fromString(p.get(f).asText());
        } catch (IllegalArgumentException e) {
            throw new Reject("INVALID_PAYLOAD", f + " 는 UUID 여야 합니다");
        }
    }

    /** 정규화 좌표(B2: u, v ∈ [0,1]) — Point · LineString · Polygon */
    private static void requireGeom(JsonNode g) {
        String t = g.path("type").asText("");
        JsonNode coords = g.path("coords");
        if (!Set.of("Point", "LineString", "Polygon").contains(t) || !coords.isArray() || coords.isEmpty()) {
            throw new Reject("INVALID_GEOMETRY", "geomNorm 은 {type: Point|LineString|Polygon, coords: [[u,v]...]}");
        }
        List<JsonNode> pts = new ArrayList<>();
        if ("Point".equals(t) && coords.size() == 2 && coords.get(0).isNumber()) {
            pts.add(coords);
        } else {
            coords.forEach(pts::add);
        }
        for (JsonNode pt : pts) {
            if (!pt.isArray() || pt.size() != 2) {
                throw new Reject("INVALID_GEOMETRY", "좌표는 [u, v]");
            }
            for (JsonNode c : pt) {
                if (!c.isNumber() || c.asDouble() < 0 || c.asDouble() > 1) {
                    throw new Reject("INVALID_GEOMETRY", "정규화 좌표는 0~1 (도면 시트 기준)");
                }
            }
        }
    }

    /** BR-FLD-001: 위치 노드는 fac::api 로 확인 — 회차 건물의 살아 있는 노드만 */
    private void checkNode(Round round, UUID nodeId) {
        if (nodeId == null) {
            return;
        }
        Optional<FacQueryApi.LocationNode> n = fac.locationNode(nodeId);
        if (n.isEmpty() || !n.get().buildingId().equals(round.buildingId())) {
            throw new Reject("UNKNOWN_LOCATION_NODE", "회차 건물의 위치 노드가 아닙니다: " + nodeId);
        }
        if (n.get().deleted()) {
            throw new Reject("LOCATION_NODE_DELETED", "삭제된 위치 노드입니다(대체 노드: " + n.get().replacedById() + ")");
        }
    }

    // ------------------------------------------------------------------ 충돌 (B7.2)

    private long since(Batch b, PushOp op) {
        return op.baseSeq() != null ? Math.max(op.baseSeq(), b.since) : b.since;
    }

    private void detectGeometryConflict(Batch b, PushOp op, DefectState s) {
        List<Map<String, Object>> other = fld.jdbc().queryForList("""
                SELECT seq, hlc, device_id, payload->'geomNorm' AS v FROM fld.defect_event
                 WHERE defect_id = ? AND seq > ? AND device_id <> ?
                   AND event_type IN ('DefectGeometryChanged','DefectCreated')
                 ORDER BY seq DESC LIMIT 1""", op.defectId(), since(b, op), b.deviceId);
        if (other.isEmpty()) {
            return;
        }
        Map<String, Object> o = other.get(0);
        long gap = Hlc.parse((String) o.get("hlc")).distanceMillis(Hlc.parse(op.hlc()));
        if (gap <= props.sync().geometryConflictWindow().toMillis()) {
            raiseConflict(b, op, s, "geom", "GEOM_5MIN", op.payload().path("geomNorm"), s.geomNorm(), o);
        }
    }

    private void raiseConflict(Batch b, PushOp op, DefectState s, String field, String strategy, JsonNode local,
                               JsonNode serverValue, Map<String, Object> serverEvent) {
        UUID id = UuidV7.next();
        fld.jdbc().update("""
                INSERT INTO fld.sync_conflict(id, round_id, zone_id, defect_id, field, strategy, local_op_id,
                       local_event_type, local_payload, local_value, local_hlc, local_device_id, local_actor_id,
                       server_seq, server_value, server_hlc, server_device_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""",
                id, b.round.id(), s.zoneId(), s.id(), field, strategy, op.opId(), op.eventType(), fld.json(op.payload()),
                fld.json(local), op.hlc(), b.deviceId, b.actorId, ((Number) serverEvent.get("seq")).longValue(),
                fld.json(serverValue), serverEvent.get("hlc"), serverEvent.get("device_id"), Timestamp.from(b.now));
        Map<String, Object> r = result(op, "CONFLICT", null);
        r.put("conflictId", id);
        r.put("field", field);
        r.put("strategy", strategy);
        r.put("localValue", local);
        r.put("serverValue", serverValue);
        r.put("serverHlc", serverEvent.get("hlc"));
        r.put("serverSeq", serverEvent.get("seq"));
        throw new ConflictRaised(id, r);
    }

    // ------------------------------------------------------------------ 측정값 (BR-FLD-003 · 008 · 011)

    private Runnable measured(Batch b, PushOp op, DefectState s, ObjectNode server, Map<String, Object> extra) {
        JsonNode p = op.payload();
        String item = p.path("item").asText("");
        String method = p.path("method").asText("");
        if (!DefectTypes.MEASURE_ITEMS.contains(item)) {
            throw new Reject("UNKNOWN_MEASURE_ITEM", "측정 항목 아님: " + item);
        }
        if (!p.path("value").isNumber() || p.path("value").decimalValue().signum() < 0) {
            throw new Reject("INVALID_PAYLOAD", "value 는 0 이상 숫자");
        }
        if (p.path("unit").asText("").isBlank()) {
            throw new Reject("INVALID_PAYLOAD", "unit 필수");
        }
        if (!DefectTypes.METHODS.contains(method)) {
            throw new Reject("UNKNOWN_METHOD", "측정 방법(BR-FLD-003) 아님: " + method);
        }
        UUID equipmentId = uuidField(p, "equipmentId");
        if (DefectTypes.EQUIPMENT_METHODS.contains(method) && equipmentId == null) {
            throw new Reject("EQUIPMENT_REQUIRED", "장비 측정 · 게이지 값은 equipmentId 가 필요합니다(FR-FLD-010)");
        }
        OffsetDateTime measuredAt;
        try {
            measuredAt = OffsetDateTime.parse(p.path("measuredAt").asText(""));
        } catch (DateTimeParseException ex) {
            throw new Reject("INVALID_PAYLOAD", "measuredAt 은 ISO-8601 오프셋 시각");
        }
        BigDecimal value = p.path("value").decimalValue();
        // 충돌: 삭제 뒤 측정(REMOVE_WINS 예외) → 측정값 수동 충돌(MANUAL)
        if (s.removed() && Hlc.compare(s.fieldHlc("removed"), op.hlc()) < 0) {
            raiseConflict(b, op, s, "status", "REMOVE_WINS", fld.om().valueToTree(value),
                    fld.om().getNodeFactory().textNode("REMOVED"), Map.of("seq", s.lastSeq(),
                            "hlc", s.fieldHlc("removed"), "device_id", s.createdDevice()));
        }
        List<Map<String, Object>> other = fld.jdbc().queryForList("""
                SELECT seq, hlc, device_id, payload->'value' AS v FROM fld.defect_event
                 WHERE defect_id = ? AND seq > ? AND device_id <> ? AND event_type = 'DefectMeasured'
                   AND payload->>'item' = ? AND coalesce((payload->'_server'->>'provisional')::boolean, false) = false
                 ORDER BY seq DESC LIMIT 1""", op.defectId(), since(b, op), b.deviceId, item);
        if (!other.isEmpty()) {
            JsonNode theirs = fld.tree(String.valueOf(other.get(0).get("v")));
            if (theirs == null || !theirs.isNumber() || theirs.decimalValue().compareTo(value) != 0) {
                JsonNode serverValue = s.attrs().hasNonNull(item) ? s.attrs().get(item) : theirs;
                raiseConflict(b, op, s, "attrs." + item, "MANUAL", p.path("value"), serverValue, other.get(0));
            }
        }
        // 장비 재검증(측정일 KST 기준) — 별도 읽기 트랜잭션(장비 없음 예외가 push 트랜잭션을 오염시키지 않게)
        LocalDate measuredOn = measuredAt.atZoneSameInstant(SEOUL).toLocalDate();
        UUID measurementId = UuidV7.next();
        String calStatus = "NOT_APPLICABLE";
        String basis = null;
        String calReason = null;
        UUID traceId = null;
        if (equipmentId != null) {
            ValidityResult v;
            try {
                v = isolatedRead.execute(t -> calibration.validate(equipmentId, measuredOn, b.round.projectId()));
            } catch (RuntimeException ex) {
                throw new Reject("UNKNOWN_EQUIPMENT", "장비를 확인할 수 없습니다: " + equipmentId);
            }
            if (v != null && v.valid()) {
                try {
                    traceId = traces.recordTrace(new MeasurementTracePort.TraceCommand(equipmentId, b.round.projectId(),
                            measuredOn, "FLD", "MEASUREMENT", measurementId, item, value,
                            p.path("unit").asText())).traceId();
                    calStatus = "VALID";
                    basis = v.basis();
                } catch (MeasurementTracePort.TraceRejectedException ex) {
                    calStatus = "INVALID";
                    calReason = ex.reason();
                }
            } else {
                calStatus = "INVALID";
                calReason = v == null ? "UNKNOWN" : v.reason();
            }
        }
        boolean provisional = "INVALID".equals(calStatus);
        server.put("measurementId", measurementId.toString());
        server.put("calibration", calStatus);
        if (provisional) {
            server.put("provisional", true);
            server.put("calibrationReason", calReason);
            extra.put("provisional", true);
            extra.put("blockedReason", calReason);   // BR-FLD-008: 확정 차단(잠정 유지) — 보고서 · 상태 미반영
        }
        extra.put("measurementId", measurementId);
        extra.put("calibration", calStatus);
        final String fStatus = calStatus;
        final String fBasis = basis;
        final String fReason = calReason;
        final UUID fTrace = traceId;
        return () -> {
            Long seq = fld.jdbc().queryForObject("SELECT seq FROM fld.defect_event WHERE op_id = ?", Long.class, op.opId());
            fld.jdbc().update("""
                    INSERT INTO fld.measurement(id, op_id, seq, defect_id, round_id, item, value, unit, method,
                           equipment_id, measured_at, calibration_status, calibration_basis, calibration_reason,
                           trace_id, provisional, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", measurementId, op.opId(), seq,
                    op.defectId(), b.round.id(), item, value, p.path("unit").asText(), method, equipmentId,
                    Timestamp.from(measuredAt.toInstant()), fStatus, fBasis, fReason, fTrace, provisional,
                    Timestamp.from(b.now));
        };
    }

    // ------------------------------------------------------------------ 사진 · 음성 · AI

    private Runnable photoAttached(Batch b, PushOp op, DefectState s) {
        JsonNode p = op.payload();
        UUID photoId = uuidField(p, "photoId");
        String sha = p.path("sha256").asText("").toLowerCase(java.util.Locale.ROOT);
        if (photoId == null || !SHA256.matcher(sha).matches()) {
            throw new Reject("INVALID_PAYLOAD", "photoId · sha256(소문자 16진수 64자) 필수");
        }
        List<Map<String, Object>> rows = fld.jdbc().queryForList(
                "SELECT id, round_id, device_sha256 FROM fld.photo WHERE id = ? OR device_sha256 = ?", photoId, sha);
        for (Map<String, Object> r : rows) {
            if (!r.get("id").equals(photoId)) {
                throw new Reject("PHOTO_DUPLICATE", "같은 원본(해시)이 다른 사진으로 이미 등록됨: " + r.get("id"));
            }
            if (!sha.equals(((String) r.get("device_sha256")).trim()) || !b.round.id().equals(r.get("round_id"))) {
                throw new Reject("PHOTO_MISMATCH", "photoId 의 해시 · 회차가 기존 등록과 다릅니다");
            }
        }
        boolean exists = !rows.isEmpty();
        return () -> {
            if (!exists) {
                Instant taken = null;
                try {
                    taken = p.hasNonNull("takenAt") ? OffsetDateTime.parse(p.get("takenAt").asText()).toInstant() : null;
                } catch (DateTimeParseException ignored) {
                    // 촬영 시각 형식 오류는 null(EXIF 원문은 exif 에 보존)
                }
                fld.jdbc().update("""
                        INSERT INTO fld.photo(id, round_id, defect_id, owner_type, kind, device_sha256, size_bytes, exif,
                                              taken_at, device_id, created_at, created_by)
                        VALUES (?, ?, ?, 'DEFECT', ?, ?, ?, ?::jsonb, ?, ?, ?, ?)""", photoId, b.round.id(), s.id(),
                        kindOf(p), sha, p.hasNonNull("sizeBytes") ? p.get("sizeBytes").asLong() : null,
                        fld.json(p.hasNonNull("exif") ? p.get("exif") : fld.om().createObjectNode()),
                        taken == null ? null : Timestamp.from(taken), b.deviceId, Timestamp.from(b.now), b.actorId);
            } else {
                fld.jdbc().update("UPDATE fld.photo SET defect_id = ? WHERE id = ? AND defect_id IS NULL", s.id(), photoId);
            }
        };
    }

    static String kindOf(JsonNode p) {
        String k = p.path("kind").asText("NEAR");
        return Set.of("FAR", "NEAR", "SCALE", "OVERVIEW").contains(k) ? k : "NEAR";
    }

    private Runnable voiceMemo(Batch b, PushOp op, DefectState s) {
        JsonNode p = op.payload();
        UUID memoId = uuidField(p, "voiceMemoId");
        UUID attachmentId = uuidField(p, "attachmentId");
        if (memoId == null) {
            throw new Reject("INVALID_PAYLOAD", "voiceMemoId 필수");
        }
        if (attachmentId != null && attachments.verifyObject(attachmentId).map(c -> !c.match()).orElse(true)) {
            throw new Reject("UNKNOWN_ATTACHMENT", "음성 원본 첨부가 없거나 해시가 맞지 않습니다: " + attachmentId);
        }
        return () -> {
            Long seq = fld.jdbc().queryForObject("SELECT seq FROM fld.defect_event WHERE op_id = ?", Long.class, op.opId());
            fld.jdbc().update("""
                    INSERT INTO fld.voice_memo(id, op_id, seq, defect_id, round_id, attachment_id, transcript, stt_model,
                                               duration_ms, recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING""", memoId, op.opId(), seq, s.id(),
                    b.round.id(), attachmentId, p.path("transcript").asText(null), p.path("sttModel").asText(null),
                    p.hasNonNull("durationMs") ? p.get("durationMs").asInt() : null, Timestamp.from(b.now));
        };
    }

    private Runnable aiDecided(Batch b, PushOp op, DefectState s, Map<String, Object> extra) {
        JsonNode p = op.payload();
        UUID suggestionId = uuidField(p, "suggestionId");
        String decision = p.path("decision").asText("");
        if (suggestionId == null || !Set.of("ACCEPTED", "MODIFIED", "REJECTED").contains(decision)) {
            throw new Reject("INVALID_PAYLOAD", "suggestionId · decision(ACCEPTED|MODIFIED|REJECTED) 필수");
        }
        List<Map<String, Object>> rows = fld.jdbc().queryForList(
                "SELECT defect_id, decision, width_mm FROM fld.ai_suggestion WHERE id = ?", suggestionId);
        if (rows.isEmpty() || !s.id().equals(rows.get(0).get("defect_id"))) {
            throw new Reject("UNKNOWN_SUGGESTION", "이 결함의 AI 제안이 아닙니다: " + suggestionId);
        }
        if (rows.get(0).get("decision") != null) {
            throw new Reject("SUGGESTION_DECIDED", "이미 결정된 AI 제안입니다");
        }
        BigDecimal aiWidth = (BigDecimal) rows.get(0).get("width_mm");
        BigDecimal decided = p.path("widthMm").isNumber() ? p.get("widthMm").decimalValue()
                : "ACCEPTED".equals(decision) ? aiWidth : null;
        extra.put("decision", decision);
        return () -> {
            fld.jdbc().update("""
                    UPDATE fld.ai_suggestion SET decision = ?, decided_width_mm = ?, decided_by = ?, decided_at = ?
                     WHERE id = ? AND decision IS NULL""", decision, decided, b.actorId, Timestamp.from(b.now),
                    suggestionId);
            outbox.publish(new FldEvents.AiSuggestionDecided(suggestionId, s.id(), decision, aiWidth, decided));
        };
    }

    // ------------------------------------------------------------------ 배치 마무리: 프로젝션 · outbox

    private void flush(Batch b) {
        if (b.touched.isEmpty()) {
            return;
        }
        for (UUID id : b.touched) {
            DefectState s = b.states.get(id);
            fld.upsertProjection(s);
            String[] before = b.before.get(id);
            boolean wasRecorded = before[0] != null && RECORDED_LIKE.contains(before[0]);
            boolean isRecorded = RECORDED_LIKE.contains(s.status());
            boolean nodeChanged = s.locationNodeId() != null && !s.locationNodeId().toString().equals(before[1]);
            if (isRecorded && s.locationNodeId() != null && (!wasRecorded || nodeChanged)) {
                outbox.publish(new FldEvents.DefectRecorded(s.id(), s.roundId(), b.round.projectId(),
                        b.round.buildingId(), s.locationNodeId(), s.code(), s.typeCode()));
            }
            before[0] = s.status();
            before[1] = s.locationNodeId() == null ? null : s.locationNodeId().toString();
        }
        outbox.publish(new FldEvents.DefectChanged(b.round.id(), b.round.projectId(), List.copyOf(b.touched), b.fromSeq,
                b.toSeq, b.accepted));
    }

    // ================================================================== pull

    /** change token = base64url("v1:{roundId}:{seq}") — 불투명 커서(앱은 해석하지 않고 그대로 돌려준다) */
    public static String token(UUID roundId, long seq) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1:" + roundId + ":" + seq).getBytes(StandardCharsets.UTF_8));
    }

    static long parseToken(String token, UUID roundId) {
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split(":");
            if (parts.length != 3 || !"v1".equals(parts[0]) || !UUID.fromString(parts[1]).equals(roundId)) {
                throw new IllegalArgumentException("round");
            }
            return Long.parseLong(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new DomainException(FldErrors.INVALID, "change token 이 이 회차의 것이 아니거나 손상됨");
        }
    }

    @Transactional
    public Map<String, Object> pull(UUID roundId, UUID deviceId, Long sinceSeq, String changeToken, Integer limit) {
        Round round = fld.round(roundId);
        access.requireProject("fld:defect:read", round.projectId());
        long since = changeToken != null && !changeToken.isBlank() ? parseToken(changeToken, roundId)
                : sinceSeq == null ? 0 : sinceSeq;
        int lim = limit == null ? 1000 : Math.max(1, Math.min(limit, 5000));
        Instant now = clock.instant();
        Instant cutoff = now.minus(props.sync().visibilityWindow());
        List<StoredEvent> events = fld.feed(roundId, since, deviceId, cutoff, lim + 1);
        boolean more = events.size() > lim;
        if (more) {
            events = events.subList(0, lim);
        }
        long last = events.isEmpty() ? since : events.get(events.size() - 1).seq();
        if (!more && deviceId != null) {
            // 자기 기기 이벤트만 남은 구간도 커서를 넘긴다(가시성 창 이전 범위까지)
            Long own = fld.jdbc().queryForObject("SELECT max(seq) FROM fld.defect_event WHERE round_id = ? AND seq > ? "
                    + "AND received_at <= ?", Long.class, roundId, last, Timestamp.from(cutoff));
            if (own != null) {
                last = own;
            }
        }
        boolean pendingWindow = Boolean.TRUE.equals(fld.jdbc().queryForObject(
                "SELECT EXISTS (SELECT 1 FROM fld.defect_event WHERE round_id = ? AND seq > ? AND received_at > ?)",
                Boolean.class, roundId, last, Timestamp.from(cutoff)));
        if (deviceId != null) {
            fld.jdbc().update("""
                    INSERT INTO fld.device_cursor(device_id, round_id, last_pulled_seq, last_seen_at) VALUES (?, ?, ?, ?)
                    ON CONFLICT (device_id, round_id) DO UPDATE SET last_pulled_seq = EXCLUDED.last_pulled_seq,
                                                                   last_seen_at = EXCLUDED.last_seen_at""",
                    deviceId, roundId, last, Timestamp.from(now));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("roundId", roundId);
        out.put("events", events.stream().map(SyncService::eventView).toList());
        out.put("lastSeq", last);
        out.put("changeToken", token(roundId, last));
        out.put("hasMore", more);
        out.put("pendingVisibility", pendingWindow);   // 가시성 창 안 이벤트 있음 — 잠시 뒤 다시 pull
        out.put("serverTime", OffsetDateTime.ofInstant(now, SEOUL));
        out.put("reuploadRequests", fld.jdbc().queryForList("""
                SELECT id AS "photoId", device_sha256 AS "sha256", reject_reason AS "reason", reject_count AS "rejectCount"
                  FROM fld.photo WHERE round_id = ? AND upload_state = 'REJECTED'
                   AND (?::uuid IS NULL OR device_id = ?::uuid) ORDER BY created_at, id""", roundId, deviceId, deviceId));
        out.put("conflicts", openConflicts(roundId, null));
        return out;
    }

    static Map<String, Object> eventView(StoredEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", e.seq());
        m.put("opId", e.opId());
        m.put("defectId", e.defectId());
        m.put("zoneId", e.zoneId());
        m.put("eventType", e.eventType());
        m.put("payload", e.payload());
        m.put("hlc", e.hlc());
        m.put("deviceId", e.deviceId());
        m.put("actorId", e.actorId());
        m.put("afterLock", e.afterLock());
        m.put("receivedAt", e.receivedAt());
        return m;
    }

    // ================================================================== 충돌 해결 (FR-FLD-016, SCR-FLD-09)

    public List<Map<String, Object>> openConflicts(UUID roundId, UUID zoneId) {
        return fld.jdbc().queryForList("""
                SELECT id FROM fld.sync_conflict WHERE round_id = ? AND status = 'OPEN' AND (?::uuid IS NULL OR zone_id = ?)
                 ORDER BY created_at, id""", UUID.class, roundId, zoneId, zoneId).stream().map(this::conflictView).toList();
    }

    public Map<String, Object> conflictView(UUID id) {
        Map<String, Object> c = fld.one("충돌", "SELECT * FROM fld.sync_conflict WHERE id = ?", id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("roundId", c.get("round_id"));
        m.put("zoneId", c.get("zone_id"));
        m.put("defectId", c.get("defect_id"));
        m.put("field", c.get("field"));
        m.put("strategy", c.get("strategy"));
        m.put("localOpId", c.get("local_op_id"));
        m.put("localEventType", c.get("local_event_type"));
        m.put("localValue", fld.tree(String.valueOf(c.get("local_value"))));
        m.put("localPayload", fld.tree(String.valueOf(c.get("local_payload"))));
        m.put("localHlc", c.get("local_hlc"));
        m.put("localDeviceId", c.get("local_device_id"));
        m.put("localActorId", c.get("local_actor_id"));
        m.put("serverSeq", c.get("server_seq"));
        m.put("serverValue", fld.tree(String.valueOf(c.get("server_value"))));
        m.put("serverHlc", c.get("server_hlc"));
        m.put("serverDeviceId", c.get("server_device_id"));
        m.put("status", c.get("status"));
        m.put("resolution", c.get("resolution"));
        m.put("resolvedValue", c.get("resolved_value") == null ? null : fld.tree(String.valueOf(c.get("resolved_value"))));
        m.put("resolutionSeq", c.get("resolution_seq"));
        m.put("resolvedBy", c.get("resolved_by"));
        m.put("resolvedAt", FldJdbc.inst(c.get("resolved_at")));
        return m;
    }

    /**
     * 충돌 해결: KEEP_SERVER(이벤트 없음) · KEEP_CLIENT(내 원 op 를 새 opId 로) · MERGE(내 원 payload 위에 merged 필드 덮어쓰기).
     * 새 이벤트 HLC 는 서버 값보다 반드시 늦게(서버가 보정) — 해결 값이 LWW 에서 이긴다. 측정값이면 장비를 다시 검증한다.
     * 같은 opId 재요청은 같은 결과(멱등). ENG 는 본인 충돌만(A2), 잠긴 구역은 PM + 사유 없이는 불가.
     */
    @Transactional
    public Map<String, Object> resolve(UUID conflictId, Resolve r) {
        Map<String, Object> c = fld.one("충돌", "SELECT * FROM fld.sync_conflict WHERE id = ? FOR UPDATE", conflictId);
        Round round = fld.round((UUID) c.get("round_id"));
        access.requireProject("fld:conflict:resolve", round.projectId());
        UUID me = actor.actorIdOrSystem();
        if (!access.isPm() && !me.equals(c.get("local_actor_id"))) {
            throw new DomainException(FldErrors.FORBIDDEN, "ENG 는 본인 충돌만 해결할 수 있습니다(A2)");
        }
        if ("RESOLVED".equals(c.get("status"))) {
            if (r.opId() != null && r.opId().equals(c.get("resolution_op_id"))) {
                Map<String, Object> v = conflictView(conflictId);
                v.put("replayed", true);
                return v;
            }
            throw new DomainException(FldErrors.CONFLICT_RESOLVED, "이미 해결된 충돌입니다");
        }
        String resolution = r.resolution() == null ? "" : r.resolution();
        if (!Set.of("KEEP_SERVER", "KEEP_CLIENT", "MERGE").contains(resolution)) {
            throw new DomainException(FldErrors.INVALID, "resolution 은 KEEP_SERVER · KEEP_CLIENT · MERGE");
        }
        Instant now = clock.instant();
        Long seq = null;
        JsonNode resolvedValue = fld.tree(String.valueOf(c.get("server_value")));
        if (!"KEEP_SERVER".equals(resolution)) {
            if (r.opId() == null || r.deviceId() == null) {
                throw new DomainException(FldErrors.INVALID, "KEEP_CLIENT · MERGE 는 opId · deviceId 가 필요합니다");
            }
            ObjectNode payload = (ObjectNode) fld.tree(String.valueOf(c.get("local_payload")));
            if ("MERGE".equals(resolution)) {
                if (r.merged() == null || !r.merged().isObject() || r.merged().isEmpty()) {
                    throw new DomainException(FldErrors.INVALID, "MERGE 는 merged(object, 덮어쓸 필드) 가 필요합니다");
                }
                r.merged().properties().forEach(f -> {
                    if (!f.getKey().startsWith("_")) {
                        payload.set(f.getKey(), f.getValue().deepCopy());
                    }
                });
            }
            payload.put("resolvesConflict", conflictId.toString());
            String serverHlc = (String) c.get("server_hlc");
            DefectState s = DefectState.replay((UUID) c.get("defect_id"), fld.events((UUID) c.get("defect_id")));
            String fieldHlc = s.fieldHlc(((String) c.get("field")).equals("status") ? "removed" : (String) c.get("field"));
            String floor = Hlc.compare(serverHlc, fieldHlc) >= 0 ? serverHlc : fieldHlc;
            String hlc = Hlc.valid(r.hlc()) && !tooFarInFuture(r.hlc(), now) && Hlc.compare(r.hlc(), floor) > 0 ? r.hlc()
                    : (floor == null ? new Hlc(now.toEpochMilli(), 0, SERVER_NODE)
                    : Hlc.parse(floor).successor(now.toEpochMilli(), SERVER_NODE)).pack();
            if ("status".equals(c.get("field"))) {
                throw new DomainException(FldErrors.INVALID,
                        "삭제된 결함(remove-wins)에는 측정값을 되살릴 수 없습니다 — KEEP_SERVER 로 해결하세요");
            }
            PushRequest pr = new PushRequest(r.deviceId(), round.id(), Long.MAX_VALUE / 2,
                    List.of(new PushOp(r.opId(), (UUID) c.get("defect_id"), (String) c.get("local_event_type"), hlc, null,
                            null, payload)));
            Map<String, Object> pushed = pushResolution(pr, round);
            if (!"ACCEPTED".equals(pushed.get("status")) && !"DUPLICATE".equals(pushed.get("status"))) {
                throw new DomainException(FldErrors.INVALID, "해결 op 가 거부되었습니다: " + pushed.get("reason"))
                        .with("result", pushed);
            }
            seq = ((Number) pushed.get("seq")).longValue();
            JsonNode v = "geom".equals(c.get("field")) ? payload.path("geomNorm") : payload.path("value");
            resolvedValue = v;
            if (Boolean.TRUE.equals(pushed.get("provisional"))) {
                resolvedValue = payload.path("value");
            }
        }
        fld.jdbc().update("""
                UPDATE fld.sync_conflict SET status = 'RESOLVED', resolution = ?, resolved_value = ?::jsonb,
                       resolution_op_id = ?, resolution_seq = ?, resolved_by = ?, resolved_at = ? WHERE id = ?""",
                resolution, fld.json(resolvedValue), r.opId(), seq, me, Timestamp.from(now), conflictId);
        return conflictView(conflictId);
    }

    /** 해결 op 는 일반 push 경로(검증 · 장비 · 프로젝션 · outbox)를 그대로 타되, 충돌 판정은 건너뛴다(since = 무한) */
    private Map<String, Object> pushResolution(PushRequest req, Round round) {
        fld.jdbc().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "fld.round:" + round.id());
        Batch b = new Batch(round, req.deviceId(), actor.actorIdOrSystem(), req.clientLastPulledSeq(), clock.instant());
        PushOp op = req.ops().get(0);
        Map<String, Object> r = processOp(b, op);
        UUID batchId = UuidV7.next();
        fld.jdbc().update("""
                INSERT INTO fld.sync_batch(id, round_id, device_id, actor_id, client_last_pulled_seq, op_count, accepted,
                                           duplicates, conflicts, rejected, clock_drift_ms, received_at)
                VALUES (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, NULL, ?)""", batchId, round.id(), req.deviceId(), b.actorId,
                req.clientLastPulledSeq(), b.accepted, b.duplicates, b.conflicts, b.rejected, Timestamp.from(b.now));
        for (Object[] row : b.opRows) {
            row[1] = batchId;
            fld.jdbc().update("""
                    INSERT INTO fld.sync_op(op_id, batch_id, round_id, defect_id, device_id, actor_id, event_type, hlc,
                                            status, seq, conflict_id, reason, result, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)""", row);
        }
        flush(b);
        return r;
    }
}
