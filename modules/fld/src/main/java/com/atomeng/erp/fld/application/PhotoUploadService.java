package com.atomeng.erp.fld.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.core.util.Hashing;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.evidence.Buckets;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.common.evidence.MediaSniffer;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.erp.core.api.AttachmentIngestApi;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc;
import com.atomeng.erp.fld.adapter.out.persistence.FldJdbc.Round;
import com.atomeng.erp.fld.api.FldEvents;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 사진 이어 올리기 (FR-FLD-017, BR-FLD-002, 05-IMPL Step 9) — tus 1.0 core 의미를 ERP 엔드포인트로 구현(tusd 미도입).
 * <ol>
 *   <li>생성: photoId · 기기 SHA-256 · 크기 → 세션(같은 사진의 열린 세션이 있으면 그것을 돌려줌 = 앱 재시작 후 재개)</li>
 *   <li>HEAD: 서버가 받은 바이트(Upload-Offset) — 끊긴 뒤 여기서부터 이어서 보낸다(이미 보낸 청크 재전송 없음, TC-FLD-005)</li>
 *   <li>PATCH: Upload-Offset 이 서버 값과 같을 때만 청크 저장(아니면 409 + 현재 offset), 선택 Upload-Checksum(sha256 base64)</li>
 *   <li>마지막 청크: 청크를 이어 스트리밍 SHA-256 → 기기 해시와 다르면 사진 REJECTED · 청크 삭제 · 409(TC-FLD-006, 재업로드 지시는
 *       pull 의 reuploadRequests) / 같으면 core::api {@link AttachmentIngestApi} 로 WORM 원본 · 회차 해시 체인 확정 →
 *       VERIFIED + {@code fld.photo.verified}</li>
 * </ol>
 * 청크는 temp-upload 버킷 {@code fld/{uploadId}/{offset}} 객체로 둔다(완료 · 거부 시 삭제).
 */
@Service
public class PhotoUploadService {

    private static final Logger log = LoggerFactory.getLogger(PhotoUploadService.class);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");

    public record CreateUpload(UUID photoId, UUID roundId, UUID defectId, UUID deviceId, String sha256, Long sizeBytes,
                               String mimeType, String kind, Instant takenAt, JsonNode exif) {}

    /** status = OPEN · COMPLETED · REJECTED, photoState = PENDING · UPLOADING · VERIFIED · REJECTED */
    public record UploadState(UUID uploadId, UUID photoId, long offset, long length, String status, String photoState,
                              boolean created) {}

    private final FldJdbc fld;
    private final FldAccess access;
    private final FldProperties props;
    private final ObjectStorage storage;
    private final AttachmentIngestApi attachments;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final Clock clock;
    private final TransactionTemplate tx;

    public PhotoUploadService(FldJdbc fld, FldAccess access, FldProperties props, ObjectStorage storage,
                              AttachmentIngestApi attachments, OutboxPublisher outbox, CurrentActor actor, Clock clock,
                              PlatformTransactionManager txManager) {
        this.fld = fld;
        this.access = access;
        this.props = props;
        this.storage = storage;
        this.attachments = attachments;
        this.outbox = outbox;
        this.actor = actor;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public int maxChunkBytes() {
        return props.upload().maxChunkBytes();
    }

    // ------------------------------------------------------------------ 생성 (tus POST /files · pre-create 훅)

    public UploadState create(CreateUpload c) {
        String sha = c.sha256() == null ? "" : c.sha256().toLowerCase(Locale.ROOT);
        if (c.photoId() == null || c.roundId() == null || !SHA256.matcher(sha).matches() || c.sizeBytes() == null
                || c.sizeBytes() <= 0) {
            throw new DomainException(FldErrors.INVALID, "photoId · roundId · sha256(64 hex) · sizeBytes(>0) 필수");
        }
        // 보안 검토 FLD-02: 신고 크기 상한 · 사진 MIME 허용 목록(실제 형식은 완료 때 매직 바이트로 확인)
        if (c.sizeBytes() > props.upload().maxFileBytes()) {
            throw new DomainException(FldErrors.FILE_TOO_LARGE, "사진 상한 " + props.upload().maxFileBytes()
                    + " 바이트 초과").with("maxFileBytes", props.upload().maxFileBytes());
        }
        String mime = c.mimeType() == null || c.mimeType().isBlank() ? "image/jpeg" : MediaSniffer.normalize(c.mimeType());
        if (!mime.startsWith("image/") || !MediaSniffer.allowed(mime)) {
            throw new DomainException(FldErrors.MEDIA_TYPE, "허용되지 않는 사진 형식: " + c.mimeType());
        }
        Round round = fld.round(c.roundId());
        access.requireProject("fld:photo:upload", round.projectId());
        // 보안 검토 FLD-05: 결함은 같은 회차의 결함이어야 한다(다른 프로젝트 결함에 사진을 붙이지 못하게)
        if (c.defectId() != null && fld.jdbc().queryForObject(
                "SELECT count(*) FROM fld.defect_current WHERE id = ? AND round_id = ?", Integer.class,
                c.defectId(), c.roundId()) == 0) {
            throw new DomainException(FldErrors.INVALID, "defectId 가 이 회차의 결함이 아닙니다");
        }
        UUID me = actor.actorIdOrSystem();
        return tx.execute(s -> {
            List<Map<String, Object>> rows = fld.jdbc().queryForList(
                    "SELECT id, round_id, device_sha256, upload_state FROM fld.photo WHERE id = ? OR device_sha256 = ? "
                            + "FOR UPDATE", c.photoId(), sha);
            Map<String, Object> photo = null;
            for (Map<String, Object> r : rows) {
                if (!r.get("id").equals(c.photoId())) {
                    DomainException dup = new DomainException(FldErrors.PHOTO_EXISTS, "같은 원본(해시)이 다른 사진으로 이미 등록됨");
                    // 보안 검토 FLD-05: 다른 회차(다른 프로젝트일 수 있음) 사진 id 는 알려 주지 않는다
                    throw c.roundId().equals(r.get("round_id")) ? dup.with("existingPhotoId", r.get("id")) : dup;
                }
                photo = r;
            }
            Instant now = clock.instant();
            if (photo == null) {
                fld.jdbc().update("""
                        INSERT INTO fld.photo(id, round_id, defect_id, owner_type, kind, device_sha256, size_bytes,
                                              mime_type, exif, taken_at, device_id, upload_state, created_at, created_by)
                        VALUES (?, ?, ?, 'DEFECT', ?, ?, ?, ?, ?::jsonb, ?, ?, 'PENDING', ?, ?)""", c.photoId(),
                        c.roundId(), c.defectId(), kind(c.kind()), sha, c.sizeBytes(), mime,
                        c.exif() == null ? "{}" : fld.json(c.exif()), FldJdbc.ts(c.takenAt()),
                        c.deviceId() == null ? actor.actorIdOrSystem() : c.deviceId(), Timestamp.from(now),
                        actor.actorIdOrSystem());
            } else {
                if (!sha.equals(((String) photo.get("device_sha256")).trim())
                        || !c.roundId().equals(photo.get("round_id"))) {
                    throw new DomainException(FldErrors.PHOTO_EXISTS, "photoId 의 해시 · 회차가 기존 등록과 다릅니다");
                }
                if ("VERIFIED".equals(photo.get("upload_state"))) {
                    throw new DomainException(FldErrors.PHOTO_EXISTS, "이미 검증된 사진입니다(중복 업로드 불필요)")
                            .with("photoState", "VERIFIED");
                }
            }
            List<UUID> open = fld.jdbc().queryForList(
                    "SELECT id FROM fld.upload_session WHERE photo_id = ? AND status = 'OPEN'", UUID.class, c.photoId());
            if (!open.isEmpty()) {
                return state(open.get(0), false);
            }
            // 보안 검토 FLD-02: 사용자당 열린(만료 전) 세션 수 상한 — 같은 사용자 생성은 advisory lock 으로 직렬화
            fld.jdbc().queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", "fld-upload:" + me);
            Integer openByMe = fld.jdbc().queryForObject("""
                    SELECT count(*) FROM fld.upload_session WHERE created_by = ? AND status = 'OPEN' AND expires_at > ?""",
                    Integer.class, me, Timestamp.from(clock.instant()));
            if (openByMe != null && openByMe >= props.upload().maxOpenSessionsPerUser()) {
                throw new DomainException(FldErrors.TOO_MANY_SESSIONS, "열린 업로드 세션이 "
                        + props.upload().maxOpenSessionsPerUser() + "개를 넘습니다 — 진행 중인 업로드를 끝내거나 만료를 기다리세요")
                        .with("maxOpenSessions", props.upload().maxOpenSessionsPerUser());
            }
            UUID id = UuidV7.next();
            fld.jdbc().update("""
                    INSERT INTO fld.upload_session(id, photo_id, total_bytes, device_id, created_at, updated_at, expires_at,
                                                   created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, c.photoId(), c.sizeBytes(), c.deviceId(), Timestamp.from(now),
                    Timestamp.from(now), Timestamp.from(now.plus(props.upload().sessionTtl())), actor.actorIdOrSystem());
            fld.jdbc().update("UPDATE fld.photo SET upload_state = 'UPLOADING', size_bytes = ? WHERE id = ? "
                    + "AND upload_state IN ('PENDING','REJECTED')", c.sizeBytes(), c.photoId());
            return state(id, true);
        });
    }

    private static String kind(String k) {
        return k != null && List.of("FAR", "NEAR", "SCALE", "OVERVIEW").contains(k) ? k : "NEAR";
    }

    // ------------------------------------------------------------------ HEAD offset

    public UploadState head(UUID uploadId) {
        UploadState s = state(uploadId, false);
        access.requireProject("fld:photo:upload", projectOfPhoto(s.photoId()));
        return s;
    }

    private UUID projectOfPhoto(UUID photoId) {
        return (UUID) fld.one("사진", """
                SELECT r.project_id FROM fld.photo p JOIN fld.survey_round r ON r.id = p.round_id WHERE p.id = ?""",
                photoId).get("project_id");
    }

    private UploadState state(UUID uploadId, boolean created) {
        Map<String, Object> u = fld.one("업로드 세션", """
                SELECT s.id, s.photo_id, s.received_bytes, s.total_bytes, s.status, p.upload_state
                  FROM fld.upload_session s JOIN fld.photo p ON p.id = s.photo_id WHERE s.id = ?""", uploadId);
        return new UploadState(uploadId, (UUID) u.get("photo_id"), ((Number) u.get("received_bytes")).longValue(),
                ((Number) u.get("total_bytes")).longValue(), (String) u.get("status"), (String) u.get("upload_state"),
                created);
    }

    // ------------------------------------------------------------------ PATCH 청크

    /**
     * @param checksum tus checksum 확장 형식 "sha256 {base64}" (선택)
     */
    public UploadState append(UUID uploadId, long offset, byte[] chunk, String checksum) {
        UploadState before = state(uploadId, false);
        access.requireProject("fld:photo:upload", projectOfPhoto(before.photoId()));
        if (chunk == null || chunk.length == 0) {
            throw new DomainException(FldErrors.INVALID, "빈 청크");
        }
        if (chunk.length > props.upload().maxChunkBytes()) {
            throw new DomainException(FldErrors.CHUNK_TOO_LARGE, "청크 상한 " + props.upload().maxChunkBytes() + " 바이트 초과");
        }
        String chunkSha = Hashing.sha256Hex(chunk);
        if (checksum != null && !checksum.isBlank()) {
            String[] parts = checksum.trim().split("\\s+");
            if (parts.length != 2 || !"sha256".equalsIgnoreCase(parts[0])
                    || !HexFormat.of().formatHex(Base64.getDecoder().decode(parts[1])).equals(chunkSha)) {
                throw new DomainException(FldErrors.CHUNK_CHECKSUM, "청크 체크섬 불일치 — 이 청크를 다시 보내세요")
                        .with("offset", before.offset());
            }
        }
        UploadState after = tx.execute(s -> {
            Map<String, Object> u = fld.one("업로드 세션", "SELECT received_bytes, total_bytes, status, expires_at "
                    + "FROM fld.upload_session WHERE id = ? FOR UPDATE", uploadId);
            long received = ((Number) u.get("received_bytes")).longValue();
            long total = ((Number) u.get("total_bytes")).longValue();
            if (!"OPEN".equals(u.get("status"))) {
                throw new DomainException(FldErrors.UPLOAD_NOT_OPEN, "열린 업로드 세션이 아닙니다(" + u.get("status") + ")")
                        .with("status", u.get("status"));
            }
            if (!FldJdbc.inst(u.get("expires_at")).isAfter(clock.instant())) {
                // 보안 검토 FLD-02: 만료 세션에는 청크를 더 받지 않는다(정리는 expireSessions)
                throw new DomainException(FldErrors.UPLOAD_EXPIRED, "업로드 세션이 만료되었습니다 — 새 세션으로 다시 올리세요")
                        .with("reupload", true);
            }
            if (offset != received) {
                throw new DomainException(FldErrors.OFFSET_MISMATCH, "Upload-Offset " + offset + " ≠ 서버 수신 " + received
                        + " — HEAD 로 확인 후 이어서 보내세요").with("currentOffset", received);
            }
            if (received + chunk.length > total) {
                throw new DomainException(FldErrors.INVALID, "선언 크기(" + total + ")를 넘는 청크");
            }
            String key = "fld/%s/%015d".formatted(uploadId, offset);
            storage.put(Buckets.TEMP_UPLOAD, key, new ByteArrayInputStream(chunk), "application/octet-stream");
            Instant now = clock.instant();
            fld.jdbc().update("""
                    INSERT INTO fld.upload_chunk(session_id, offset_bytes, length, object_key, sha256, received_at)
                    VALUES (?, ?, ?, ?, ?, ?)""", uploadId, offset, chunk.length, key, chunkSha, Timestamp.from(now));
            fld.jdbc().update("UPDATE fld.upload_session SET received_bytes = ?, updated_at = ? WHERE id = ?",
                    received + chunk.length, Timestamp.from(now), uploadId);
            return state(uploadId, false);
        });
        if (after.offset() == after.length()) {
            return finish(uploadId);
        }
        return after;
    }

    // ------------------------------------------------------------------ 완료: SHA-256 검증 → core 첨부

    private UploadState finish(UUID uploadId) {
        Map<String, Object> u = fld.one("업로드 세션", """
                SELECT s.photo_id, s.total_bytes, p.device_sha256, p.round_id, p.defect_id, p.mime_type, p.taken_at,
                       r.project_id, r.evidence_mode
                  FROM fld.upload_session s JOIN fld.photo p ON p.id = s.photo_id
                  JOIN fld.survey_round r ON r.id = p.round_id WHERE s.id = ?""", uploadId);
        UUID photoId = (UUID) u.get("photo_id");
        String expected = ((String) u.get("device_sha256")).trim();
        List<String> keys = fld.jdbc().queryForList(
                "SELECT object_key FROM fld.upload_chunk WHERE session_id = ? ORDER BY offset_bytes", String.class, uploadId);
        // 보안 검토 FLD-02/05: 신고 MIME 을 믿지 않는다 — 조립한 원본의 매직 바이트가 신고 형식과 같아야 한다
        if (!MediaSniffer.matches((String) u.get("mime_type"), MediaSniffer.head(concat(keys)))) {
            reject(uploadId, photoId, "MIME_MISMATCH", keys);
            throw new DomainException(FldErrors.MEDIA_TYPE, "사진 내용이 신고한 형식(" + u.get("mime_type") + ")이 아닙니다")
                    .with("reason", "MIME_MISMATCH").with("photoId", photoId).with("reupload", true);
        }
        String actual;
        try (InputStream in = concat(keys)) {
            actual = HashChain.sha256(in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!actual.equals(expected)) {
            reject(uploadId, photoId, "HASH_MISMATCH", keys);
            log.warn("fld photo hash mismatch photo={} upload={} (device≠server) — REJECTED, 재업로드 지시", photoId, uploadId);
            throw new DomainException(FldErrors.PHOTO_HASH_MISMATCH,
                    "서버 재계산 SHA-256 이 기기 해시와 다릅니다 — 사진을 다시 올리세요(BR-FLD-002)")
                    .with("reason", "HASH_MISMATCH").with("photoId", photoId).with("expectedSha256", expected)
                    .with("serverSha256", actual).with("reupload", true);
        }
        UUID roundId = (UUID) u.get("round_id");
        boolean evidence = Boolean.TRUE.equals(u.get("evidence_mode"));
        // 증거 모드: 체인 시각은 기기 시각이 아니라 서버 수신 시각(A8-5 · TC-FLD-015)
        Instant captured = evidence ? clock.instant() : FldJdbc.inst(u.get("taken_at"));
        AttachmentIngestApi.Ingested stored;
        try {
            stored = attachments.ingest(new AttachmentIngestApi.IngestCommand(actual + ".jpg",
                    (String) u.get("mime_type"), ((Number) u.get("total_bytes")).longValue(), actual, captured, "FLD",
                    "PHOTO", photoId, roundId), () -> concat(keys));
        } catch (DomainException e) {
            reject(uploadId, photoId, "CORE_REJECTED", keys);
            throw new DomainException(FldErrors.PHOTO_HASH_MISMATCH, "core 첨부 검증 실패: " + e.getMessage())
                    .with("reason", "CORE_REJECTED").with("photoId", photoId).with("reupload", true);
        }
        UUID projectId = (UUID) u.get("project_id");
        UUID defectId = (UUID) u.get("defect_id");
        tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            fld.jdbc().update("""
                    UPDATE fld.photo SET upload_state = 'VERIFIED', attachment_id = ?, server_sha256 = ?, chain_hash = ?,
                           verified_at = ?, reject_reason = NULL WHERE id = ?""", stored.attachmentId(), stored.sha256(),
                    stored.chainHash(), Timestamp.from(now), photoId);
            fld.jdbc().update("UPDATE fld.upload_session SET status = 'COMPLETED', updated_at = ? WHERE id = ?",
                    Timestamp.from(now), uploadId);
            outbox.publish(new FldEvents.PhotoVerified(photoId, roundId, projectId, defectId, stored.attachmentId(),
                    stored.sha256(), stored.chainHash()));
        });
        keys.forEach(k -> storage.delete(Buckets.TEMP_UPLOAD, k));
        return state(uploadId, false);
    }

    /**
     * 보안 검토 FLD-02: 만료된 OPEN 세션 정리 — EXPIRED 로 바꾸고 임시 청크 객체 · 행을 지운다(사진은 재업로드 대기 REJECTED).
     *
     * @return 정리한 세션 수
     */
    public int expireSessions() {
        Timestamp now = Timestamp.from(clock.instant());
        List<UUID> expired = fld.jdbc().queryForList("""
                SELECT id FROM fld.upload_session WHERE status = 'OPEN' AND expires_at <= ? ORDER BY expires_at LIMIT 500""",
                UUID.class, now);
        int n = 0;
        for (UUID id : expired) {
            List<String> keys = tx.execute(s -> {
                Map<String, Object> u = fld.jdbc().queryForList(
                        "SELECT photo_id, status FROM fld.upload_session WHERE id = ? FOR UPDATE", id).get(0);
                if (!"OPEN".equals(u.get("status"))) {
                    return null;
                }
                List<String> k = fld.jdbc().queryForList(
                        "SELECT object_key FROM fld.upload_chunk WHERE session_id = ?", String.class, id);
                fld.jdbc().update("DELETE FROM fld.upload_chunk WHERE session_id = ?", id);
                fld.jdbc().update("UPDATE fld.upload_session SET status = 'EXPIRED', reject_reason = 'EXPIRED', "
                        + "updated_at = ? WHERE id = ?", now, id);
                fld.jdbc().update("UPDATE fld.photo SET upload_state = 'REJECTED', reject_reason = 'UPLOAD_EXPIRED' "
                        + "WHERE id = ? AND upload_state = 'UPLOADING'", u.get("photo_id"));
                return k;
            });
            if (keys != null) {
                keys.forEach(k -> storage.delete(Buckets.TEMP_UPLOAD, k));
                n++;
            }
        }
        if (n > 0) {
            log.info("fld 업로드 세션 만료 정리 {}건", n);
        }
        return n;
    }

    private InputStream concat(List<String> keys) {
        List<InputStream> parts = keys.stream().map(k -> storage.get(Buckets.TEMP_UPLOAD, k)).toList();
        return new SequenceInputStream(Collections.enumeration(parts));
    }

    private void reject(UUID uploadId, UUID photoId, String reason, List<String> keys) {
        tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            fld.jdbc().update("UPDATE fld.upload_session SET status = 'REJECTED', reject_reason = ?, updated_at = ? "
                    + "WHERE id = ?", reason, Timestamp.from(now), uploadId);
            fld.jdbc().update("UPDATE fld.photo SET upload_state = 'REJECTED', reject_reason = ?, "
                    + "reject_count = reject_count + 1 WHERE id = ?", reason, photoId);
        });
        keys.forEach(k -> storage.delete(Buckets.TEMP_UPLOAD, k));
    }

    // ------------------------------------------------------------------ 사진 원본 재검증

    public Map<String, Object> verify(UUID photoId) {
        Map<String, Object> p = fld.one("사진", """
                SELECT p.id, p.device_sha256, p.attachment_id, p.upload_state, r.project_id
                  FROM fld.photo p JOIN fld.survey_round r ON r.id = p.round_id WHERE p.id = ?""", photoId);
        access.requireProject("fld:photo:verify", (UUID) p.get("project_id"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("photoId", photoId);
        m.put("uploadState", p.get("upload_state"));
        m.put("deviceSha256", ((String) p.get("device_sha256")).trim());
        UUID att = (UUID) p.get("attachment_id");
        if (att == null) {
            m.put("result", "NOT_UPLOADED");
            return m;
        }
        var check = attachments.verifyObject(att).orElse(null);
        boolean match = check != null && check.match() && check.recordedSha256().equals(m.get("deviceSha256"));
        m.put("attachmentId", att);
        m.put("serverSha256", check == null ? null : check.actualSha256());
        m.put("result", match ? "MATCH" : "MISMATCH");
        return m;
    }

    /** 회차 증거 체인(core 첨부 chain_scope = roundId) 전체 검증 — 끊김 · 분기 · 변조 위치 */
    public Map<String, Object> verifyChain(UUID roundId) {
        Round r = fld.round(roundId);
        access.requireProject("fld:photo:verify", r.projectId());
        var c = attachments.checkChain(roundId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roundId", roundId);
        m.put("evidenceMode", r.evidenceMode());
        m.put("valid", c.valid());
        m.put("length", c.length());
        m.put("brokenAt", c.brokenAt());
        m.put("reason", c.reason());
        return m;
    }
}
