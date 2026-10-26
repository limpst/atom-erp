package com.atomeng.erp.fld.application;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * atom.fld.* — sync.maxOps(push 배치 상한 500) · sync.visibilityWindow(가시성 지연 창, 05-IMPL Step 10) ·
 * sync.clockDriftWarn(5분, A8-5) · sync.geometryConflictWindow(5분, B7.2) · upload.maxChunkBytes(5MB) ·
 * upload.sessionTtl(7일) · ai.discrepancyRatio(2.0, A8-8).
 */
@ConfigurationProperties(prefix = "atom.fld")
public record FldProperties(Sync sync, Upload upload, Ai ai) {

    public FldProperties {
        sync = sync == null ? new Sync(null, null, null, null, null) : sync;
        upload = upload == null ? new Upload(null, null, null, null) : upload;
        ai = ai == null ? new Ai(null) : ai;
    }

    /**
     * @param maxFutureSkew 기기 HLC 가 서버 시각보다 이만큼 넘게 미래면 op 거부(HLC_IN_FUTURE, SEC-2026-10-19) — 미래 시각으로
     *                      LWW 를 항상 이기거나 16자리 wallMs 를 넘겨 정렬을 깨는 것을 막는다. 기본 24시간(오프라인 기기 여유).
     */
    public record Sync(Integer maxOps, Duration visibilityWindow, Duration clockDriftWarn,
                       Duration geometryConflictWindow, Duration maxFutureSkew) {
        public Sync {
            maxOps = maxOps == null ? 500 : maxOps;
            visibilityWindow = visibilityWindow == null ? Duration.ofSeconds(2) : visibilityWindow;
            clockDriftWarn = clockDriftWarn == null ? Duration.ofMinutes(5) : clockDriftWarn;
            geometryConflictWindow = geometryConflictWindow == null ? Duration.ofMinutes(5) : geometryConflictWindow;
            maxFutureSkew = maxFutureSkew == null ? Duration.ofHours(24) : maxFutureSkew;
        }
    }

    /**
     * 업로드 한도 — <b>보안 검토 FLD-02b 확정값(2026-10-26, 권장안 승인)</b>. 운영 기본값이며 설정으로만 바꾼다
     * ({@code atom.fld.upload.*}, application.yml 에 같은 값을 명시). 근거: 현장 기기(태블릿 · 드론 카메라) 원본 사진은
     * 대개 5~30MB 라 50MB 면 여유가 있고, 동시에 열린 세션 20개 · 7일 만료면 오프라인 1주 현장 작업도 수용하면서
     * TEMP 디스크 고갈(세션 남발)을 막는다. 첨부 공통 상한 {@code atom.attachment.max-bytes} 도 같은 50MB(core AttachmentService).
     *
     * @param maxChunkBytes          PATCH 청크 상한(FR-FLD-017, 5MB)
     * @param sessionTtl             업로드 세션 만료(7일) — 지나면 PATCH 410 FLD-410-001 · FldUploadJanitor 가 EXPIRED
     * @param maxFileBytes           사진 원본 최대 크기(50MB) — 넘으면 413 FLD-413-002
     * @param maxOpenSessionsPerUser 사용자당 동시에 열린 업로드 세션 수(20) — 넘으면 429 FLD-429-001
     */
    public record Upload(Integer maxChunkBytes, Duration sessionTtl, Long maxFileBytes, Integer maxOpenSessionsPerUser) {
        public static final int DEFAULT_MAX_CHUNK_BYTES = 5 * 1024 * 1024;
        public static final Duration DEFAULT_SESSION_TTL = Duration.ofDays(7);
        public static final long DEFAULT_MAX_FILE_BYTES = 50L * 1024 * 1024;
        public static final int DEFAULT_MAX_OPEN_SESSIONS_PER_USER = 20;

        public Upload {
            maxChunkBytes = maxChunkBytes == null ? DEFAULT_MAX_CHUNK_BYTES : maxChunkBytes;
            sessionTtl = sessionTtl == null ? DEFAULT_SESSION_TTL : sessionTtl;
            maxFileBytes = maxFileBytes == null ? DEFAULT_MAX_FILE_BYTES : maxFileBytes;
            maxOpenSessionsPerUser = maxOpenSessionsPerUser == null ? DEFAULT_MAX_OPEN_SESSIONS_PER_USER
                    : maxOpenSessionsPerUser;
        }
    }

    public record Ai(BigDecimal discrepancyRatio) {
        public Ai {
            discrepancyRatio = discrepancyRatio == null ? new BigDecimal("2.0") : discrepancyRatio;
        }
    }
}
