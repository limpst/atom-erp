package com.atomeng.erp.aip.application;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipEvents;
import com.atomeng.erp.aip.domain.AipCodes;
import com.atomeng.erp.aip.domain.AipRules;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 제안 · HITL 피드백 · 라벨 큐 · 데이터셋 커밋 (설계 A6.2 · B3 ③④⑤, BR-AIP-001 · 002 · 005 · 012, TC-AIP-020).
 * <ul>
 *   <li>제안은 항상 SUGGESTED 로 시작 — 기술자 확인(ACCEPTED/MODIFIED) 전에는 결함 확정 · 보고서에 못 들어간다(BR-AIP-001)</li>
 *   <li>결정 시점 feedback.training_eligible = false(항상), (b) 동의 ∧ (c) 블러면 라벨링 큐 후보만 등록(수락은 약한 양성 표본)</li>
 *   <li>데이터셋 커밋 시 (a) 검수 승인까지 충족한 피드백만 true(DB 트리거가 커밋 없는 true 전환을 막는다)</li>
 * </ul>
 */
@Service
public class SuggestionService {

    private static final Logger log = LoggerFactory.getLogger(SuggestionService.class);
    private static final Set<String> DEFECTS = Set.of("CRACK", "SPALL", "SCALING", "EFFLORESCENCE", "REBAR", "LEAK");
    private static final Set<String> EDITABLE = Set.of("defectType", "crackPattern", "widthMaxMm", "lengthM", "geometry",
            "description");
    static final String GUIDELINE_VERSION = "g1.0";

    private final AipJdbc db;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final AipProperties props;

    public SuggestionService(AipJdbc db, OutboxPublisher outbox, CurrentActor actor, AipProperties props) {
        this.db = db;
        this.outbox = outbox;
        this.actor = actor;
        this.props = props;
    }

    // ------------------------------------------------------------------ 제안 생성

    /** CRACK_DETECT 결과 → 제안(사진 입력이 core.attachment 에 있을 때만). 측정 성분마다 1건, 성분이 없으면 사진 단위 1건 */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> fromCrackDetect(Map<String, Object> job, JsonNode out) {
        List<UUID> ids = new ArrayList<>();
        if (!out.path("detected").asBoolean(false)) {
            return ids;
        }
        JsonNode in = AipJdbc.tree(job.get("input_refs"));
        if (!in.isArray() || in.isEmpty() || in.get(0).path("attachmentId").isNull() || in.get(0).path("attachmentId").isMissingNode()) {
            return ids;
        }
        UUID photo = UUID.fromString(in.get(0).path("attachmentId").asText());
        Integer exists = db.jdbc().queryForObject("SELECT count(*) FROM core.attachment WHERE id = ?", Integer.class, photo);
        if (exists == null || exists == 0) {
            log.warn("AIP 제안 생략 — 첨부 없음 {}", photo);
            return ids;
        }
        JsonNode finding = out.path("finding").path("valid").asBoolean(false) ? out.path("finding").path("finding") : null;
        String type = finding == null ? "CRACK" : finding.path("defectType").asText("CRACK");
        if (!DEFECTS.contains(type)) {
            return ids;   // VLM 이 결함 없음(NONE) 판정
        }
        double conf = out.path("score").path("pCrack").asDouble(0);
        if (finding != null) {
            conf = Math.min(conf, finding.path("confidence").asDouble(conf));
        }
        String pattern = finding == null ? null : finding.path("crackPattern").asText(null);
        String desc = finding == null ? null : finding.path("description").asText(null);
        JsonNode measure = out.path("measure");
        String scaleSource = measure.path("image").path("scaleSource").asText("NONE");
        Double mmpp = measure.path("image").path("mmPerPx").isNumber() ? measure.path("image").path("mmPerPx").asDouble() : null;
        List<JsonNode> comps = new ArrayList<>();
        measure.path("components").forEach(comps::add);
        if (comps.isEmpty()) {
            comps.add(null);
        }
        for (JsonNode c : comps) {
            ObjectNode geom = AipJdbc.MAPPER.createObjectNode();
            List<String> flags = new ArrayList<>();
            BigDecimal wmax = null;
            BigDecimal wmean = null;
            BigDecimal wp95 = null;
            BigDecimal wunc = null;
            BigDecimal len = null;
            if (c == null) {
                geom.put("type", "image");
                flags.add("MEASURE_REQUIRED");
            } else {
                geom.put("type", "bbox");
                geom.set("px", c.path("bbox"));
                c.path("flags").forEach(f -> flags.add(f.asText()));
                if ("CRACK".equals(type) && c.path("widthMaxMm").isNumber()) {   // 폭 수치는 균열에만(Step 7 병합 규칙)
                    wmax = c.path("widthMaxMm").decimalValue();
                    wmean = dec(c, "widthMeanMm");
                    wp95 = dec(c, "widthP95Mm");
                    wunc = dec(c, "widthUncertMm");
                    len = dec(c, "lengthM");
                }
            }
            UUID id = db.jdbc().queryForObject("""
                    INSERT INTO aip.suggestion(job_id, project_id, photo_attachment_id, defect_type, crack_pattern, geometry,
                           width_max_mm, width_mean_mm, width_p95_mm, width_uncert_mm, length_m, mm_per_px, scale_source,
                           confidence, description, flags)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::text[]) RETURNING id""", UUID.class,
                    AipJdbc.uuid(job, "id"), AipJdbc.uuid(job, "project_id"), photo, type,
                    "NA".equals(pattern) ? null : pattern, geom.toString(), wmax, wmean, wp95, wunc, len,
                    wmax == null ? null : mmpp, wmax == null && "NONE".equals(scaleSource) ? "NONE" : scaleSource,
                    BigDecimal.valueOf(Math.round(conf * 1000) / 1000.0), desc, "{" + String.join(",", flags) + "}");
            ids.add(id);
        }
        return ids;
    }

    private static BigDecimal dec(JsonNode c, String k) {
        return c.path(k).isNumber() ? c.path(k).decimalValue() : null;
    }

    public List<Map<String, Object>> byPhoto(UUID attachmentId) {
        return db.jdbc().queryForList("SELECT * FROM aip.suggestion WHERE photo_attachment_id = ? ORDER BY created_at, id",
                attachmentId);
    }

    /** 웹 목록(읽기 전용): 제안(상태별) + Job 요청자(범위 판정용) — 범위 거르기는 호출자(AipAccess)가 한다 */
    public List<Map<String, Object>> list(String status, int limit) {
        return db.jdbc().queryForList("""
                SELECT s.id, s.job_id, s.project_id, s.photo_attachment_id, s.defect_type, s.crack_pattern, s.width_max_mm,
                       s.width_mean_mm, s.width_uncert_mm, s.length_m, s.mm_per_px, s.scale_source, s.confidence,
                       s.description, s.flags, s.status, s.created_at, j.requested_by AS job_requested_by,
                       f.decision, f.reason_code, f.decided_by, f.decided_at
                  FROM aip.suggestion s JOIN aip.job j ON j.id = s.job_id LEFT JOIN aip.feedback f ON f.suggestion_id = s.id
                 WHERE (?::text IS NULL OR s.status = ?) ORDER BY s.created_at DESC, s.id LIMIT ?""", status, status, limit);
    }

    public Map<String, Object> get(UUID id) {
        return db.get("suggestion", id);
    }

    // ------------------------------------------------------------------ 결정(HITL)

    public record Decision(String decision, String reasonCode, Map<String, Object> modified) {}

    /** 수락 · 수정 · 거절 — diff 자동 계산, feedback(training_eligible=false), 조건 충족 시 라벨 큐, aip.suggestion.decided */
    @Transactional
    public Map<String, Object> decide(UUID suggestionId, Decision d) {
        Map<String, Object> s = db.lock("suggestion", suggestionId);
        if (!"SUGGESTED".equals(AipJdbc.str(s, "status"))) {
            throw new DomainException(AipCodes.INVALID_TRANSITION, "이미 결정된 제안: " + AipJdbc.str(s, "status"))
                    .with("status", AipJdbc.str(s, "status"));
        }
        String decision = d.decision() == null ? "" : d.decision().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("ACCEPTED", "MODIFIED", "REJECTED").contains(decision)) {
            throw new DomainException(AipCodes.INVALID, "decision 은 ACCEPTED · MODIFIED · REJECTED");
        }
        if ("REJECTED".equals(decision) && (d.reasonCode() == null || d.reasonCode().isBlank())) {
            throw new DomainException(AipCodes.INVALID, "거절은 사유 코드가 필요합니다");
        }
        Map<String, Object> mod = d.modified() == null ? Map.of() : d.modified();
        if ("MODIFIED".equals(decision) && mod.isEmpty()) {
            throw new DomainException(AipCodes.INVALID, "수정 수락은 바뀐 값(modified)이 필요합니다");
        }
        ObjectNode diff = AipJdbc.MAPPER.createObjectNode();
        for (Map.Entry<String, Object> e : mod.entrySet()) {
            if (!EDITABLE.contains(e.getKey())) {
                throw new DomainException(AipCodes.INVALID, "수정할 수 없는 항목: " + e.getKey());
            }
            Object before = s.get(snake(e.getKey()));
            diff.set(e.getKey(), AipJdbc.MAPPER.createArrayNode().add(AipJdbc.MAPPER.valueToTree(
                    before == null ? null : before instanceof BigDecimal b ? b.stripTrailingZeros() : before.toString()))
                    .add(AipJdbc.MAPPER.valueToTree(e.getValue())));
        }
        db.jdbc().update("UPDATE aip.suggestion SET status = ? WHERE id = ?", decision, suggestionId);
        UUID project = AipJdbc.uuid(s, "project_id");
        UUID photo = AipJdbc.uuid(s, "photo_attachment_id");
        boolean consent = consent(project);
        boolean blur = blurDone(photo);
        boolean queue = AipRules.labelQueueCandidate(decision, consent, blur,
                AipRules.acceptSampled(suggestionId, props.suggestion().acceptSampleRate()));
        UUID feedbackId = db.jdbc().queryForObject("""
                INSERT INTO aip.feedback(suggestion_id, decision, reason_code, diff, decided_by, label_queued, training_eligible)
                VALUES (?, ?, ?, ?::jsonb, ?, ?, false) RETURNING id""", UUID.class, suggestionId, decision,
                d.reasonCode(), diff.isEmpty() ? null : diff.toString(), actor.actorIdOrSystem(), queue);
        UUID taskId = null;
        if (queue) {
            String sha = db.jdbc().queryForObject("SELECT sha256 FROM core.attachment WHERE id = ?", String.class, photo);
            double conf = ((BigDecimal) s.get("confidence")).doubleValue();
            taskId = db.jdbc().queryForObject("""
                    INSERT INTO aip.label_task(source, feedback_id, project_id, image_attachment_id, image_sha256,
                           priority_score, guideline_version) VALUES ('FEEDBACK', ?, ?, ?, ?, ?, ?) RETURNING id""",
                    UUID.class, feedbackId, project, photo, sha, AipRules.labelPriority(conf, decision), GUIDELINE_VERSION);
        }
        outbox.publish(new AipEvents.SuggestionDecided(suggestionId, decision, d.reasonCode()));
        Map<String, Object> out = new LinkedHashMap<>(db.get("feedback", feedbackId));
        out.put("labelTaskId", taskId);
        return out;
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    /** 30일 미확인 제안 → EXPIRED (A6.2) */
    @Transactional
    public int expireStale() {
        return db.jdbc().update("UPDATE aip.suggestion SET status = 'EXPIRED' WHERE status = 'SUGGESTED' "
                + "AND created_at < now() - make_interval(days => ?)", props.suggestion().expireDays());
    }

    // ------------------------------------------------------------------ 동의 · 블러 플래그

    public boolean consent(UUID projectId) {
        if (projectId == null) {
            return false;
        }
        List<Boolean> v = db.jdbc().queryForList("SELECT training_consent FROM aip.project_consent WHERE project_id = ?",
                Boolean.class, projectId);
        return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
    }

    public boolean blurDone(UUID attachmentId) {
        List<Boolean> v = db.jdbc().queryForList("SELECT blur_done FROM aip.input_privacy WHERE attachment_id = ?",
                Boolean.class, attachmentId);
        return !v.isEmpty() && Boolean.TRUE.equals(v.get(0));
    }

    @Transactional
    public Map<String, Object> setConsent(UUID projectId, boolean consent, String source) {
        db.jdbc().update("""
                INSERT INTO aip.project_consent(project_id, training_consent, source, recorded_by) VALUES (?, ?, ?, ?)
                ON CONFLICT (project_id) DO UPDATE SET training_consent = EXCLUDED.training_consent, source = EXCLUDED.source,
                       recorded_by = EXCLUDED.recorded_by, recorded_at = now()""",
                projectId, consent, source == null ? "MANUAL" : source, actor.actorIdOrSystem());
        return db.jdbc().queryForMap("SELECT * FROM aip.project_consent WHERE project_id = ?", projectId);
    }

    @Transactional
    public Map<String, Object> setPrivacy(UUID attachmentId, boolean blurDone, int faces, int plates) {
        db.jdbc().update("""
                INSERT INTO aip.input_privacy(attachment_id, blur_done, faces_blurred, plates_blurred) VALUES (?, ?, ?, ?)
                ON CONFLICT (attachment_id) DO UPDATE SET blur_done = EXCLUDED.blur_done,
                       faces_blurred = EXCLUDED.faces_blurred, plates_blurred = EXCLUDED.plates_blurred, checked_at = now()""",
                attachmentId, blurDone, faces, plates);
        return db.jdbc().queryForMap("SELECT * FROM aip.input_privacy WHERE attachment_id = ?", attachmentId);
    }

    // ------------------------------------------------------------------ 라벨 검수 · 데이터셋 커밋

    /** 검수 판정(Senior Labeler 역할은 아직 없음 → QA · SYSADMIN): 승인 → DONE, 반려 → DISCARDED */
    @Transactional
    public Map<String, Object> adjudicate(UUID taskId, boolean approved, JsonNode finalResult, BigDecimal iou, String card) {
        Map<String, Object> t = db.lock("label_task", taskId);
        String st = AipJdbc.str(t, "status");
        if ("DONE".equals(st) || "DISCARDED".equals(st)) {
            throw new DomainException(AipCodes.INVALID_TRANSITION, "이미 판정된 라벨 과제: " + st);
        }
        db.jdbc().update("""
                INSERT INTO aip.adjudication(task_id, approved, agreement_iou, final_result, adjudicator_id, feedback_card)
                VALUES (?, ?, ?, ?::jsonb, ?, ?)""", taskId, approved, iou,
                finalResult == null ? "{}" : finalResult.toString(), actor.actorIdOrSystem(), card);
        db.jdbc().update("UPDATE aip.label_task SET status = ? WHERE id = ?", approved ? "DONE" : "DISCARDED", taskId);
        return db.get("label_task", taskId);
    }

    /**
     * 데이터셋 커밋(LakeFS 연동 전 — 매니페스트 SHA-256 을 커밋 id 로 기록). 대상 = 라벨 큐에 올라 검수 승인(a)된 피드백 중
     * 지금도 동의(b) ∧ 블러(c)이고 아직 커밋되지 않은 것. 분할 = sha256(project|date) 그룹 결정론(val 15%).
     * 포함된 피드백만 training_eligible=true(BR-AIP-012 ②), aip.dataset.committed 발행.
     */
    @Transactional
    public Map<String, Object> commitDataset(String name, String tag) {
        if (name == null || !name.matches("^[a-z0-9][a-z0-9-]{1,58}$") || tag == null
                || !tag.matches("^[A-Za-z0-9][A-Za-z0-9._-]{0,39}$")) {
            throw new DomainException(AipCodes.INVALID, "데이터셋 이름(소문자 · 숫자 · -)과 태그(40자 이내)가 필요합니다");
        }
        List<Map<String, Object>> cands = db.jdbc().queryForList("""
                SELECT f.id AS feedback_id, t.id AS task_id, t.image_sha256, t.image_attachment_id, t.project_id,
                       to_char(coalesce(a.captured_at, a.created_at) AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD') AS capture_date,
                       adj.approved
                  FROM aip.feedback f JOIN aip.label_task t ON t.feedback_id = f.id
                  JOIN aip.adjudication adj ON adj.task_id = t.id
                  JOIN core.attachment a ON a.id = t.image_attachment_id
                 WHERE f.training_eligible = false AND f.committed_dataset_id IS NULL AND t.status = 'DONE'
                 ORDER BY f.decided_at, f.id FOR UPDATE OF f""");
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> c : cands) {
            boolean ok = AipRules.trainingEligibleAtCommit(Boolean.TRUE.equals(c.get("approved")),
                    consent(AipJdbc.uuid(c, "project_id")), blurDone(AipJdbc.uuid(c, "image_attachment_id")));
            if (ok) {
                Map<String, Object> it = new LinkedHashMap<>(c);
                it.put("split", split(AipJdbc.str(c, "project_id"), AipJdbc.str(c, "capture_date")));
                items.add(it);
            }
        }
        ObjectNode manifest = AipJdbc.MAPPER.createObjectNode();
        manifest.put("name", name);
        manifest.put("tag", tag);
        var arr = manifest.putArray("items");
        int train = 0;
        int val = 0;
        for (Map<String, Object> it : items) {
            arr.addObject().put("sha256", AipJdbc.str(it, "image_sha256")).put("split", (String) it.get("split"));
            if ("val".equals(it.get("split"))) {
                val++;
            } else {
                train++;
            }
        }
        String commit = AipRules.sha256Hex(AipRules.canonical(manifest));
        ObjectNode split = AipJdbc.MAPPER.createObjectNode().put("train", train).put("val", val);
        ObjectNode card = AipJdbc.MAPPER.createObjectNode().put("items", items.size()).put("consentRatio", 1.0)
                .put("blurRatio", 1.0).put("source", "FEEDBACK").put("note", "합성 · 로컬 기록(LakeFS 연동 전)");
        UUID dsId;
        try {
            dsId = db.jdbc().queryForObject("""
                    INSERT INTO aip.dataset_version(name, lakefs_repo, lakefs_commit, tag, split_manifest, data_card, committed_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?) RETURNING id""", UUID.class, name, name, commit, tag,
                    split.toString(), card.toString(), actor.actorIdOrSystem());
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new DomainException(AipCodes.INVALID_TRANSITION, "이미 있는 데이터셋 태그: " + name + "@" + tag);
        }
        for (Map<String, Object> it : items) {
            UUID fb = AipJdbc.uuid(it, "feedback_id");
            db.jdbc().update("INSERT INTO aip.dataset_item(dataset_version_id, feedback_id, label_task_id, image_sha256, split) "
                    + "VALUES (?, ?, ?, ?, ?)", dsId, fb, AipJdbc.uuid(it, "task_id"), AipJdbc.str(it, "image_sha256"),
                    it.get("split"));
            db.jdbc().update("UPDATE aip.feedback SET committed_dataset_id = ?, training_eligible = true WHERE id = ?", dsId, fb);
        }
        outbox.publish(new AipEvents.DatasetCommitted(dsId, name, tag, items.size()));
        Map<String, Object> out = new LinkedHashMap<>(db.get("dataset_version", dsId));
        out.put("count", items.size());
        return out;
    }

    /** 결정론적 그룹 분할(11-IMPL Step 10 splits.py 와 같은 식): sha256("project|date") 앞 8hex % 10000 / 10000 < 0.15 → val */
    static String split(String projectId, String captureDate) {
        String h = AipRules.sha256Hex(projectId + "|" + captureDate);
        long v = Long.parseLong(h.substring(0, 8), 16);
        return (v % 10_000) / 10_000.0 < 0.15 ? "val" : "train";
    }
}
