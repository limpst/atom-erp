package com.atomeng.erp.core.api;

import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 서버 경유 첨부 적재 공개 포트 (05-IMPL Step 9 — FLD 이어 올리기 세션이 조립한 원본을 core 첨부 파이프라인에 넘긴다).
 * presign → (서버가 temp 객체 쓰기) → :complete 와 같은 경로를 탄다: 기기 해시와 서버 재계산 해시 비교 → WORM 복사 →
 * chain_scope 해시 체인 연결 → {@code core.attachment.stored} outbox. 해시가 다르면 temp 삭제 후
 * {@link com.atomeng.common.core.error.DomainException}(CORE-422-010).
 * <p>호출자 트랜잭션 밖에서 부른다(내부에서 짧은 트랜잭션을 나눠 쓰며, 저장소 복사가 DB 커밋 앞에 있다).
 */
public interface AttachmentIngestApi {

    /**
     * @param ownerModule 3~4자 대문자(FLD …)
     * @param chainScope  해시 체인 단위(FLD = 조사 회차 roundId)
     */
    record IngestCommand(String fileName, String mimeType, long sizeBytes, String sha256, Instant capturedAt,
                         String ownerModule, String ownerType, UUID ownerId, UUID chainScope) {}

    /** 저장 결과 — sha256 = 서버 재계산 값(= 기기 해시), chainHash = 체인 고리 해시 */
    record Ingested(UUID attachmentId, String bucket, String objectKey, String sha256, String prevHash,
                    String chainHash, UUID chainScope) {}

    /** 체인 검증 결과(GENESIS 에서 모든 고리 통과 = valid) */
    record ChainCheck(UUID chainScope, boolean valid, int length, UUID brokenAt, String reason) {}

    /** content 는 1회 이상 열릴 수 있다(스트리밍) */
    Ingested ingest(IngestCommand cmd, Supplier<InputStream> content);

    ChainCheck checkChain(UUID chainScope);

    /** 원본 재해시 결과 — match = 기록 해시와 저장소 원본 재계산 해시가 같음(원본 유실이면 actual null · false) */
    record ObjectCheck(UUID attachmentId, String recordedSha256, String actualSha256, boolean match) {}

    /** 저장된 원본을 다시 읽어 SHA-256 재계산(첨부 없으면 empty) */
    java.util.Optional<ObjectCheck> verifyObject(UUID attachmentId);
}
