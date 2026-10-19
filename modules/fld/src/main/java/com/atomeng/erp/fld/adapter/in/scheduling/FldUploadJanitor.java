package com.atomeng.erp.fld.adapter.in.scheduling;

import com.atomeng.erp.fld.application.PhotoUploadService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보안 검토 FLD-02 — 만료된 사진 업로드 세션 정리(임시 청크 삭제). atom.fld.upload.cleanup.enabled=false 면 끈다.
 */
@Component
@ConditionalOnProperty(prefix = "atom.fld.upload.cleanup", name = "enabled", havingValue = "true", matchIfMissing = true)
class FldUploadJanitor {

    private static final Logger log = LoggerFactory.getLogger(FldUploadJanitor.class);

    private final PhotoUploadService uploads;

    FldUploadJanitor(PhotoUploadService uploads) {
        this.uploads = uploads;
    }

    @Scheduled(initialDelayString = "${atom.fld.upload.cleanup.initial-delay:5m}",
            fixedDelayString = "${atom.fld.upload.cleanup.interval:1h}")
    void expire() {
        try {
            uploads.expireSessions();
        } catch (RuntimeException e) {
            log.warn("fld 업로드 세션 정리 오류: {}", e.toString());
        }
    }
}
