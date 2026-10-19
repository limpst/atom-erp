package com.atomeng.erp.aip.adapter.out.attachment;

import com.atomeng.erp.aip.application.port.JobImageLoader;
import com.atomeng.erp.core.api.AttachmentReadApi;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** core 첨부 원본 로더 — 저장 원본을 읽고 기록 해시와 다시 대조한다(AIP-03). */
@Component
class CoreAttachmentImageLoader implements JobImageLoader {

    private final AttachmentReadApi attachments;

    CoreAttachmentImageLoader(AttachmentReadApi attachments) {
        this.attachments = attachments;
    }

    @Override
    public byte[] load(UUID attachmentId) {
        return attachments.readOriginal(attachmentId, MAX_IMAGE_BYTES);
    }
}
