package com.atomeng.erp.fld.adapter.out.persistence;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.fld.application.FldErrors;
import com.atomeng.erp.fld.domain.DefectState;
import com.atomeng.erp.fld.domain.StoredEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * fld 스키마 JDBC — 이벤트 저장소(append) · 프로젝션 upsert · 공통 변환. append-only 테이블이 많아 JPA 대신 JDBC
 * (HRQ · EQP · RPT · DEM 과 같은 방식). defect_event 에는 UPDATE · DELETE 문을 두지 않는다(DB 트리거도 거부).
 */
@Repository
public class FldJdbc {

    public record Round(UUID id, UUID projectId, UUID buildingId, int roundNo, String surveyType, boolean evidenceMode,
                        String status, Instant completedAt) {}

    public record Zone(UUID id, UUID roundId, String name, UUID assigneeId, String status, UUID floorNodeId) {
        public boolean locked() {
            return "LOCKED".equals(status);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public FldJdbc(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public ObjectMapper om() {
        return om;
    }

    public static Timestamp ts(Instant x) {
        return x == null ? null : Timestamp.from(x);
    }

    public static Instant inst(Object v) {
        return v == null ? null : ((Timestamp) v).toInstant();
    }

    public String json(Object o) {
        try {
            return o == null ? null : om.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new DomainException(FldErrors.INVALID, "JSON 직렬화 오류");
        }
    }

    public JsonNode tree(String json) {
        try {
            return json == null ? null : om.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("저장된 JSON 오류", e);
        }
    }

    public Map<String, Object> one(String what, String sql, Object... args) {
        List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
        if (rows.isEmpty()) {
            throw new DomainException(FldErrors.NOT_FOUND, what + " 없음");
        }
        return rows.get(0);
    }

    // ------------------------------------------------------------------ 회차 · 구역

    private static Round round(ResultSet rs, int i) throws SQLException {
        Timestamp c = rs.getTimestamp("completed_at");
        return new Round(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getObject("building_id", UUID.class), rs.getInt("round_no"), rs.getString("survey_type"),
                rs.getBoolean("evidence_mode"), rs.getString("status"), c == null ? null : c.toInstant());
    }

    public Optional<Round> findRound(UUID id) {
        return jdbc.query("SELECT * FROM fld.survey_round WHERE id = ?", FldJdbc::round, id).stream().findFirst();
    }

    public Round round(UUID id) {
        return findRound(id).orElseThrow(() -> new DomainException(FldErrors.NOT_FOUND, "조사 회차 없음: " + id));
    }

    private static Zone zone(ResultSet rs, int i) throws SQLException {
        return new Zone(rs.getObject("id", UUID.class), rs.getObject("round_id", UUID.class), rs.getString("zone_name"),
                rs.getObject("assignee_id", UUID.class), rs.getString("status"), rs.getObject("floor_node_id", UUID.class));
    }

    public Optional<Zone> findZone(UUID id) {
        return jdbc.query("SELECT * FROM fld.zone_assignment WHERE id = ?", FldJdbc::zone, id).stream().findFirst();
    }

    public Zone zone(UUID id) {
        return findZone(id).orElseThrow(() -> new DomainException(FldErrors.NOT_FOUND, "구역 없음: " + id));
    }

    public List<Zone> zones(UUID roundId) {
        return jdbc.query("SELECT * FROM fld.zone_assignment WHERE round_id = ? ORDER BY zone_name", FldJdbc::zone,
                roundId);
    }

    /** 결함 번호 채번 CRK-0001 (INSERT … ON CONFLICT 카운터, max()+1 금지) */
    public String nextCode(UUID roundId, String typeCode) {
        Integer seq = jdbc.queryForObject("""
                INSERT INTO fld.code_counter(round_id, type_code, last_seq) VALUES (?, ?, 1)
                ON CONFLICT (round_id, type_code) DO UPDATE SET last_seq = fld.code_counter.last_seq + 1
                RETURNING last_seq""", Integer.class, roundId, typeCode);
        return "%s-%04d".formatted(typeCode, seq);
    }

    // ------------------------------------------------------------------ 이벤트 저장소

    public long appendEvent(UUID opId, UUID defectId, UUID roundId, UUID zoneId, String eventType, JsonNode payload,
                            String hlc, UUID deviceId, UUID actorId, boolean afterLock, Instant receivedAt) {
        Long seq = jdbc.queryForObject("""
                INSERT INTO fld.defect_event(op_id, defect_id, round_id, zone_id, event_type, payload, hlc, device_id,
                                             actor_id, after_lock, received_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?) RETURNING seq""", Long.class, opId, defectId, roundId,
                zoneId, eventType, json(payload), hlc, deviceId, actorId, afterLock, ts(receivedAt));
        return seq == null ? -1 : seq;
    }

    private StoredEvent event(ResultSet rs, int i) throws SQLException {
        return new StoredEvent(rs.getLong("seq"), rs.getObject("op_id", UUID.class),
                rs.getObject("defect_id", UUID.class), rs.getObject("round_id", UUID.class),
                rs.getObject("zone_id", UUID.class), rs.getString("event_type"), tree(rs.getString("payload")),
                rs.getString("hlc"), rs.getObject("device_id", UUID.class), rs.getObject("actor_id", UUID.class),
                rs.getBoolean("after_lock"), rs.getTimestamp("received_at").toInstant());
    }

    public List<StoredEvent> events(UUID defectId) {
        return jdbc.query("SELECT * FROM fld.defect_event WHERE defect_id = ? ORDER BY seq", this::event, defectId);
    }

    /** 시점 재현(FR-FLD-020): 서버 수신 시각 ≤ asOf 인 이벤트 */
    public List<StoredEvent> eventsAsOf(UUID defectId, Instant asOf) {
        return jdbc.query("SELECT * FROM fld.defect_event WHERE defect_id = ? AND received_at <= ? ORDER BY seq",
                this::event, defectId, ts(asOf));
    }

    public List<StoredEvent> roundEvents(UUID roundId) {
        return jdbc.query("SELECT * FROM fld.defect_event WHERE round_id = ? ORDER BY seq", this::event, roundId);
    }

    /** change feed — seq &gt; since, 자기 기기 제외, 가시성 지연 창(received_at ≤ cutoff) */
    public List<StoredEvent> feed(UUID roundId, long since, UUID excludeDevice, Instant cutoff, int limit) {
        return jdbc.query("""
                SELECT * FROM fld.defect_event
                 WHERE round_id = ? AND seq > ? AND (?::uuid IS NULL OR device_id <> ?::uuid) AND received_at <= ?
                 ORDER BY seq LIMIT ?""", this::event, roundId, since, excludeDevice, excludeDevice, ts(cutoff), limit);
    }

    public long maxSeq(UUID roundId) {
        Long v = jdbc.queryForObject("SELECT max(seq) FROM fld.defect_event WHERE round_id = ?", Long.class, roundId);
        return v == null ? 0 : v;
    }

    // ------------------------------------------------------------------ 프로젝션

    public void upsertProjection(DefectState s) {
        jdbc.update("""
                INSERT INTO fld.defect_current(id, round_id, zone_id, code, location_node_id, sheet_id, sheet_version,
                       type_code, subtype_code, geom_norm, attrs, measure_meta, field_hlc, version_vector, photos,
                       voice_memos, ai_pending, status, removed_reason, version, last_seq, created_by, created_device,
                       updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO UPDATE SET zone_id = EXCLUDED.zone_id, location_node_id = EXCLUDED.location_node_id,
                       sheet_id = EXCLUDED.sheet_id, sheet_version = EXCLUDED.sheet_version,
                       type_code = EXCLUDED.type_code, subtype_code = EXCLUDED.subtype_code,
                       geom_norm = EXCLUDED.geom_norm, attrs = EXCLUDED.attrs, measure_meta = EXCLUDED.measure_meta,
                       field_hlc = EXCLUDED.field_hlc, version_vector = EXCLUDED.version_vector,
                       photos = EXCLUDED.photos, voice_memos = EXCLUDED.voice_memos, ai_pending = EXCLUDED.ai_pending,
                       status = EXCLUDED.status, removed_reason = EXCLUDED.removed_reason, version = EXCLUDED.version,
                       last_seq = EXCLUDED.last_seq, updated_at = EXCLUDED.updated_at""",
                s.id(), s.roundId(), s.zoneId(), s.code(), s.locationNodeId(), s.sheetId(), s.sheetVersion(),
                s.typeCode(), s.subtypeCode(), json(s.geomNorm()), json(s.attrs()), json(s.measureMeta()),
                json(s.fieldHlcMap()), json(s.versionVector()), json(s.attachedPhotos()), json(s.voiceMemos()),
                s.aiPending(), s.status(), s.removedReason(), s.version(), s.lastSeq(), s.createdBy(),
                s.createdDevice(), ts(s.updatedAt()));
    }

    public Optional<Map<String, Object>> defectRow(UUID id) {
        return jdbc.queryForList("SELECT * FROM fld.defect_current WHERE id = ?", id).stream().findFirst();
    }
}
