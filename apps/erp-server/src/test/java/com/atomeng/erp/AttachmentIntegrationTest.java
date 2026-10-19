package com.atomeng.erp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.atomeng.common.events.OutboxRelay;
import com.atomeng.common.evidence.Buckets;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.common.evidence.ObjectLockedException;
import com.atomeng.common.evidence.ObjectStorage;
import com.atomeng.erp.core.adapter.in.event.ThumbnailGenerator;
import com.atomeng.erp.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 00-IMPL Step 8 첨부 · 해시 체인 — TC-CORE-006, TC-CORE-009, WORM 불변성 */
class AttachmentIntegrationTest extends IntegrationTestBase {

    @Autowired
    ObjectStorage storage;
    @Autowired
    OutboxRelay relay;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    /** 합성 현장 사진(PNG) — i 마다 다른 픽셀 */
    private static byte[] syntheticPhoto(int i) throws Exception {
        var img = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color((40 + i * 10) % 256, 80, 120));
        g.fillRect(0, 0, 1600, 1200);
        g.setColor(Color.DARK_GRAY);
        g.drawLine(100 + i, 100, 1500, 1100 - i); // 합성 "균열"
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String sha(byte[] b) {
        return HashChain.sha256(new ByteArrayInputStream(b));
    }

    private MockHttpServletRequestBuilder fieldApp(MockHttpServletRequestBuilder b, UUID engineer) {
        return as(b, engineer, "ENG").header("X-Client-Id", "field-app")
                .header("Idempotency-Key", UUID.randomUUID().toString());
    }

    private JsonNode presign(UUID engineer, UUID chainScope, String fileName, String sha256, Instant capturedAt)
            throws Exception {
        String body = """
                {"fileName":"%s","mimeType":"image/png","sizeBytes":1,"sha256":"%s","capturedAt":"%s",
                 "ownerModule":"FLD","ownerType":"Photo","ownerId":"%s","chainScope":"%s"}"""
                .formatted(fileName, sha256, capturedAt, UUID.randomUUID(), chainScope);
        var r = mvc.perform(fieldApp(postJson("/api/v1/core/attachments:presign", body), engineer))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.uploadUrl").value(org.hamcrest.Matchers.startsWith("local://temp-upload/")))
                .andReturn();
        return json(r);
    }

    /**
     * 보안 검토 CORE-01(a): 첨부 체인 범위는 실제 FLD 조사 회차여야 하고 올리는 사람은 그 프로젝트 투입 인원이어야 한다.
     * 합성 프로젝트 · 건물 · 회차를 만들고 members 를 투입한다 → roundId(= chainScope)
     */
    private UUID round(UUID... members) throws Exception {
        var flow = new com.atomeng.erp.support.RptFlow(mvc, jdbc, objectMapper, relay);
        UUID pm = flow.employee("ATT-PM");
        UUID project = flow.project(pm);
        for (UUID m : members) {
            jdbc.update("INSERT INTO core.employee(id, emp_no, name, hire_date, created_by, updated_by) "
                    + "VALUES (?, ?, '합성기술자', DATE '2020-01-01', ?, ?) ON CONFLICT (id) DO NOTHING", m,
                    "ATT-" + m.toString().substring(0, 8), m, m);
            flow.member(project, m, "PART");
        }
        UUID building = flow.facBuilding(pm, "합성 첨부 건물");
        var r = mvc.perform(as(postJson("/api/v1/fld/rounds", """
                {"projectId":"%s","buildingId":"%s","surveyType":"PRECISE_INSPECTION"}""".formatted(project, building)),
                pm, "PM")).andExpect(status().isCreated()).andReturn();
        return UUID.fromString(json(r).get("id").asText());
    }

    private void clientPut(JsonNode presigned, byte[] bytes) {
        storage.put(Buckets.TEMP_UPLOAD, presigned.get("tempKey").asText(), new ByteArrayInputStream(bytes),
                "image/png");
    }

    @Test
    @DisplayName("SEC-CORE-002: 다른 사용자가 만든 업로드 세션 :complete → 403 CORE-403-001, 세션은 PENDING 유지 · 첨부 미생성")
    void completeOnlyByUploader() throws Exception {
        UUID owner = actor();
        UUID other = actor();
        UUID scope = round(owner);
        byte[] photo = syntheticPhoto(7);
        var p = presign(owner, scope, "own.png", sha(photo), Instant.parse("2026-10-19T02:00:00Z"));
        clientPut(p, photo);
        String uploadId = p.get("uploadId").asText();

        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + uploadId + ":complete", "{}"), other))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CORE-403-001"));
        assertThat(jdbc.queryForObject("SELECT status FROM core.attachment_upload WHERE id = ?::uuid", String.class,
                uploadId)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.attachment WHERE chain_scope = ?", Integer.class,
                scope)).isZero();
        // 본인은 확정 가능
        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + uploadId + ":complete", "{}"), owner))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("TC-CORE-006: 해시 위조 업로드 → 422 CORE-422-010, temp 객체 삭제, 첨부 미생성")
    void tcCore006_forgedHashRejected() throws Exception {
        UUID eng = actor();
        UUID scope = round(eng);
        byte[] real = syntheticPhoto(1);
        byte[] claimedFor = syntheticPhoto(2);
        var p = presign(eng, scope, "forged.png", sha(claimedFor), Instant.parse("2026-10-19T01:00:00Z"));
        clientPut(p, real); // 다른 파일을 올림
        String uploadId = p.get("uploadId").asText();

        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + uploadId + ":complete", "{}"), eng))
                .andExpect(status().is(422))
                .andExpect(jsonPath("$.code").value("CORE-422-010"));

        assertThat(storage.exists(Buckets.TEMP_UPLOAD, p.get("tempKey").asText())).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM core.attachment_upload WHERE id = ?::uuid", String.class,
                uploadId)).isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.attachment WHERE chain_scope = ?", Integer.class,
                scope)).isZero();
        // 거부된 업로드 재완료 시도 → 409 CORE-409-011
        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + uploadId + ":complete", "{}"), eng))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CORE-409-011"));
    }

    @Test
    @DisplayName("TC-CORE-009: 현장 앱 오프라인 사진 10장 → 온라인 업로드 10건, 체인 연속, 썸네일 생성")
    void tcCore009_offlineBatchOf10() throws Exception {
        UUID eng = actor();
        UUID survey = round(eng); // 점검회차 = 체인 단위(픽스처 이벤트는 아래 TRUNCATE 로 비운다)
        jdbc.execute("TRUNCATE core.outbox_event, core.outbox_dead");
        Instant offlineStart = Instant.parse("2026-10-19T00:30:00Z");
        List<JsonNode> stored = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            byte[] photo = syntheticPhoto(i);
            var p = presign(eng, survey, "IMG_%04d.png".formatted(i), sha(photo), offlineStart.plusSeconds(i * 37L));
            clientPut(p, photo);
            String key = UUID.randomUUID().toString();
            var r = mvc.perform(as(postJson("/api/v1/core/attachments/uploads/" + p.get("uploadId").asText()
                            + ":complete", "{}"), eng, "ENG").header("X-Client-Id", "field-app")
                            .header("Idempotency-Key", key))
                    .andExpect(status().isCreated()).andReturn();
            stored.add(json(r));
            // 네트워크 끊김으로 같은 :complete 재전송 → 멱등 재반환(업무 미실행)
            if (i == 3) {
                mvc.perform(as(postJson("/api/v1/core/attachments/uploads/" + p.get("uploadId").asText()
                                + ":complete", "{}"), eng, "ENG").header("X-Client-Id", "field-app")
                                .header("Idempotency-Key", key))
                        .andExpect(status().isCreated())
                        .andExpect(header().string("Idempotent-Replayed", "true"))
                        .andExpect(jsonPath("$.attachmentId").value(json(r).get("attachmentId").asText()));
            }
            assertThat(storage.exists(Buckets.TEMP_UPLOAD, p.get("tempKey").asText())).isFalse();
        }

        // 업로드 10건, 체인 연속(prev_hash = 직전 chain_hash), 첫 고리만 GENESIS
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.attachment WHERE chain_scope = ?", Integer.class,
                survey)).isEqualTo(10);
        assertThat(stored.get(0).get("prevHash").isNull()).isTrue();
        for (int i = 1; i < 10; i++) {
            assertThat(stored.get(i).get("prevHash").asText()).isEqualTo(stored.get(i - 1).get("chainHash").asText());
        }
        mvc.perform(as(get("/api/v1/core/attachments/chains/" + survey), eng, "ENG"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.length").value(10));

        // 썸네일: outbox(core.attachment.stored) → Relay → ThumbnailGenerator
        var poll = relay.pollOnce();
        assertThat(poll.published()).isEqualTo(10);
        for (JsonNode s : stored) {
            String key = s.get("objectKey").asText();
            assertThat(storage.exists(Buckets.FIELD_ORIGINAL, key)).isTrue();
            assertThat(storage.exists(Buckets.FIELD_DERIVED, ThumbnailGenerator.thumbKey(key))).isTrue();
            var thumb = ImageIO.read(storage.get(Buckets.FIELD_DERIVED, ThumbnailGenerator.thumbKey(key)));
            assertThat(Math.max(thumb.getWidth(), thumb.getHeight())).isEqualTo(320);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.processed_event WHERE consumer = ?", Integer.class,
                ThumbnailGenerator.CONSUMER)).isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("Step8 불변성: 원본(WORM) 삭제·덮어쓰기 거부, DB sha256 변경 거부, 체인 검증이 변조를 탐지")
    void immutabilityAndTamperDetection() throws Exception {
        UUID eng = actor();
        UUID scope = round(eng);
        List<JsonNode> stored = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            byte[] photo = syntheticPhoto(20 + i);
            var p = presign(eng, scope, "w%d.png".formatted(i), sha(photo), Instant.parse("2026-10-19T02:00:00Z"));
            clientPut(p, photo);
            stored.add(json(mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/"
                    + p.get("uploadId").asText() + ":complete", "{}"), eng)).andExpect(status().isCreated())
                    .andReturn()));
        }
        String key = stored.get(1).get("objectKey").asText();
        assertThatThrownBy(() -> storage.delete(Buckets.FIELD_ORIGINAL, key)).isInstanceOf(ObjectLockedException.class);
        assertThatThrownBy(() -> storage.put(Buckets.FIELD_ORIGINAL, key, new ByteArrayInputStream(new byte[] {1}),
                null)).isInstanceOf(ObjectLockedException.class);
        UUID middle = UUID.fromString(stored.get(1).get("attachmentId").asText());
        assertThatThrownBy(() -> jdbc.update("UPDATE core.attachment SET sha256 = ? WHERE id = ?", "0".repeat(64),
                middle)).hasMessageContaining("immutable");

        // 트리거를 우회한 DB 변조(슈퍼유저) → 체인 검증이 정확히 그 고리를 지목
        // 같은 커넥션에서 SET·UPDATE·복원 (풀의 다른 커넥션에 replica 모드가 새지 않도록)
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) c -> {
            try (var st = c.createStatement()) {
                st.execute("SET session_replication_role = replica");
                try (var ps = c.prepareStatement("UPDATE core.attachment SET sha256 = ? WHERE id = ?")) {
                    ps.setString(1, "f".repeat(64));
                    ps.setObject(2, middle);
                    ps.executeUpdate();
                } finally {
                    st.execute("SET session_replication_role = origin");
                }
            }
            return null;
        });
        mvc.perform(as(get("/api/v1/core/attachments/chains/" + scope), eng, "ENG"))
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.brokenAt").value(middle.toString()));
    }

    @Test
    @DisplayName("Step8: presign 15분 만료 후 :complete → 422 CORE-422-011, 업로드 안 한 채 :complete → 422 CORE-422-012")
    void expiryAndMissingObject() throws Exception {
        UUID eng = actor();
        byte[] photo = syntheticPhoto(30);
        var missing = presign(eng, round(eng), "m.png", sha(photo), Instant.parse("2026-10-19T03:00:00Z"));
        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + missing.get("uploadId").asText()
                        + ":complete", "{}"), eng))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("CORE-422-012"));

        var late = presign(eng, round(eng), "late.png", sha(photo), Instant.parse("2026-10-19T03:00:00Z"));
        clientPut(late, photo);
        clock.advance(Duration.ofMinutes(16));
        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + late.get("uploadId").asText()
                        + ":complete", "{}"), eng))
                .andExpect(status().is(422)).andExpect(jsonPath("$.code").value("CORE-422-011"));
        assertThat(storage.exists(Buckets.TEMP_UPLOAD, late.get("tempKey").asText())).isFalse();
    }

    private org.springframework.test.web.servlet.ResultActions presignRaw(UUID eng, String mime, long size, String sha,
                                                                         String module, UUID chainScope) throws Exception {
        return mvc.perform(fieldApp(postJson("/api/v1/core/attachments:presign", """
                {"fileName":"x.png","mimeType":"%s","sizeBytes":%d,"sha256":"%s",
                 "ownerModule":"%s","ownerType":"Photo","ownerId":"%s","chainScope":"%s"}"""
                .formatted(mime, size, sha, module, UUID.randomUUID(), chainScope)), eng));
    }

    @Test
    @DisplayName("보안 검토 CORE-01(a): 없는 체인 범위 · 해석기 없는 모듈 · 투입되지 않은 프로젝트 회차로 presign → 422 CORE-422-013")
    void ownerMustResolveToCallersProject() throws Exception {
        UUID eng = actor();
        UUID mine = round(eng);
        UUID theirs = round(actor());
        String sha = "a".repeat(64);
        presignRaw(eng, "image/png", 10, sha, "FLD", UUID.randomUUID()).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORE-422-013"));
        presignRaw(eng, "image/png", 10, sha, "XYZ", mine).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORE-422-013"));
        presignRaw(eng, "image/png", 10, sha, "FLD", theirs).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CORE-422-013"));
        presignRaw(eng, "image/png", 10, sha, "FLD", mine).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("보안 검토 CORE-01(b)(c): 크기 상한 초과 413 · 허용 밖 MIME 422 · 내용이 신고 MIME 과 다르면 :complete 422(첨부 미생성)")
    void sizeAndMediaType() throws Exception {
        UUID eng = actor();
        UUID scope = round(eng);
        presignRaw(eng, "image/png", 60L * 1024 * 1024, "a".repeat(64), "FLD", scope)
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("CORE-413-001"));
        presignRaw(eng, "text/html", 10, "a".repeat(64), "FLD", scope)
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORE-422-014"));
        // PNG 라고 신고하고 HTML 을 올림
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var p = json(presignRaw(eng, "image/png", html.length, sha(html), "FLD", scope)
                .andExpect(status().isCreated()).andReturn());
        clientPut(p, html);
        mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/" + p.get("uploadId").asText() + ":complete",
                        "{}"), eng))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CORE-422-014"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM core.attachment WHERE chain_scope = ?", Integer.class,
                scope)).isZero();
        // 정상 PNG 는 실제 크기로 기록(신고값 1 무시)
        byte[] photo = syntheticPhoto(50);
        var ok = presign(eng, scope, "ok.png", sha(photo), Instant.parse("2026-10-19T02:00:00Z"));
        clientPut(ok, photo);
        var stored = json(mvc.perform(fieldApp(postJson("/api/v1/core/attachments/uploads/"
                + ok.get("uploadId").asText() + ":complete", "{}"), eng)).andExpect(status().isCreated()).andReturn());
        assertThat(jdbc.queryForObject("SELECT size_bytes FROM core.attachment WHERE id = ?::uuid", Long.class,
                stored.get("attachmentId").asText())).isEqualTo((long) photo.length);
    }

    @Test
    @DisplayName("Step8 권한: 첨부 업로드는 ENG·PM 만 — CUSTOMER·AUDITOR 는 403")
    void permissions() throws Exception {
        String body = """
                {"fileName":"x.png","mimeType":"image/png","sizeBytes":1,"sha256":"%s",
                 "ownerModule":"FLD","ownerType":"Photo","ownerId":"%s","chainScope":"%s"}"""
                .formatted("a".repeat(64), UUID.randomUUID(), UUID.randomUUID());
        for (String role : List.of("CUSTOMER", "AUDITOR")) {
            mvc.perform(as(postJson("/api/v1/core/attachments:presign", body), actor(), role))
                    .andExpect(status().isForbidden());
        }
    }
}
