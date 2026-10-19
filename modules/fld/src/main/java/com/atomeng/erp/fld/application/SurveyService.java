package com.atomeng.erp.fld.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fac.api.FacQueryApi;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc.Round;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc.Zone;
import com.atomeng.erp.fld.api.FldEvents;
import com.atomeng.erp.fld.domain.DefectState;
import com.atomeng.erp.fld.domain.Hlc;
import com.atomeng.erp.fld.domain.StoredEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조사 회차 · 구역 배정 · 구역 잠금/해제(FR-FLD-019, BR-FLD-009) · 결함 조회 · 이력/시점 재현(FR-FLD-020) · 프로젝션 재구축
 * (NFR-FLD-07, TC-FLD-013) · {@code fld.survey.completed}(모든 구역 잠금 시 1회).
 */
@Service
public class SurveyService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Set<String> SURVEY_TYPES = Set.of("REGULAR", "PRECISE_INSPECTION", "PRECISE_DIAGNOSIS", "ADJACENT_PRE",
            "ADJACENT_POST", "DEMOLITION");

    public record CreateRound(UUID projectId, UUID buildingId, Integer roundNo, String surveyType, Boolean evidenceMode,
                              LocalDate startedOn) {}

    public record CreateZone(String zoneName, UUID floorNodeId, UUID sheetId, JsonNode zoneGeomNorm, UUID assigneeId) {}

    private final FldJdbc fld;
    private final FldAccess access;
    private final FacQueryApi fac;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final Clock clock;

    public SurveyService(FldJdbc fld, FldAccess access, FacQueryApi fac, OutboxPublisher outbox, CurrentActor actor,
                         Clock clock) {
        this.fld = fld;
        this.access = access;
        this.fac = fac;
        this.outbox = outbox;
        this.actor = actor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ 회차 · 구역

    @Transactional
    public Map<String, Object> createRound(CreateRound c) {
        access.requireProject("fld:round:create", c.projectId());
        if (c.surveyType() == null || !SURVEY_TYPES.contains(c.surveyType())) {
            throw new DomainException(FldErrors.INVALID, "surveyType 은 " + SURVEY_TYPES + " 중 하나");
        }
        var building = fac.building(c.buildingId())
                .orElseThrow(() -> new DomainException(FldErrors.INVALID, "알 수 없는 시설물(fac.building): " + c.buildingId()));
        if ("MERGED".equals(building.status())) {
            throw new DomainException(FldErrors.INVALID, "병합된 시설물입니다 — 정본 " + building.mergedIntoId());
        }
        int roundNo = c.roundNo() != null ? c.roundNo() : fld.jdbc().queryForObject(
                "SELECT coalesce(max(round_no), 0) + 1 FROM fld.survey_round WHERE project_id = ?", Integer.class,
                c.projectId());
        UUID id = UuidV7.next();
        try {
            fld.jdbc().update("""
                    INSERT INTO fld.survey_round(id, project_id, building_id, round_no, survey_type, evidence_mode,
                                                 started_on, created_at, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, c.projectId(), c.buildingId(), roundNo, c.surveyType(),
                    Boolean.TRUE.equals(c.evidenceMode()), c.startedOn() == null ? null : Date.valueOf(c.startedOn()),
                    Timestamp.from(clock.instant()), actor.actorIdOrSystem());
        } catch (DuplicateKeyException e) {
            throw new DomainException(FldErrors.ROUND_EXISTS, "같은 프로젝트에 회차 번호 " + roundNo + " 가 이미 있습니다");
        }
        return roundView(id);
    }

    @Transactional
    public Map<String, Object> addZone(UUID roundId, CreateZone z) {
        Round r = fld.round(roundId);
        access.requireProject("fld:zone:assign", r.projectId());
        if (z.zoneName() == null || z.zoneName().isBlank() || z.assigneeId() == null) {
            throw new DomainException(FldErrors.INVALID, "zoneName · assigneeId 필수");
        }
        if (z.floorNodeId() != null) {
            var node = fac.locationNode(z.floorNodeId());
            if (node.isEmpty() || !node.get().buildingId().equals(r.buildingId()) || node.get().deleted()) {
                throw new DomainException(FldErrors.INVALID, "회차 건물의 위치 노드가 아닙니다: " + z.floorNodeId());
            }
        }
        if ("COMPLETED".equals(r.status())) {
            throw new DomainException(FldErrors.IMMUTABLE, "완료된 회차에는 구역을 추가할 수 없습니다");
        }
        UUID id = UuidV7.next();
        try {
            fld.jdbc().update("""
                    INSERT INTO fld.zone_assignment(id, round_id, zone_name, floor_node_id, sheet_id, zone_geom_norm,
                                                    assignee_id)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)""", id, roundId, z.zoneName().trim(), z.floorNodeId(), z.sheetId(),
                    z.zoneGeomNorm() == null ? null : fld.json(z.zoneGeomNorm()), z.assigneeId());
        } catch (DuplicateKeyException e) {
            throw new DomainException(FldErrors.INVALID, "같은 회차에 같은 구역 이름이 있습니다: " + z.zoneName());
        }
        return zoneView(fld.zone(id));
    }

    public Map<String, Object> round(UUID roundId) {
        Round r = fld.round(roundId);
        access.requireProject("fld:round:read", r.projectId());
        return roundView(roundId);
    }

    /** 웹 관리 화면: 프로젝트의 회차 목록(최신 회차 먼저) — 단건과 같은 범위 검사 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> rounds(UUID projectId) {
        access.requireProject("fld:round:read", projectId);
        return fld.jdbc().queryForList("SELECT id FROM fld.survey_round WHERE project_id = ? ORDER BY round_no DESC",
                UUID.class, projectId).stream().map(this::roundView).toList();
    }

    /** 웹 관리 화면: 회차 사진의 검증 상태(기기 해시 · 서버 해시 · 거부 사유) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> photos(UUID roundId) {
        Round r = fld.round(roundId);
        access.requireProject("fld:round:read", r.projectId());
        return fld.jdbc().queryForList("""
                SELECT p.id, p.defect_id, d.code AS defect_code, p.kind, p.upload_state, p.device_sha256,
                       p.server_sha256, p.attachment_id, p.reject_reason, p.reject_count, p.size_bytes, p.taken_at,
                       p.verified_at, p.invalidated, p.created_at
                  FROM fld.photo p LEFT JOIN fld.defect_current d ON d.id = p.defect_id
                 WHERE p.round_id = ? ORDER BY p.created_at, p.id""", roundId).stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("photoId", row.get("id"));
            m.put("defectId", row.get("defect_id"));
            m.put("defectCode", row.get("defect_code"));
            m.put("kind", row.get("kind"));
            m.put("uploadState", row.get("upload_state"));
            m.put("deviceSha256", row.get("device_sha256"));
            m.put("serverSha256", row.get("server_sha256"));
            m.put("attachmentId", row.get("attachment_id"));
            m.put("rejectReason", row.get("reject_reason"));
            m.put("rejectCount", row.get("reject_count"));
            m.put("sizeBytes", row.get("size_bytes"));
            m.put("takenAt", str(row.get("taken_at")));
            m.put("verifiedAt", str(row.get("verified_at")));
            m.put("invalidated", row.get("invalidated"));
            return m;
        }).toList();
    }

    private static String str(Object ts) {
        return ts instanceof java.sql.Timestamp t ? t.toInstant().toString() : ts == null ? null : ts.toString();
    }

    private Map<String, Object> roundView(UUID id) {
        Round r = fld.round(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id());
        m.put("projectId", r.projectId());
        m.put("buildingId", r.buildingId());
        m.put("roundNo", r.roundNo());
        m.put("surveyType", r.surveyType());
        m.put("evidenceMode", r.evidenceMode());
        m.put("status", r.status());
        m.put("completedAt", r.completedAt());
        m.put("lastSeq", fld.maxSeq(id));
        m.put("zones", fld.zones(id).stream().map(this::zoneView).toList());
        return m;
    }

    private Map<String, Object> zoneView(Zone z) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", z.id());
        m.put("roundId", z.roundId());
        m.put("zoneName", z.name());
        m.put("assigneeId", z.assigneeId());
        m.put("floorNodeId", z.floorNodeId());
        m.put("status", z.status());
        m.put("defectCount", fld.jdbc().queryForObject(
                "SELECT count(*) FROM fld.defect_current WHERE zone_id = ? AND status <> 'REMOVED'", Integer.class, z.id()));
        return m;
    }

    // ------------------------------------------------------------------ 구역 잠금 (FR-FLD-019, TC-FLD-011)

    /** GET /zones/{id}/lock-check — 회차 프로젝트 범위 확인 후 잠금 검증 사유(SEC-2026-10-19: 범위 검사 누락 수정) */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> lockCheck(UUID zoneId) {
        Zone z = fld.zone(zoneId);
        access.requireProject("fld:zone:lock", fld.round(z.roundId()).projectId());
        return lockIssues(zoneId);
    }

    /** 잠금 검증 사유(모두 모아 한 번에): 필수값 누락(DRAFT) · 미해결 충돌 · 미결 AI 제안 · 사진 미검증(REJECTED) */
    public List<Map<String, Object>> lockIssues(UUID zoneId) {
        List<Map<String, Object>> issues = new ArrayList<>();
        fld.jdbc().queryForList("""
                SELECT id, code, type_code FROM fld.defect_current WHERE zone_id = ? AND status = 'DRAFT' ORDER BY code""",
                zoneId).forEach(d -> issues.add(issue("REQUIRED_MISSING", d.get("id"), (String) d.get("code"),
                "필수 측정값 누락(FR-FLD-005) — 유형 " + d.get("type_code"))));
        fld.jdbc().queryForList("""
                SELECT c.id, c.defect_id, c.field, d.code FROM fld.sync_conflict c
                  LEFT JOIN fld.defect_current d ON d.id = c.defect_id
                 WHERE c.zone_id = ? AND c.status = 'OPEN' ORDER BY c.created_at""", zoneId)
                .forEach(c -> {
                    Map<String, Object> i = issue("OPEN_CONFLICT", c.get("defect_id"), (String) c.get("code"),
                            "미해결 동기화 충돌(FR-FLD-016): " + c.get("field"));
                    i.put("conflictId", c.get("id"));
                    issues.add(i);
                });
        fld.jdbc().queryForList("""
                SELECT id, code FROM fld.defect_current WHERE zone_id = ? AND status = 'AI_SUGGESTED' ORDER BY code""",
                zoneId).forEach(d -> issues.add(issue("AI_SUGGESTION_PENDING", d.get("id"), (String) d.get("code"),
                "AI 제안 미결정(BR-FLD-004)")));
        fld.jdbc().queryForList("""
                SELECT p.id, d.id AS defect_id, d.code FROM fld.photo p JOIN fld.defect_current d ON d.id = p.defect_id
                 WHERE d.zone_id = ? AND d.status <> 'REMOVED' AND p.upload_state = 'REJECTED' ORDER BY d.code""", zoneId)
                .forEach(p -> {
                    Map<String, Object> i = issue("PHOTO_REJECTED", p.get("defect_id"), (String) p.get("code"),
                            "해시 불일치로 거부된 사진 재업로드 필요(BR-FLD-002)");
                    i.put("photoId", p.get("id"));
                    issues.add(i);
                });
        return issues;
    }

    private static Map<String, Object> issue(String kind, Object defectId, String code, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("defectId", defectId);
        m.put("defectCode", code);
        m.put("message", message);
        return m;
    }

    @Transactional
    public Map<String, Object> lock(UUID zoneId) {
        Zone z = lockedRow(zoneId);
        Round r = fld.round(z.roundId());
        access.requireProject("fld:zone:lock", r.projectId());
        if (z.locked()) {
            return zoneView(z);   // 멱등
        }
        fld.jdbc().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "fld.round:" + r.id());
        List<Map<String, Object>> issues = lockIssues(zoneId);
        if (!issues.isEmpty()) {
            throw new DomainException(FldErrors.ZONE_NOT_READY, "구역 잠금 조건 미충족 " + issues.size() + "건")
                    .with("reason", "FLD_ZONE_NOT_READY").with("issues", issues);
        }
        Instant now = clock.instant();
        UUID me = actor.actorIdOrSystem();
        fld.jdbc().update("UPDATE fld.zone_assignment SET status = 'LOCKED', locked_at = ?, locked_by = ?, "
                + "version = version + 1 WHERE id = ?", Timestamp.from(now), me, zoneId);
        fld.jdbc().update("INSERT INTO fld.zone_lock_audit(id, zone_id, action, actor_id, at) VALUES (?, ?, 'LOCK', ?, ?)",
                UuidV7.next(), zoneId, me, Timestamp.from(now));
        int verified = systemEvents(r, z, "DefectVerified", null, me, now);
        outbox.publish(new FldEvents.ZoneLocked(zoneId, r.id(), r.projectId(), z.name(), verified));
        completeIfAllLocked(r, now);
        return zoneView(fld.zone(zoneId));
    }

    @Transactional
    public Map<String, Object> unlock(UUID zoneId, String reason) {
        Zone z = lockedRow(zoneId);
        Round r = fld.round(z.roundId());
        access.requireProject("fld:zone:lock", r.projectId());
        if (reason == null || reason.trim().length() < 5) {
            throw new DomainException(FldErrors.INVALID, "잠금 해제 사유(5자 이상) 필수(BR-FLD-009)");
        }
        if (!z.locked()) {
            throw new DomainException(FldErrors.INVALID, "잠긴 구역이 아닙니다");
        }
        Instant now = clock.instant();
        UUID me = actor.actorIdOrSystem();
        fld.jdbc().update("UPDATE fld.zone_assignment SET status = 'IN_PROGRESS', locked_at = NULL, locked_by = NULL, "
                + "version = version + 1 WHERE id = ?", zoneId);
        fld.jdbc().update("INSERT INTO fld.zone_lock_audit(id, zone_id, action, reason, actor_id, at) "
                + "VALUES (?, ?, 'UNLOCK', ?, ?, ?)", UuidV7.next(), zoneId, reason.trim(), me, Timestamp.from(now));
        systemEvents(r, z, "DefectUnverified", reason.trim(), me, now);
        return zoneView(fld.zone(zoneId));
    }

    private Zone lockedRow(UUID zoneId) {
        fld.one("구역", "SELECT id FROM fld.zone_assignment WHERE id = ? FOR UPDATE", zoneId);
        return fld.zone(zoneId);
    }

    /** 구역 결함(삭제 제외) 전체에 서버 이벤트(검수 · 검수 해제) — 결정적 opId(구역 · 결함 · 잠금 시각) */
    private int systemEvents(Round r, Zone z, String type, String reason, UUID me, Instant now) {
        List<UUID> defects = fld.jdbc().queryForList(
                "SELECT id FROM fld.defect_current WHERE zone_id = ? AND status <> 'REMOVED' ORDER BY code", UUID.class,
                z.id());
        for (UUID d : defects) {
            DefectState s = DefectState.replay(d, fld.events(d));
            String floor = s.fieldHlc("verified");
            Hlc hlc = floor == null || Hlc.compare(floor, new Hlc(now.toEpochMilli(), 0, SyncService.SERVER_NODE).pack()) < 0
                    ? new Hlc(now.toEpochMilli(), 0, SyncService.SERVER_NODE)
                    : Hlc.parse(floor).successor(now.toEpochMilli(), SyncService.SERVER_NODE);
            ObjectNode payload = fld.om().createObjectNode().put("zoneId", z.id().toString());
            if (reason != null) {
                payload.put("reason", reason);
            }
            UUID opId = UUID.nameUUIDFromBytes((type + ":" + d + ":" + now.toEpochMilli() + ":" + s.version())
                    .getBytes(StandardCharsets.UTF_8));
            long seq = fld.appendEvent(opId, d, r.id(), z.id(), type, payload, hlc.pack(), SyncService.SERVER_DEVICE, me,
                    false, now);
            s.apply(new StoredEvent(seq, opId, d, r.id(), z.id(), type, payload, hlc.pack(), SyncService.SERVER_DEVICE,
                    me, false, now));
            fld.upsertProjection(s);
        }
        if (!defects.isEmpty()) {
            outbox.publish(new FldEvents.DefectChanged(r.id(), r.projectId(), defects, -1, fld.maxSeq(r.id()),
                    defects.size()));
        }
        return defects.size();
    }

    /** 모든 구역이 잠기면 회차 완료 + fld.survey.completed 1회(completed_at 이 NULL 일 때만 — 해제 · 재잠금에도 다시 안 냄) */
    private void completeIfAllLocked(Round r, Instant now) {
        Integer open = fld.jdbc().queryForObject(
                "SELECT count(*) FROM fld.zone_assignment WHERE round_id = ? AND status <> 'LOCKED'", Integer.class, r.id());
        if (open == null || open > 0) {
            return;
        }
        int updated = fld.jdbc().update("""
                UPDATE fld.survey_round SET status = 'COMPLETED', completed_at = ?, ended_on = ?, version = version + 1
                 WHERE id = ? AND completed_at IS NULL""", Timestamp.from(now),
                Date.valueOf(LocalDate.ofInstant(now, SEOUL)), r.id());
        if (updated == 1) {
            int zones = fld.zones(r.id()).size();
            Integer defects = fld.jdbc().queryForObject(
                    "SELECT count(*) FROM fld.defect_current WHERE round_id = ? AND status <> 'REMOVED'", Integer.class,
                    r.id());
            outbox.publish(new FldEvents.SurveyCompleted(r.id(), r.projectId(), r.buildingId(), r.roundNo(),
                    r.surveyType(), zones, defects == null ? 0 : defects, fld.maxSeq(r.id()),
                    LocalDate.ofInstant(now, SEOUL)));
        }
    }

    // ------------------------------------------------------------------ 결함 조회 · 이력

    public List<Map<String, Object>> defects(UUID roundId, UUID zoneId) {
        Round r = fld.round(roundId);
        access.requireProject("fld:defect:read", r.projectId());
        return fld.jdbc().queryForList("""
                SELECT id FROM fld.defect_current WHERE round_id = ? AND (?::uuid IS NULL OR zone_id = ?) ORDER BY code""",
                UUID.class, roundId, zoneId, zoneId).stream().map(this::defectView).toList();
    }

    public Map<String, Object> defect(UUID defectId) {
        Map<String, Object> row = fld.defectRow(defectId)
                .orElseThrow(() -> new DomainException(FldErrors.NOT_FOUND, "결함 없음: " + defectId));
        access.requireProject("fld:defect:read", fld.round((UUID) row.get("round_id")).projectId());
        return defectView(defectId);
    }

    Map<String, Object> defectView(UUID id) {
        Map<String, Object> d = fld.defectRow(id).orElseThrow();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.get("id"));
        m.put("roundId", d.get("round_id"));
        m.put("zoneId", d.get("zone_id"));
        m.put("code", d.get("code"));
        m.put("typeCode", d.get("type_code"));
        m.put("subtypeCode", d.get("subtype_code"));
        m.put("locationNodeId", d.get("location_node_id"));
        m.put("geomNorm", fld.tree(String.valueOf(d.get("geom_norm"))));
        m.put("attrs", fld.tree(String.valueOf(d.get("attrs"))));
        m.put("measureMeta", fld.tree(String.valueOf(d.get("measure_meta"))));
        m.put("fieldHlc", fld.tree(String.valueOf(d.get("field_hlc"))));
        m.put("versionVector", fld.tree(String.valueOf(d.get("version_vector"))));
        m.put("photos", fld.tree(String.valueOf(d.get("photos"))));
        m.put("voiceMemos", fld.tree(String.valueOf(d.get("voice_memos"))));
        m.put("aiPending", d.get("ai_pending"));
        m.put("status", d.get("status"));
        m.put("removedReason", d.get("removed_reason"));
        m.put("version", d.get("version"));
        m.put("lastSeq", d.get("last_seq"));
        m.put("updatedAt", FldJdbc.inst(d.get("updated_at")));
        m.put("provisionalMeasurements", fld.jdbc().queryForList("""
                SELECT id, item, value, unit, method, equipment_id AS "equipmentId", calibration_reason AS "reason"
                  FROM fld.measurement WHERE defect_id = ? AND provisional ORDER BY seq""", id));
        return m;
    }

    /** FR-FLD-020: 이벤트 이력 + asOf(서버 수신 시각) 기준 상태 재현 */
    public Map<String, Object> history(UUID defectId, Instant asOf) {
        List<StoredEvent> all = fld.events(defectId);
        if (all.isEmpty()) {
            throw new DomainException(FldErrors.NOT_FOUND, "결함 없음: " + defectId);
        }
        access.requireProject("fld:defect:read", fld.round(all.get(0).roundId()).projectId());
        List<StoredEvent> events = asOf == null ? all : fld.eventsAsOf(defectId, asOf);
        DefectState s = DefectState.replay(defectId, events);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defectId", defectId);
        m.put("asOf", asOf);
        m.put("events", events.stream().map(SyncService::eventView).toList());
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("exists", s.created());
        if (s.created()) {
            state.put("code", s.code());
            state.put("typeCode", s.typeCode());
            state.put("attrs", s.attrs());
            state.put("geomNorm", s.geomNorm());
            state.put("locationNodeId", s.locationNodeId());
            state.put("photos", s.attachedPhotos());
            state.put("status", s.status());
            state.put("version", s.version());
        }
        m.put("state", state);
        return m;
    }

    // ------------------------------------------------------------------ 재구축 (NFR-FLD-07, TC-FLD-013)

    /** 회차 프로젝션 덤프 해시(정렬 · 내용 열만) — 재구축 전후 비교 */
    public String projectionHash(UUID roundId) {
        List<String> rows = fld.jdbc().queryForList("""
                SELECT concat_ws('|', id, zone_id, code, location_node_id, sheet_id, sheet_version, type_code, subtype_code,
                                 geom_norm::text, attrs::text, measure_meta::text, field_hlc::text, version_vector::text,
                                 photos::text, voice_memos::text, ai_pending, status, removed_reason, version, last_seq,
                                 created_by, created_device, updated_at)
                  FROM fld.defect_current WHERE round_id = ? ORDER BY id""", String.class, roundId);
        return Hashing.sha256Hex(String.join("\n", rows));
    }

    @Transactional
    public Map<String, Object> rebuild(UUID roundId) {
        Round r = fld.round(roundId);
        fld.jdbc().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "fld.round:" + r.id());
        String before = projectionHash(roundId);
        int deleted = fld.jdbc().update("DELETE FROM fld.defect_current WHERE round_id = ?", roundId);
        Map<UUID, List<StoredEvent>> byDefect = new LinkedHashMap<>();
        for (StoredEvent e : fld.roundEvents(roundId)) {
            byDefect.computeIfAbsent(e.defectId(), k -> new ArrayList<>()).add(e);
        }
        byDefect.forEach((id, ev) -> fld.upsertProjection(DefectState.replay(id, ev)));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roundId", roundId);
        m.put("deleted", deleted);
        m.put("rebuilt", byDefect.size());
        m.put("events", byDefect.values().stream().mapToInt(List::size).sum());
        m.put("hashBefore", before);
        m.put("hashAfter", projectionHash(roundId));
        return m;
    }
}
