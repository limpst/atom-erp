package com.atomeng.erp.fld.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 결함 상태 리듀서 (설계 B6.1 · B7.2, 05-IMPL Step 3) — I/O 없는 순수 규칙. 이벤트를 seq 순으로 적용하면 defect_current 1행이 된다
 * (재구축 · 시점 재현 · 실시간 프로젝션이 모두 이 클래스 하나를 쓴다 → TC-FLD-013).
 * <ul>
 *   <li>스칼라 · 기하 · 유형 · 위치 · 측정값: 필드별 LWW-Register(HLC 큰 값 승) — 늦게 도착한 옛 값은 무시</li>
 *   <li>측정값 중 서버 재검증 실패(잠정, {@code _server.provisional})는 attrs 에 반영하지 않는다(BR-FLD-008, TC-FLD-014)</li>
 *   <li>사진: 사진별 LWW(첨부/분리) · 음성 메모: 추가만(집합) · AI 제안: 첨부 집합 − 결정 집합</li>
 *   <li>삭제: remove-wins(이후 어떤 이벤트도 되살리지 않음) · 검수(VERIFIED): LWW</li>
 * </ul>
 * 모든 규칙이 교환 법칙을 만족해 같은 이벤트 집합이면 적용 순서와 관계없이 같은 상태로 수렴한다(DefectStateTest).
 */
public final class DefectState {

    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final UUID id;
    private UUID roundId;
    private UUID zoneId;
    private String code;
    private UUID locationNodeId;
    private UUID sheetId;
    private Integer sheetVersion;
    private String typeCode;
    private String subtypeCode;
    private JsonNode geomNorm = F.objectNode();
    private final ObjectNode attrs = F.objectNode();
    private final ObjectNode measureMeta = F.objectNode();
    private final TreeMap<String, String> fieldHlc = new TreeMap<>();
    private final TreeMap<String, String> versionVector = new TreeMap<>();
    private final TreeMap<String, Boolean> photos = new TreeMap<>();
    private final TreeMap<String, ObjectNode> voiceMemos = new TreeMap<>();
    private final TreeMap<String, String> aiAttached = new TreeMap<>();   // suggestionId → hlc
    private final TreeMap<String, String> aiDecided = new TreeMap<>();    // suggestionId → decision
    private boolean verified;
    private boolean removed;
    private String removedReason;
    private long version;
    private long lastSeq;
    private Instant updatedAt;
    private UUID createdBy;
    private UUID createdDevice;
    private boolean created;

    public DefectState(UUID id) {
        this.id = id;
    }

    public static DefectState replay(UUID id, List<StoredEvent> events) {
        DefectState s = new DefectState(id);
        events.forEach(s::apply);
        return s;
    }

    public DefectState apply(StoredEvent e) {
        JsonNode p = e.payload();
        String hlc = e.hlc();
        switch (e.eventType()) {
            case "DefectCreated" -> {
                if (!created) {
                    created = true;
                    roundId = e.roundId();
                    zoneId = uuid(p, "zoneId");
                    code = p.path("_server").path("code").asText(null);
                    createdBy = e.actorId();
                    createdDevice = e.deviceId();
                    sheetId = uuid(p, "sheetId");
                    sheetVersion = p.hasNonNull("sheetVersion") ? p.get("sheetVersion").asInt() : null;
                }
                lww("type", hlc, () -> {
                    typeCode = p.path("typeCode").asText(null);
                    subtypeCode = p.path("subtypeCode").asText(null);
                });
                lww("geom", hlc, () -> geomNorm = p.path("geomNorm").deepCopy());
                if (p.hasNonNull("locationNodeId")) {
                    lww("location", hlc, () -> locationNodeId = uuid(p, "locationNodeId"));
                }
            }
            case "DefectGeometryChanged" -> lww("geom", hlc, () -> geomNorm = p.path("geomNorm").deepCopy());
            case "DefectClassified" -> lww("type", hlc, () -> {
                typeCode = p.path("typeCode").asText(null);
                subtypeCode = p.path("subtypeCode").asText(null);
            });
            case "DefectMeasured" -> {
                if (!p.path("_server").path("provisional").asBoolean(false)) {
                    String item = p.path("item").asText();
                    lww("attrs." + item, hlc, () -> {
                        attrs.set(item, p.path("value").deepCopy());
                        ObjectNode meta = F.objectNode();
                        meta.put("unit", p.path("unit").asText(null));
                        meta.put("method", p.path("method").asText(null));
                        meta.put("equipmentId", p.path("equipmentId").asText(null));
                        meta.put("measuredAt", p.path("measuredAt").asText(null));
                        meta.put("measurementId", p.path("_server").path("measurementId").asText(null));
                        measureMeta.set(item, meta);
                    });
                }
            }
            case "DefectAttributesSet" -> p.path("fields").properties().forEach(f ->
                    lww("attrs." + f.getKey(), hlc, () -> attrs.set(f.getKey(), f.getValue().deepCopy())));
            case "DefectMemberLinked" -> lww("location", hlc, () -> locationNodeId = uuid(p, "locationNodeId"));
            case "PhotoAttached" -> {
                String photo = p.path("photoId").asText();
                lww("photo:" + photo, hlc, () -> photos.put(photo, true));
            }
            case "PhotoDetached" -> {
                String photo = p.path("photoId").asText();
                lww("photo:" + photo, hlc, () -> photos.put(photo, false));
            }
            case "VoiceMemoAttached" -> {
                ObjectNode v = F.objectNode();
                v.put("voiceMemoId", p.path("voiceMemoId").asText());
                v.put("attachmentId", p.path("attachmentId").asText(null));
                v.put("transcript", p.path("transcript").asText(null));
                v.put("sttModel", p.path("sttModel").asText(null));
                voiceMemos.putIfAbsent(p.path("voiceMemoId").asText(), v);
            }
            case "AiSuggestionAttached" -> aiAttached.merge(p.path("suggestionId").asText(), hlc,
                    (a, b) -> Hlc.compare(a, b) >= 0 ? a : b);
            case "AiSuggestionDecided" -> aiDecided.putIfAbsent(p.path("suggestionId").asText(),
                    p.path("decision").asText());
            case "DefectVerified" -> lww("verified", hlc, () -> verified = true);
            case "DefectUnverified" -> lww("verified", hlc, () -> verified = false);
            case "DefectRemoved" -> {
                removed = true;
                lww("removed", hlc, () -> removedReason = p.path("reason").asText(null));
            }
            default -> throw new IllegalArgumentException("알 수 없는 결함 이벤트: " + e.eventType());
        }
        versionVector.merge(e.deviceId().toString(), hlc, (a, b) -> Hlc.compare(a, b) >= 0 ? a : b);
        version++;
        lastSeq = Math.max(lastSeq, e.seq());
        if (updatedAt == null || e.receivedAt().isAfter(updatedAt)) {
            updatedAt = e.receivedAt();
        }
        return this;
    }

    private void lww(String field, String hlc, Runnable change) {
        String cur = fieldHlc.get(field);
        if (cur != null && Hlc.compare(cur, hlc) >= 0) {
            return;   // 늦게 도착한 옛 값
        }
        change.run();
        fieldHlc.put(field, hlc);
    }

    private static UUID uuid(JsonNode p, String f) {
        return p.hasNonNull(f) ? UUID.fromString(p.get(f).asText()) : null;
    }

    // ------------------------------------------------------------------ 파생 상태

    public boolean complete() {
        return typeCode != null && DefectTypes.complete(typeCode, attrs::hasNonNull);
    }

    /** 미결 AI 제안(첨부 − 결정 중 HLC 가 가장 늦은 것) */
    public UUID aiPending() {
        String best = null;
        String bestHlc = null;
        for (Map.Entry<String, String> a : aiAttached.entrySet()) {
            if (!aiDecided.containsKey(a.getKey()) && (bestHlc == null || Hlc.compare(a.getValue(), bestHlc) > 0)) {
                best = a.getKey();
                bestHlc = a.getValue();
            }
        }
        return best == null ? null : UUID.fromString(best);
    }

    /** A6.1: REMOVED > VERIFIED > (필수값 완비 ? (미결 AI ? AI_SUGGESTED : RECORDED) : DRAFT) */
    public String status() {
        if (removed) {
            return "REMOVED";
        }
        if (verified) {
            return "VERIFIED";
        }
        if (!complete()) {
            return "DRAFT";
        }
        return aiPending() != null ? "AI_SUGGESTED" : "RECORDED";
    }

    public List<String> attachedPhotos() {
        List<String> out = new ArrayList<>();
        photos.forEach((k, v) -> {
            if (v) {
                out.add(k);
            }
        });
        return out;
    }

    public String fieldHlc(String field) {
        return fieldHlc.get(field);
    }

    public UUID id() { return id; }
    public UUID roundId() { return roundId; }
    public UUID zoneId() { return zoneId; }
    public String code() { return code; }
    public UUID locationNodeId() { return locationNodeId; }
    public UUID sheetId() { return sheetId; }
    public Integer sheetVersion() { return sheetVersion; }
    public String typeCode() { return typeCode; }
    public String subtypeCode() { return subtypeCode; }
    public JsonNode geomNorm() { return geomNorm; }
    public ObjectNode attrs() { return attrs; }
    public ObjectNode measureMeta() { return measureMeta; }
    public Map<String, String> fieldHlcMap() { return java.util.Collections.unmodifiableMap(fieldHlc); }
    public Map<String, String> versionVector() { return java.util.Collections.unmodifiableMap(versionVector); }
    public List<ObjectNode> voiceMemos() { return List.copyOf(voiceMemos.values()); }
    public boolean removed() { return removed; }
    public String removedReason() { return removedReason; }
    public long version() { return version; }
    public long lastSeq() { return lastSeq; }
    public Instant updatedAt() { return updatedAt; }
    public UUID createdBy() { return createdBy; }
    public UUID createdDevice() { return createdDevice; }
    public boolean created() { return created; }

    /** 수렴성 비교용 정규 표현(버전 · seq · 시각 제외 — 적용 순서와 무관한 내용만) */
    public Map<String, Object> contentView() {
        Map<String, Object> m = new TreeMap<>();
        m.put("code", String.valueOf(code));
        m.put("type", typeCode + "/" + subtypeCode);
        m.put("geom", String.valueOf(geomNorm));
        m.put("location", String.valueOf(locationNodeId));
        m.put("attrs", asMap(attrs));
        m.put("photos", attachedPhotos());
        m.put("voice", new ArrayList<>(voiceMemos.keySet()));
        m.put("ai", String.valueOf(aiPending()));
        m.put("status", status());
        m.put("versionVector", new TreeMap<>(versionVector));
        m.put("fieldHlc", new TreeMap<>(fieldHlc));
        return m;
    }

    private static Map<String, String> asMap(ObjectNode n) {
        Map<String, String> m = new TreeMap<>();
        n.properties().forEach(f -> m.put(f.getKey(), f.getValue().toString()));
        return m;
    }
}
