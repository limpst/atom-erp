package com.atomeng.erp.core.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * core.attachment / core.attachment_upload JDBC 저장소. attachment 는 append-only(트리거)라 공통 6컬럼
 * BaseEntity 대신 JDBC 로 다룬다.
 */
@Repository
public class AttachmentJdbcRepository {

    public record Upload(UUID id, String tempBucket, String tempKey, String fileName, String mimeType, long sizeBytes,
                         String claimedSha256, String ownerModule, String ownerType, UUID ownerId, UUID chainScope,
                         Instant capturedAt, String status, UUID attachmentId, Instant expiresAt) {}

    public record Link(UUID id, String sha256, String prevHash, String chainHash, Instant capturedAt, UUID ownerId) {}

    private final JdbcTemplate jdbc;

    public AttachmentJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertUpload(Upload u, UUID createdBy) {
        jdbc.update("""
                INSERT INTO core.attachment_upload(id, temp_bucket, temp_key, file_name, mime_type, size_bytes,
                    claimed_sha256, owner_module, owner_type, owner_id, chain_scope, captured_at, expires_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                u.id(), u.tempBucket(), u.tempKey(), u.fileName(), u.mimeType(), u.sizeBytes(), u.claimedSha256(),
                u.ownerModule(), u.ownerType(), u.ownerId(), u.chainScope(), ts(u.capturedAt()), ts(u.expiresAt()),
                createdBy);
    }

    /** 업로드 세션을 만든 사용자 (SEC-CORE-002 :complete 소유자 검사) */
    public Optional<UUID> uploadCreatedBy(UUID id) {
        return jdbc.query("SELECT created_by FROM core.attachment_upload WHERE id = ?",
                (rs, i) -> rs.getObject("created_by", UUID.class), id).stream().findFirst();
    }

    /** 행 잠금으로 같은 업로드의 동시 :complete 직렬화 */
    public Optional<Upload> lockUpload(UUID id) {
        return jdbc.query("SELECT * FROM core.attachment_upload WHERE id = ? FOR UPDATE", (rs, i) -> new Upload(
                rs.getObject("id", UUID.class), rs.getString("temp_bucket"), rs.getString("temp_key"),
                rs.getString("file_name"), rs.getString("mime_type"), rs.getLong("size_bytes"),
                rs.getString("claimed_sha256"), rs.getString("owner_module"), rs.getString("owner_type"),
                rs.getObject("owner_id", UUID.class), rs.getObject("chain_scope", UUID.class),
                inst(rs.getTimestamp("captured_at")), rs.getString("status"),
                rs.getObject("attachment_id", UUID.class), inst(rs.getTimestamp("expires_at"))), id)
                .stream().findFirst();
    }

    public void markUpload(UUID id, String status, String reason, UUID attachmentId, Instant at) {
        jdbc.update("""
                UPDATE core.attachment_upload SET status = ?, reject_reason = ?, attachment_id = ?, completed_at = ?
                 WHERE id = ?""", status, reason, attachmentId, ts(at), id);
    }

    /** 체인 단위 직렬화 (트랜잭션 종료 시 해제) */
    public void lockChain(UUID chainScope) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 7))", Object.class,
                chainScope.toString());
    }

    /** 체인의 마지막 고리(다른 행의 prev_hash 로 참조되지 않는 행)의 chain_hash */
    public Optional<String> tailHash(UUID chainScope) {
        return jdbc.queryForList("""
                SELECT a.chain_hash FROM core.attachment a
                 WHERE a.chain_scope = ?
                   AND NOT EXISTS (SELECT 1 FROM core.attachment b
                                    WHERE b.chain_scope = a.chain_scope AND b.prev_hash = a.chain_hash)""",
                String.class, chainScope).stream().findFirst();
    }

    public void insertAttachment(UUID id, String bucket, String objectKey, Upload u, String sha256, String prevHash,
                                 String chainHash, UUID createdBy) {
        jdbc.update("""
                INSERT INTO core.attachment(id, bucket, object_key, file_name, mime_type, size_bytes, sha256, prev_hash,
                    chain_hash, owner_module, owner_type, owner_id, chain_scope, captured_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, bucket, objectKey, u.fileName(), u.mimeType(), u.sizeBytes(), sha256, prevHash, chainHash,
                u.ownerModule(), u.ownerType(), u.ownerId(), u.chainScope(), ts(u.capturedAt()), createdBy);
    }

    public List<Link> chain(UUID chainScope) {
        return jdbc.query("""
                SELECT id, sha256, prev_hash, chain_hash, captured_at, owner_id FROM core.attachment
                 WHERE chain_scope = ? ORDER BY created_at, id""",
                (rs, i) -> new Link(rs.getObject("id", UUID.class), rs.getString("sha256"), rs.getString("prev_hash"),
                        rs.getString("chain_hash"), inst(rs.getTimestamp("captured_at")),
                        rs.getObject("owner_id", UUID.class)), chainScope);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static Instant inst(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** 저장된 원본 위치 · 기록 해시 (삭제 제외) — [bucket, objectKey, sha256] */
    public Optional<String[]> stored(UUID attachmentId) {
        return jdbc.query("SELECT bucket, object_key, sha256 FROM core.attachment WHERE id = ? AND deleted_at IS NULL",
                (rs, i) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3).trim()}, attachmentId)
                .stream().findFirst();
    }
}
