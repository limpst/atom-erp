package com.atomeng.erp.fld.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.aip.api.AipJobApi;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc.Round;
import com.atomeng.erp.fld.api.FldEvents;
import com.atomeng.erp.fld.domain.DefectState;
import com.atomeng.erp.fld.domain.Hlc;
import com.atomeng.erp.fld.domain.StoredEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 제안 (FR-FLD-012, BR-FLD-004, 05-IMPL Step 14 일부).
 * <ul>
 *   <li>분석 요청: 검증된 사진을 aip::api {@link AipJobApi} 동기 Job(CRACK_MEASURE)으로 보낸다 — 워커 미설정(기본)이면 DISABLED</li>
 *   <li>제안 첨부: fld.ai_suggestion + 서버 이벤트 AiSuggestionAttached(값은 attrs 가 아니라 제안에만 — 사람 수락 전 보고서 미반영)</li>
 *   <li>사람 확정 균열폭과 2배 이상 차이 → {@code fld.ai-suggestion.discrepancy}(QAR)</li>
 * </ul>
 * 결정(수락 · 수정 · 거절)은 앱이 오프라인 op(AiSuggestionDecided)로 보낸다(SyncService).
 */
@Service
public class AiSuggestionService {

    public record Attach(UUID defectId, UUID photoId, String modelVersion, UUID jobId, BigDecimal widthMm,
                         BigDecimal confidence, JsonNode result) {}

    private final FldJdbc fld;
    private final FldAccess access;
    private final AipJobApi aip;
    private final AiDiscrepancyCheck discrepancy;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final Clock clock;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public AiSuggestionService(FldJdbc fld, FldAccess access, AipJobApi aip, AiDiscrepancyCheck discrepancy,
                               OutboxPublisher outbox, CurrentActor actor, Clock clock,
                               org.springframework.transaction.PlatformTransactionManager txManager) {
        this.tx = new org.springframework.transaction.support.TransactionTemplate(txManager);
        this.fld = fld;
        this.access = access;
        this.aip = aip;
        this.discrepancy = discrepancy;
        this.outbox = outbox;
        this.actor = actor;
        this.clock = clock;
    }

    @Transactional
    public Map<String, Object> attach(Attach a) {
        List<StoredEvent> events = fld.events(a.defectId());
        if (events.isEmpty()) {
            throw new DomainException(FldErrors.NOT_FOUND, "결함 없음: " + a.defectId());
        }
        DefectState s = DefectState.replay(a.defectId(), events);
        Round round = fld.round(s.roundId());
        if (a.modelVersion() == null || a.modelVersion().isBlank()) {
            throw new DomainException(FldErrors.INVALID, "modelVersion 필수");
        }
        if (a.photoId() != null) {
            fld.one("사진", "SELECT id FROM fld.photo WHERE id = ? AND round_id = ?", a.photoId(), round.id());
        }
        fld.jdbc().queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class,
                "fld.round:" + round.id());
        Instant now = clock.instant();
        UUID id = UuidV7.next();
        ObjectNode result = a.result() != null && a.result().isObject() ? (ObjectNode) a.result().deepCopy()
                : fld.om().createObjectNode();
        fld.jdbc().update("""
                INSERT INTO fld.ai_suggestion(id, defect_id, photo_id, round_id, model_version, job_id, result, width_mm,
                                              confidence, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""", id, a.defectId(), a.photoId(), round.id(),
                a.modelVersion(), a.jobId(), fld.json(result), a.widthMm(), a.confidence(), Timestamp.from(now));
        ObjectNode payload = fld.om().createObjectNode();
        payload.put("suggestionId", id.toString());
        payload.put("modelVersion", a.modelVersion());
        if (a.widthMm() != null) {
            payload.put("widthMm", a.widthMm());
        }
        if (a.confidence() != null) {
            payload.put("confidence", a.confidence());
        }
        if (a.photoId() != null) {
            payload.put("photoId", a.photoId().toString());
        }
        String last = s.versionVector().values().stream().max(Hlc::compare).orElse(null);
        Hlc hlc = last == null ? new Hlc(now.toEpochMilli(), 0, SyncService.SERVER_NODE)
                : Hlc.parse(last).successor(now.toEpochMilli(), SyncService.SERVER_NODE);
        UUID opId = UuidV7.next();
        long seq = fld.appendEvent(opId, a.defectId(), round.id(), s.zoneId(), "AiSuggestionAttached", payload,
                hlc.pack(), SyncService.SERVER_DEVICE, actor.actorIdOrSystem(), false, now);
        s.apply(new StoredEvent(seq, opId, a.defectId(), round.id(), s.zoneId(), "AiSuggestionAttached", payload,
                hlc.pack(), SyncService.SERVER_DEVICE, actor.actorIdOrSystem(), false, now));
        fld.upsertProjection(s);
        outbox.publish(new FldEvents.DefectChanged(round.id(), round.projectId(), List.of(a.defectId()), seq, seq, 1));
        BigDecimal ratio = discrepancy.check(s, id, round.projectId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("suggestionId", id);
        m.put("defectId", a.defectId());
        m.put("seq", seq);
        m.put("defectStatus", s.status());
        m.put("discrepancyRatio", ratio);
        return m;
    }

    /** 검증된 사진 → AIP 동기 Job(CRACK_MEASURE). 워커 꺼짐 → status DISABLED(제안 없음, ERP 다른 기능 영향 없음) */
    public Map<String, Object> analyze(UUID photoId) {
        Map<String, Object> p = fld.one("사진", """
                SELECT p.id, p.defect_id, p.attachment_id, p.device_sha256, p.upload_state, r.project_id
                  FROM fld.photo p JOIN fld.survey_round r ON r.id = p.round_id WHERE p.id = ?""", photoId);
        UUID projectId = (UUID) p.get("project_id");
        access.requireProject("fld:ai-suggestion:request", projectId);
        if (!"VERIFIED".equals(p.get("upload_state")) || p.get("defect_id") == null) {
            throw new DomainException(FldErrors.INVALID, "결함에 연결된 검증(VERIFIED) 사진만 분석할 수 있습니다");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("photoId", photoId);
        if (!aip.enabled()) {
            m.put("status", "DISABLED");
            return m;
        }
        ObjectNode req = fld.om().createObjectNode();
        req.put("photoId", photoId.toString());
        req.put("attachmentId", String.valueOf(p.get("attachment_id")));
        req.put("sha256", ((String) p.get("device_sha256")).trim());
        AipJobApi.SyncResult r;
        try {
            r = aip.runSync(new AipJobApi.SyncRequest(AipServiceCode.CRACK_MEASURE, "FLD", projectId,
                    "fld-photo:" + photoId, req, Duration.ofMinutes(2)));
        } catch (AipJobApi.AipUnavailableException | AipJobApi.AipTimeoutException e) {
            m.put("status", e instanceof AipJobApi.AipDisabledException ? "DISABLED" : "UNAVAILABLE");
            m.put("message", e.getMessage());
            return m;
        }
        JsonNode out = r.output() == null ? fld.om().createObjectNode() : r.output();
        // 워커 계약(services/aip-worker contract CRACK_MEASURE): components[].widthMaxMm — 스케일 없으면 폭 없음(MEASURE_REQUIRED)
        BigDecimal width = null;
        for (JsonNode c : out.path("components")) {
            if (c.path("widthMaxMm").isNumber() && (width == null || c.get("widthMaxMm").decimalValue().compareTo(width) > 0)) {
                width = c.get("widthMaxMm").decimalValue();
            }
        }
        final BigDecimal fWidth = width;
        BigDecimal conf = out.path("confidence").isNumber() ? out.get("confidence").decimalValue() : null;
        Map<String, Object> attached = tx.execute(t -> attach(new Attach((UUID) p.get("defect_id"), photoId,
                r.modelVersion() == null ? String.valueOf(r.model()) : r.modelVersion(), r.jobId(), fWidth, conf, out)));
        m.put("status", "SUGGESTED");
        m.putAll(attached);
        return m;
    }
}
