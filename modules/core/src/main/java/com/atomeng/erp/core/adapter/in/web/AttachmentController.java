package com.atomeng.erp.core.adapter.in.web;

import com.atomeng.common.identity.authz.RequirePermission;
import com.atomeng.erp.core.application.AttachmentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** 설계 9.1 파일 규칙: 업로드는 서버 경유 금지 — presign → PUT → :complete */
@RestController
@RequestMapping("/api/v1/core")
class AttachmentController {

    record PresignRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 100) String mimeType,
            @PositiveOrZero long sizeBytes,
            @NotBlank @Pattern(regexp = "^[0-9a-fA-F]{64}$") String sha256,
            Instant capturedAt,
            @NotBlank @Pattern(regexp = "^[A-Z]{3,4}$") String ownerModule,
            @NotBlank @Size(max = 40) String ownerType,
            @NotNull UUID ownerId,
            @NotNull UUID chainScope) {}

    private final AttachmentService service;

    AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping("/attachments:presign")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("core:attachment:create")
    public AttachmentService.PresignResult presign(@Valid @RequestBody PresignRequest r) {
        return service.presign(new AttachmentService.PresignCommand(r.fileName(), r.mimeType(), r.sizeBytes(),
                r.sha256(), r.capturedAt(), r.ownerModule(), r.ownerType(), r.ownerId(), r.chainScope()));
    }

    @PostMapping("/attachments/uploads/{uploadId}:complete")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission("core:attachment:create")
    public AttachmentService.Stored complete(@PathVariable UUID uploadId) {
        return service.complete(uploadId);
    }

    @GetMapping("/attachments/chains/{chainScope}")
    @RequirePermission("core:attachment:read")
    public AttachmentService.ChainReport verify(@PathVariable UUID chainScope) {
        return service.verifyChain(chainScope);
    }
}
