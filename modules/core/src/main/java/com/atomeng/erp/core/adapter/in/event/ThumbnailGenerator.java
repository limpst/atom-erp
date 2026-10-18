package com.atomeng.erp.core.adapter.in.event;

import com.atomeng.common.events.EventEnvelope;
import com.atomeng.common.events.IdempotentListener;
import com.atomeng.common.evidence.Buckets;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.common.evidence.Thumbnailer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * core.attachment.stored 구독 → field-derived/{원본키}/thumb.png (설계 11.1·11.2-8).
 * Relay 가 at-least-once 로 전달하므로 processed_event 로 멱등 처리. 파생 버킷은 WORM 이 아니라 재생성 가능.
 */
@Component
public class ThumbnailGenerator {

    public static final String CONSUMER = "core.thumbnail-generator";

    private final ObjectStorage storage;
    private final Thumbnailer thumbnailer;

    public ThumbnailGenerator(ObjectStorage storage, Thumbnailer thumbnailer) {
        this.storage = storage;
        this.thumbnailer = thumbnailer;
    }

    public static String thumbKey(String originalKey) {
        return originalKey + "/thumb.png";
    }

    @EventListener(condition = "#env.eventType == 'core.attachment.stored'")
    @IdempotentListener(consumer = CONSUMER)
    public void on(EventEnvelope env) {
        String mime = env.payload().path("mimeType").asText("");
        if (!mime.startsWith("image/")) {
            return;
        }
        String bucket = env.payload().path("bucket").asText();
        String key = env.payload().path("objectKey").asText();
        try (InputStream in = storage.get(bucket, key)) {
            thumbnailer.thumbnail(in, Thumbnailer.THUMB_EDGE).ifPresent(png ->
                    storage.put(Buckets.FIELD_DERIVED, thumbKey(key), new ByteArrayInputStream(png), "image/png"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
