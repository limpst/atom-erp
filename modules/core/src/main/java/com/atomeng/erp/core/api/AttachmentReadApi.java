package com.atomeng.erp.core.api;

import java.util.Optional;
import java.util.UUID;

/**
 * 첨부 읽기 공개 포트 (보안 검토 AIP-03) — 다른 모듈이 첨부의 소속 프로젝트를 확인하고 서버가 보관한 원본을 읽는다.
 * 클라이언트가 보낸 이미지 본문 대신 이 원본만 분석에 쓴다.
 */
public interface AttachmentReadApi {

    /**
     * @param projectId 소유자 해석기로 푼 프로젝트(해석기 없음 · 풀 수 없음이면 null)
     */
    record AttachmentInfo(UUID id, UUID projectId, String ownerModule, String ownerType, UUID ownerId,
                          UUID chainScope, String sha256, String mimeType, long sizeBytes, UUID createdBy) {}

    /** 삭제되지 않은 첨부(없으면 empty) */
    Optional<AttachmentInfo> find(UUID attachmentId);

    /**
     * 저장된 원본 전체. maxBytes 를 넘으면 {@link com.atomeng.common.core.error.DomainException}(CORE-413-001),
     * 다시 계산한 SHA-256 이 기록과 다르면 CORE-422-010.
     */
    byte[] readOriginal(UUID attachmentId, long maxBytes);
}
