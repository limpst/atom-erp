package com.atomeng.erp.dem.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.atomeng.erp.dem.api.DemEvents;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 증거 사진 (FR-DEM-008, 10-IMPL Step 6, TC-DEM-009) — 사진 원본은 core 첨부 파이프라인(presign → PUT → :complete,
 * 서버 해시 재계산 · WORM · 해시 체인)으로 올리고, DEM 은 첨부를 증거 사진으로 등록한다.
 * <ul>
 *   <li>등록: 기기 해시 = core.attachment.sha256 = 저장소 원본 재계산 해시여야 함 → 아니면 409 + 보안 감사(별도 트랜잭션)</li>
 *   <li>같은 첨부 · 같은 해시 재등록 = 기존 photoId(멱등, UNIQUE sha256)</li>
 *   <li>검증 API: 원본 재해시 → MATCH / MISMATCH(불일치는 증거 패키지 제외 표시 + 보안 감사)</li>
 *   <li>{@code dem.evidence-photo.registered} → AIP 위험요소 후보 탐지(FR-DEM-020)</li>
 * </ul>
 */
@Service
public class EvidenceService {

    public record Register(UUID attachmentId, String sha256, UUID workPackageId, Instant capturedAt, BigDecimal lat,
                           BigDecimal lng, String deviceId) {}

    public record Registered(boolean created, Map<String, Object> body) {}

    private final DemJdbc dem;
    private final ObjectStorage storage;
    private final DemSecurityAudit security;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final Clock clock;

    private final DemAccess access;

    public EvidenceService(DemJdbc dem, ObjectStorage storage, DemSecurityAudit security, OutboxPublisher outbox,
                           CurrentActor actor, Clock clock, DemAccess access) {
        this.access = access;
        this.dem = dem;
        this.storage = storage;
        this.security = security;
        this.outbox = outbox;
        this.actor = actor;
        this.clock = clock;
    }

    @Transactional
    public Registered register(Register r) {
        String claimed = r.sha256().toLowerCase(java.util.Locale.ROOT);
        var existing = dem.jdbc().queryForList("SELECT id, work_package_id FROM dem.evidence_photo "
                + "WHERE attachment_id = ? OR sha256 = ? ORDER BY received_at LIMIT 1", r.attachmentId(), claimed);
        dem.one("업무 패키지", "SELECT id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL", r.workPackageId());
        access.requirePackage("dem:evidence:register", r.workPackageId(), true);   // DEM-02: 투입 인원 · 패키지 감리자
        var att = dem.one("첨부", """
                SELECT bucket, object_key, sha256, owner_module, chain_scope, created_by FROM core.attachment
                 WHERE id = ? AND deleted_at IS NULL""", r.attachmentId());
        // 보안 검토 2026-10-19: 다른 모듈(FLD · RPT …)의 첨부나 남이 올린 첨부를 id 만 알고 증거 사진으로 끌어오지 못하게 한다.
        // DEM 첨부 파이프라인으로 호출자 본인이 올린 첨부만 등록(시스템 실행은 웹 경계 밖이라 작성자 검사 생략).
        if (!"DEM".equals(att.get("owner_module"))) {
            throw new DomainException(DemErrors.INVALID, "DEM 증거 사진 첨부(ownerModule=DEM)만 등록할 수 있습니다");
        }
        if (actor.current().isPresent() && !actor.actorIdOrSystem().equals(att.get("created_by"))) {
            throw new DomainException(DemErrors.INVALID, "본인이 올린 첨부만 증거 사진으로 등록할 수 있습니다");
        }
        String recorded = ((String) att.get("sha256")).trim();
        String server = rehash((String) att.get("bucket"), (String) att.get("object_key"));
        if (!claimed.equals(recorded) || !recorded.equals(server)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("attachmentId", r.attachmentId().toString());
            detail.put("client", claimed);
            detail.put("attachment", recorded);
            detail.put("server", server);
            detail.put("deviceId", r.deviceId());
            security.hashMismatch("evidence-photo", r.attachmentId(), detail);
            throw new DomainException(DemErrors.EVIDENCE_HASH_MISMATCH, "사진 해시 불일치(위변조 의심)")
                    .with("reason", "EVIDENCE_HASH_MISMATCH").with("serverSha256", server);
        }
        if (!existing.isEmpty()) {
            // 같은 사진(첨부 · 해시)이 다른 업무 패키지에 이미 있으면 그 패키지의 사진 정보를 돌려주지 않는다(sha256 UNIQUE)
            if (!r.workPackageId().equals(existing.get(0).get("work_package_id"))) {
                throw new DomainException(DemErrors.EVIDENCE_DUPLICATE_OTHER_PACKAGE,
                        "같은 사진이 다른 업무 패키지에 이미 등록되어 있습니다");
            }
            return new Registered(false, view((UUID) existing.get(0).get("id")));
        }
        // 보안 검토 CORE-01(a): 첨부의 체인 범위(= 업로드 때 소유자 확인을 거친 업무 패키지)가 등록 대상 패키지여야 한다
        if (!r.workPackageId().equals(att.get("chain_scope"))) {
            throw new DomainException(DemErrors.INVALID, "다른 업무 패키지 체인으로 올린 첨부입니다");
        }
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.evidence_photo(id, attachment_id, work_package_id, sha256, captured_at, received_at, lat,
                                               lng, device_id, last_verify, last_verified_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'MATCH', ?, ?)""", id, r.attachmentId(), r.workPackageId(), server,
                DemJdbc.ts(r.capturedAt()), Timestamp.from(clock.instant()), r.lat(), r.lng(), r.deviceId(),
                Timestamp.from(clock.instant()), actor.actorIdOrSystem());
        outbox.publish(new DemEvents.EvidencePhotoRegistered(id, r.attachmentId(), server, r.workPackageId(), null));
        return new Registered(true, view(id));
    }

    private String rehash(String bucket, String key) {
        try (InputStream in = storage.get(bucket, key)) {
            return HashChain.sha256(in);
        } catch (java.io.IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 원본 재해시 → MATCH / MISMATCH (TC-DEM-009) */
    @Transactional
    public Map<String, Object> verify(UUID photoId) {
        var p = dem.one("증거 사진", """
                SELECT p.id, p.work_package_id, p.sha256, a.bucket, a.object_key FROM dem.evidence_photo p
                  JOIN core.attachment a ON a.id = p.attachment_id WHERE p.id = ?""", photoId);
        access.requirePackage("dem:evidence:verify", (UUID) p.get("work_package_id"), false);   // DEM-02
        String expected = (String) p.get("sha256");
        String actual;
        try {
            actual = rehash((String) p.get("bucket"), (String) p.get("object_key"));
        } catch (RuntimeException e) {
            actual = null;   // 원본 유실도 불일치
        }
        String result = expected.equals(actual) ? "MATCH" : "MISMATCH";
        Instant now = clock.instant();
        dem.jdbc().update("UPDATE dem.evidence_photo SET last_verify = ?, last_verified_at = ? WHERE id = ?", result,
                Timestamp.from(now), photoId);
        if ("MISMATCH".equals(result)) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("photoId", photoId.toString());
            detail.put("expected", expected);
            detail.put("actual", actual);
            security.hashMismatch("evidence-photo", photoId, detail);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("photoId", photoId);
        m.put("result", result);
        m.put("expectedSha256", expected);
        m.put("actualSha256", actual);
        m.put("verifiedAt", now);
        m.put("includedInEvidencePackage", "MATCH".equals(result));
        return m;
    }

    public Map<String, Object> view(UUID photoId) {
        var p = dem.one("증거 사진", """
                SELECT id, attachment_id, work_package_id, sha256, captured_at, received_at, lat, lng, device_id,
                       last_verify FROM dem.evidence_photo WHERE id = ?""", photoId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.get("id"));
        m.put("attachmentId", p.get("attachment_id"));
        m.put("workPackageId", p.get("work_package_id"));
        m.put("sha256", p.get("sha256"));
        m.put("capturedAt", DemJdbc.inst(p.get("captured_at")));
        m.put("receivedAt", DemJdbc.inst(p.get("received_at")));
        m.put("lat", p.get("lat"));
        m.put("lng", p.get("lng"));
        m.put("deviceId", p.get("device_id"));
        m.put("lastVerify", p.get("last_verify"));
        return m;
    }

    /** 분쟁 대응 증거 목록(manifest 행) — 최근 검증 MISMATCH 는 제외 표시 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> manifest(UUID workPackageId) {
        access.requirePackage("dem:evidence:verify", workPackageId, false);   // DEM-02
        return dem.jdbc().queryForList("SELECT id FROM dem.evidence_photo WHERE work_package_id = ? ORDER BY received_at, id",
                UUID.class, workPackageId).stream().map(this::view).toList();
    }
}
