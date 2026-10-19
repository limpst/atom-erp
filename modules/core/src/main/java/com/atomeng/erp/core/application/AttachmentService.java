package com.atomeng.erp.core.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.CoreErrors;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.evidence.Buckets;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.erp.core.adapter.out.persistence.AttachmentJdbcRepository;
import com.atomeng.erp.core.adapter.out.persistence.AttachmentJdbcRepository.Upload;
import com.atomeng.erp.core.api.AttachmentIngestApi;
import com.atomeng.erp.core.api.AttachmentStoredEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
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
 * 첨부 업로드·무결성 (설계 11.2 단계 1~6, 00-IMPL Step 8).
 * <ol>
 *   <li>presign: 기기가 계산한 SHA-256·촬영시각을 받아 temp-upload 서명 URL(15분) 발급</li>
 *   <li>클라이언트 PUT</li>
 *   <li>:complete: 서버가 임시 객체를 스트리밍 해시 재계산 → 불일치면 temp 삭제 + 422 CORE-422-010 (TC-CORE-006)</li>
 *   <li>일치: field-original(WORM)로 서버측 복사 → 체인 잠금 → chain_hash 계산 → attachment INSERT
 *       + outbox core.attachment.stored + 감사 로그 (한 트랜잭션) → temp 삭제</li>
 * </ol>
 */
@Service
public class AttachmentService implements AttachmentIngestApi {

    private static final Logger log = LoggerFactory.getLogger(AttachmentService.class);
    private static final Duration PRESIGN_TTL = Duration.ofMinutes(15);
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern EXT = Pattern.compile("^[a-z0-9]{1,8}$");

    public record PresignCommand(String fileName, String mimeType, long sizeBytes, String sha256, Instant capturedAt,
                                 String ownerModule, String ownerType, UUID ownerId, UUID chainScope) {}

    public record PresignResult(UUID uploadId, String uploadUrl, String tempKey, Instant expiresAt) {}

    public record Stored(UUID attachmentId, String bucket, String objectKey, String sha256, String prevHash,
                         String chainHash, UUID chainScope) {}

    public record ChainReport(UUID chainScope, boolean valid, int length, UUID brokenAt, String reason) {}

    private final AttachmentJdbcRepository repo;
    private final ObjectStorage storage;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;
    private final TransactionTemplate tx;

    public AttachmentService(AttachmentJdbcRepository repo, ObjectStorage storage, OutboxPublisher outbox,
                             AuditLog audit, CurrentActor actor, Clock clock, PlatformTransactionManager txManager) {
        this.repo = repo;
        this.storage = storage;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    public PresignResult presign(PresignCommand c) {
        String sha = c.sha256() == null ? "" : c.sha256().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(sha).matches()) {
            throw new DomainException(CoreErrors.VALIDATION_FAILED, "sha256 은 소문자 16진수 64자",
                    List.of(Map.of("field", "sha256", "reason", "PATTERN")));
        }
        UUID uploadId = UuidV7.next();
        String tempKey = uploadId + "/" + safeName(c.fileName());
        var presigned = storage.presignPut(Buckets.TEMP_UPLOAD, tempKey, PRESIGN_TTL);
        Instant captured = c.capturedAt() == null ? null : c.capturedAt().truncatedTo(ChronoUnit.MICROS);
        var u = new Upload(uploadId, Buckets.TEMP_UPLOAD, tempKey, c.fileName(), c.mimeType(), c.sizeBytes(), sha,
                c.ownerModule(), c.ownerType(), c.ownerId(), c.chainScope(), captured, "PENDING", null,
                presigned.expiresAt());
        tx.executeWithoutResult(s -> repo.insertUpload(u, actor.actorIdOrSystem()));
        return new PresignResult(uploadId, presigned.url().toString(), tempKey, presigned.expiresAt());
    }

    public Stored complete(UUID uploadId) {
        Upload u = tx.execute(s -> repo.lockUpload(uploadId)).orElse(null);
        if (u == null) {
            throw new DomainException(CoreErrors.NOT_FOUND, "업로드 세션 없음: " + uploadId);
        }
        // SEC-CORE-002: 다른 사용자가 만든 업로드 세션을 확정(체인 편입)할 수 없다 — 세션 id 를 알아도 남의 증거를 확정 · 소진 불가
        UUID by = actor.actorIdOrSystem();
        if (!repo.uploadCreatedBy(uploadId).map(by::equals).orElse(false)) {
            throw new DomainException(CoreErrors.PERMISSION_DENIED, "업로드를 시작한 사용자만 확정할 수 있습니다");
        }
        if (!"PENDING".equals(u.status())) {
            throw new DomainException(CoreErrors.UPLOAD_NOT_PENDING, "이미 처리된 업로드입니다(" + u.status() + ")");
        }
        Instant now = clock.instant();
        if (u.expiresAt().isBefore(now)) {
            reject(u, "EXPIRED", "presign 만료");
            throw new DomainException(CoreErrors.UPLOAD_EXPIRED, "업로드 URL 이 만료되었습니다");
        }
        if (!storage.exists(u.tempBucket(), u.tempKey())) {
            throw new DomainException(CoreErrors.UPLOAD_OBJECT_MISSING, "업로드된 파일이 없습니다");
        }
        String actual;
        try (InputStream in = storage.get(u.tempBucket(), u.tempKey())) {
            actual = HashChain.sha256(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (!actual.equals(u.claimedSha256())) {
            reject(u, "REJECTED", "sha256 불일치");
            log.warn("attachment hash mismatch upload={} (claimed≠actual) — temp 객체 삭제", uploadId);
            throw new DomainException(CoreErrors.ATTACHMENT_HASH_MISMATCH,
                    "서버 재계산 SHA-256 이 기기 해시와 다릅니다(위조·전송 손상 의심)");
        }

        UUID attachmentId = UuidV7.next();
        String objectKey = objectKey(u, attachmentId, now);
        storage.copy(u.tempBucket(), u.tempKey(), Buckets.FIELD_ORIGINAL, objectKey); // WORM 보관
        Stored stored = tx.execute(s -> {
            var locked = repo.lockUpload(uploadId).orElseThrow();
            if (!"PENDING".equals(locked.status())) { // 동시 :complete 경합
                throw new DomainException(CoreErrors.UPLOAD_NOT_PENDING, "이미 처리된 업로드입니다");
            }
            repo.lockChain(u.chainScope());
            String prev = repo.tailHash(u.chainScope()).orElse(null);
            String chainHash = HashChain.link(prev, actual, u.capturedAt(), u.ownerId());
            repo.insertAttachment(attachmentId, Buckets.FIELD_ORIGINAL, objectKey, u, actual, prev, chainHash, by);
            repo.markUpload(uploadId, "COMPLETED", null, attachmentId, clock.instant());
            outbox.publish(new AttachmentStoredEvent(attachmentId, Buckets.FIELD_ORIGINAL, objectKey, u.mimeType(),
                    u.chainScope(), actual));
            Map<String, Object> after = new HashMap<>();
            after.put("objectKey", objectKey);
            after.put("sha256", actual);
            after.put("chainHash", chainHash);
            audit.append("CORE", AuditLog.Action.CREATE, "Attachment", attachmentId, null, after);
            return new Stored(attachmentId, Buckets.FIELD_ORIGINAL, objectKey, actual, prev, chainHash,
                    u.chainScope());
        });
        storage.delete(u.tempBucket(), u.tempKey());
        return stored;
    }

    /**
     * core::api {@link AttachmentIngestApi} — 서버가 조립한 원본(FLD 이어 올리기)을 presign 과 같은 업로드 세션으로 temp 에 쓰고
     * :complete 로 확정한다(해시 재계산 · WORM · 체인 · outbox 는 동일 경로).
     */
    @Override
    public Ingested ingest(IngestCommand c, java.util.function.Supplier<InputStream> content) {
        PresignResult p = presign(new PresignCommand(c.fileName(), c.mimeType(), c.sizeBytes(), c.sha256(),
                c.capturedAt(), c.ownerModule(), c.ownerType(), c.ownerId(), c.chainScope()));
        try (InputStream in = content.get()) {
            storage.put(Buckets.TEMP_UPLOAD, p.tempKey(), in, c.mimeType());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Stored s = complete(p.uploadId());
        return new Ingested(s.attachmentId(), s.bucket(), s.objectKey(), s.sha256(), s.prevHash(), s.chainHash(),
                s.chainScope());
    }

    @Override
    public java.util.Optional<ObjectCheck> verifyObject(UUID attachmentId) {
        return repo.stored(attachmentId).map(a -> {
            String actual;
            try (InputStream in = storage.get(a[0], a[1])) {
                actual = HashChain.sha256(in);
            } catch (IOException | RuntimeException e) {
                actual = null;   // 원본 유실도 불일치
            }
            return new ObjectCheck(attachmentId, a[2], actual, a[2].equals(actual));
        });
    }

    @Override
    public ChainCheck checkChain(UUID chainScope) {
        ChainReport r = verifyChain(chainScope);
        return new ChainCheck(r.chainScope(), r.valid(), r.length(), r.brokenAt(), r.reason());
    }

    /** 체인 검증: GENESIS 1개에서 시작해 끊김·분기·변조 없이 모든 고리를 지나야 valid */
    public ChainReport verifyChain(UUID chainScope) {
        var links = repo.chain(chainScope);
        if (links.isEmpty()) {
            return new ChainReport(chainScope, true, 0, null, null);
        }
        Map<String, AttachmentJdbcRepository.Link> byPrev = new HashMap<>();
        for (var l : links) {
            String k = l.prevHash() == null ? "GENESIS" : l.prevHash();
            if (byPrev.put(k, l) != null) {
                return new ChainReport(chainScope, false, links.size(), l.id(), "분기(fork)");
            }
        }
        String prev = "GENESIS";
        int walked = 0;
        var cur = byPrev.get(prev);
        while (cur != null) {
            String expected = HashChain.link(cur.prevHash(), cur.sha256(), cur.capturedAt(), cur.ownerId());
            if (!expected.equals(cur.chainHash())) {
                return new ChainReport(chainScope, false, links.size(), cur.id(), "chain_hash 불일치");
            }
            walked++;
            cur = byPrev.get(cur.chainHash());
        }
        if (walked != links.size()) {
            return new ChainReport(chainScope, false, links.size(), null, "끊긴 고리 " + (links.size() - walked) + "개");
        }
        return new ChainReport(chainScope, true, walked, null, null);
    }

    private void reject(Upload u, String status, String reason) {
        storage.delete(u.tempBucket(), u.tempKey());
        tx.executeWithoutResult(s -> repo.markUpload(u.id(), status, reason, null, clock.instant()));
    }

    private static String objectKey(Upload u, UUID attachmentId, Instant now) {
        String name = u.fileName() == null ? "" : u.fileName();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!EXT.matcher(ext).matches()) {
            ext = "bin";
        }
        int year = now.atZone(ZoneOffset.UTC).getYear();
        return "%d/%s/%s/%s.%s".formatted(year, u.ownerModule().toLowerCase(Locale.ROOT), u.chainScope(),
                attachmentId, ext);
    }

    private static String safeName(String fileName) {
        String n = fileName == null ? "file" : fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isBlank() || n.contains("..") ? "file" : n;
    }
}
