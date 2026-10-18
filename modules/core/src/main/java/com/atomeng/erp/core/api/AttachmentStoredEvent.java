package com.atomeng.erp.core.api;

import com.atomeng.common.events.DomainEvent;
import java.util.UUID;

/** core.attachment.stored — 원본이 WORM 버킷에 저장되고 체인에 연결됨 (썸네일·AIP 추론 트리거) */
public record AttachmentStoredEvent(UUID attachmentId, String bucket, String objectKey, String mimeType,
                                    UUID chainScope, String sha256) implements DomainEvent {

    public static final String TYPE = "core.attachment.stored";

    @Override
    public String eventType() {
        return TYPE;
    }

    @Override
    public String aggregateType() {
        return "Attachment";
    }

    @Override
    public UUID aggregateId() {
        return attachmentId;
    }
}
