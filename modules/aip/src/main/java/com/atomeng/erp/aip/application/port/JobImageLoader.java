package com.atomeng.erp.aip.application.port;

import java.util.UUID;

/**
 * 분석할 이미지 원본을 서버가 읽는다 (보안 검토 AIP-03) — 클라이언트가 보낸 imageB64 는 쓰지 않는다.
 * 구현은 core::api AttachmentReadApi(저장 원본 · 해시 재확인 · 크기 상한).
 */
@FunctionalInterface
public interface JobImageLoader {

    /** 이미지 원본 상한(AIP-07 base64 15MB 와 같은 수준) */
    long MAX_IMAGE_BYTES = 15L * 1024 * 1024;

    byte[] load(UUID attachmentId);
}
