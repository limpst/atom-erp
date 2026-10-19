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
        sync = sync == null ? new Sync(null, null, null, null) : sync;
        upload = upload == null ? new Upload(null, null) : upload;
        ai = ai == null ? new Ai(null) : ai;
    }

    public record Sync(Integer maxOps, Duration visibilityWindow, Duration clockDriftWarn,
                       Duration geometryConflictWindow) {
        public Sync {
            maxOps = maxOps == null ? 500 : maxOps;
            visibilityWindow = visibilityWindow == null ? Duration.ofSeconds(2) : visibilityWindow;
            clockDriftWarn = clockDriftWarn == null ? Duration.ofMinutes(5) : clockDriftWarn;
            geometryConflictWindow = geometryConflictWindow == null ? Duration.ofMinutes(5) : geometryConflictWindow;
        }
    }

    public record Upload(Integer maxChunkBytes, Duration sessionTtl) {
        public Upload {
            maxChunkBytes = maxChunkBytes == null ? 5 * 1024 * 1024 : maxChunkBytes;
            sessionTtl = sessionTtl == null ? Duration.ofDays(7) : sessionTtl;
        }
    }

    public record Ai(BigDecimal discrepancyRatio) {
        public Ai {
            discrepancyRatio = discrepancyRatio == null ? new BigDecimal("2.0") : discrepancyRatio;
        }
    }
}
