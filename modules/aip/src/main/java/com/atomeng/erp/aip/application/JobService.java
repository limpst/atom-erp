package com.atomeng.erp.aip.application;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.aip.adapter.out.persistence.AipJdbc;
import com.atomeng.erp.aip.api.AipEvents;
import com.atomeng.erp.aip.api.AipServiceCode;
import com.atomeng.erp.aip.domain.AipCodes;
import com.atomeng.erp.aip.domain.AipRules;
import com.atomeng.erp.aip.domain.JobStatus;
import com.atomeng.erp.core.api.AttachmentReadApi;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 추론 Job 큐 (설계 A6.1 · B3 ② · B7.4, 11-IMPL Step 1 · 2, TC-AIP-001 · 002). PostgreSQL {@code FOR UPDATE SKIP LOCKED} 디큐
 * (우선순위 + 에이징: 대기 10분마다 1단계 상승 효과), lease(임대) 만료 리퍼, 재시도(지수 백오프 2^attempts 초, 최대 max_attempts →
 * DEAD), 마감(deadline_at) 초과 → DEAD, 결과 저장은 {@code status='RUNNING' AND worker_id=나} 조건부 UPDATE 로 1회만.
 * 모든 상태 변경은 job_event(STATE) + 필요 시 outbox 이벤트와 같은 트랜잭션.
 */
@Service
public class JobService {

    /** 이미지 본문 키 — 감사 · 이벤트 · 해시용 params 에서 뺀다(이미지 해시로 대체) */
    static final List<String> BLOB_KEYS = List.of("imageB64", "maskB64");

    public record InputRef(UUID attachmentId, String sha256) {}

    public record CreateCommand(AipServiceCode serviceCode, Integer priority, UUID projectId, List<InputRef> inputs,
                                JsonNode params, String idempotencyKey, Duration deadline, Integer maxAttempts,
                                String requesterModule, String mode) {}

    public record Created(Map<String, Object> job, boolean replayed) {}

    private final AipJdbc db;
    private final OutboxPublisher outbox;
    private final CurrentActor actor;
    private final ModelRegistryService registry;
    private final InferenceAuditService audit;
    private final SuggestionService suggestions;
    private final AttachmentReadApi attachments;

    public JobService(AipJdbc db, OutboxPublisher outbox, CurrentActor actor, ModelRegistryService registry,
                      InferenceAuditService audit, SuggestionService suggestions, AttachmentReadApi attachments) {
        this.attachments = attachments;
        this.db = db;
        this.outbox = outbox;
        this.actor = actor;
        this.registry = registry;
        this.audit = audit;
        this.suggestions = suggestions;
    }

    // ------------------------------------------------------------------ 생성 · 조회 · 취소

    /** params 에서 이미지 본문을 빼고 그 해시를 남긴다 */
    static ObjectNode stripBlobs(JsonNode params) {
        ObjectNode o = params != null && params.isObject() ? ((ObjectNode) params).deepCopy() : AipJdbc.MAPPER.createObjectNode();
        for (String k : BLOB_KEYS) {
            if (o.hasNonNull(k)) {
                o.put(k.replace("B64", "Sha256"), AipRules.sha256Hex(o.get(k).asText()));
                o.remove(k);
            }
        }
        return o;
    }

    static String inputHash(List<InputRef> inputs, JsonNode params) {
        List<String> shas = new ArrayList<>();
        for (InputRef r : inputs == null ? List.<InputRef>of() : inputs) {
            shas.add(r.sha256() == null ? "" : r.sha256());
        }
        return AipRules.inputHash(shas, stripBlobs(params));
    }

    /**
     * Job 생성(QUEUED). 같은 멱등 키가 있으면 입력 해시가 같을 때 기존 Job 을 그대로 돌려준다(TC-AIP-001), 다르면 409.
     * storeBlobs = 비동기 처리용으로 이미지 본문을 params 에 보관(MinIO 입력 로더 전 임시 — 동기 호출은 보관하지 않음).
     */
    @Transactional
    public Created create(CreateCommand c, boolean storeBlobs) {
        if (!c.serviceCode().supported()) {
            throw new DomainException(AipCodes.UNSUPPORTED_SERVICE, "이번 단계 미지원 서비스: " + c.serviceCode());
        }
        List<InputRef> inputs = verifiedInputs(c);
        c = new CreateCommand(c.serviceCode(), c.priority(), c.projectId(), inputs, c.params(), c.idempotencyKey(),
                c.deadline(), c.maxAttempts(), c.requesterModule(), c.mode());
        String hash = inputHash(c.inputs(), c.params());
        if (c.idempotencyKey() != null) {
            Optional<Map<String, Object>> existing = byKey(c.idempotencyKey());
            if (existing.isPresent()) {
                return replayOrConflict(existing.get(), hash, c.requesterModule());
            }
        }
        UUID subject = c.inputs() != null && !c.inputs().isEmpty() && c.inputs().get(0).attachmentId() != null
                ? c.inputs().get(0).attachmentId() : c.projectId();
        ModelRegistryService.Route route = registry.resolve(c.serviceCode().name(), subject);
        int priority = c.priority() == null ? c.serviceCode().defaultPriority() : c.priority();
        if (priority < 1 || priority > 4) {
            throw new DomainException(AipCodes.INVALID, "priority 는 1–4");
        }
        // 보안 검토 AIP-03: 이미지는 실행 때 서버가 첨부 원본에서 읽으므로 클라이언트 본문(imageB64 · maskB64)은 보관하지 않는다
        JsonNode stored = stripBlobs(c.params());
        ArrayNode refs = AipJdbc.MAPPER.createArrayNode();
        for (InputRef r : c.inputs() == null ? List.<InputRef>of() : c.inputs()) {
            ObjectNode o = refs.addObject();
            o.put("attachmentId", r.attachmentId() == null ? null : r.attachmentId().toString());
            o.put("sha256", r.sha256());
        }
        UUID id;
        try {
            id = db.jdbc().queryForObject("""
                    INSERT INTO aip.job(service_code, mode, priority, idempotency_key, requested_by, requester_module, project_id,
                           input_refs, input_sha256, params, model_version_id, prompt_version_id, route_alias,
                           vram_budget_mb, max_attempts, deadline_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?, ?,
                            CASE WHEN ?::bigint IS NULL THEN NULL ELSE now() + make_interval(secs => ?::bigint) END)
                    RETURNING id""", UUID.class, c.serviceCode().name(), c.mode() == null ? "ASYNC" : c.mode(), priority,
                    c.idempotencyKey(), actor.actorIdOrSystem(), c.requesterModule(), c.projectId(), refs.toString(), hash,
                    stored.toString(), route.modelVersionId(), route.promptVersionId(), route.alias(),
                    c.serviceCode().vramBudgetMb(), c.maxAttempts() == null ? 3 : c.maxAttempts(),
                    c.deadline() == null ? null : c.deadline().toSeconds(), c.deadline() == null ? null : c.deadline().toSeconds());
        } catch (DuplicateKeyException race) {
            return replayOrConflict(byKey(c.idempotencyKey()).orElseThrow(), hash, c.requesterModule());
        }
        event(id, "STATE", Map.of("to", "QUEUED"));
        outbox.publish(new AipEvents.JobQueued(id, c.serviceCode().name(), priority));
        return new Created(db.get("job", id), false);
    }

    /**
     * 보안 검토 AIP-03: 입력 첨부는 존재해야 하고, 소유자 해석으로 푼 프로젝트가 Job 의 프로젝트와 같아야 하며,
     * 신고 sha256 은 기록 해시와 같아야 한다(기록 해시로 정규화). 이미지 서비스는 프로젝트 · 입력 첨부가 필수.
     */
    private List<InputRef> verifiedInputs(CreateCommand c) {
        List<InputRef> in = c.inputs() == null ? List.of() : c.inputs();
        if (JobExecutor.imageService(c.serviceCode())
                && (c.projectId() == null || in.isEmpty() || in.get(0).attachmentId() == null)) {
            throw new DomainException(AipCodes.INVALID, c.serviceCode() + " 는 projectId 와 inputs[0].attachmentId 가 필요합니다");
        }
        List<InputRef> out = new ArrayList<>();
        for (InputRef r : in) {
            if (r.attachmentId() == null) {
                out.add(r);
                continue;
            }
            AttachmentReadApi.AttachmentInfo a = attachments.find(r.attachmentId())
                    .orElseThrow(() -> new DomainException(AipCodes.INVALID, "입력 첨부가 없습니다: " + r.attachmentId()));
            if (c.projectId() == null || !c.projectId().equals(a.projectId())) {
                throw new DomainException(AipCodes.INVALID, "입력 첨부가 이 프로젝트의 첨부가 아닙니다");
            }
            if (r.sha256() != null && !r.sha256().isBlank() && !r.sha256().equalsIgnoreCase(a.sha256())) {
                throw new DomainException(AipCodes.INVALID, "입력 첨부 sha256 이 기록과 다릅니다");
            }
            out.add(new InputRef(r.attachmentId(), a.sha256()));
        }
        return out;
    }

    private Created replayOrConflict(Map<String, Object> row, String hash, String requesterModule) {
        // SEC-2026-10-19: aip.job.idempotency_key 는 전역 UNIQUE 라 HTTP 클라이언트가 고른 키가 다른 사용자의 키와 겹칠 수 있다.
        // HTTP 직접 요청(requesterModule 없음)은 같은 사용자가 만든 Job 만 재반환하고, 남의 Job 이면 jobId 도 알려 주지 않는다.
        // 모듈 간 동기 호출(RPT · QAR — 'qar-review-{round}' 같은 업무 키)은 사용자와 무관하게 같은 Job 을 재사용하는 것이 설계다.
        UUID owner = AipJdbc.uuid(row, "requested_by");
        boolean webCaller = actor.current().isPresent();   // 배치 · 시스템 실행은 웹 경계 밖
        if (requesterModule == null && webCaller && owner != null && !owner.equals(actor.actorIdOrSystem())) {
            throw new DomainException(AipCodes.IDEMPOTENCY_MISMATCH, "이미 사용 중인 Idempotency-Key 입니다");
        }
        if (!hash.equals(AipJdbc.str(row, "input_sha256"))) {
            throw new DomainException(AipCodes.IDEMPOTENCY_MISMATCH, "같은 Idempotency-Key 로 다른 입력")
                    .with("jobId", AipJdbc.uuid(row, "id"));
        }
        return new Created(row, true);
    }

    public Optional<Map<String, Object>> byKey(String key) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("SELECT * FROM aip.job WHERE idempotency_key = ?", key);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Map<String, Object> get(UUID id) {
        return db.get("job", id);
    }

    /** 웹 목록(읽기 전용): 최근 Job — 범위 거르기는 호출자(AipAccess)가 한다 */
    public List<Map<String, Object>> list(String status, String serviceCode, int limit) {
        return db.jdbc().queryForList("""
                SELECT * FROM aip.job WHERE (?::text IS NULL OR status = ?) AND (?::text IS NULL OR service_code = ?)
                 ORDER BY queued_at DESC, id DESC LIMIT ?""", status, status, serviceCode, serviceCode, limit);
    }

    public Map<String, Object> modelRow(UUID modelVersionId) {
        return db.get("model_version", modelVersionId);
    }

    public int queuePosition(UUID id) {
        Integer n = db.jdbc().queryForObject("""
                SELECT count(*) FROM aip.job q, aip.job me WHERE me.id = ? AND me.status = 'QUEUED' AND q.status = 'QUEUED'
                   AND (q.priority, q.queued_at) < (me.priority, me.queued_at)""", Integer.class, id);
        return n == null ? 0 : n + 1;
    }

    public List<Map<String, Object>> events(UUID id) {
        db.get("job", id);
        return db.jdbc().queryForList("SELECT id, at, kind, payload FROM aip.job_event WHERE job_id = ? ORDER BY id", id);
    }

    /** 취소: QUEUED → CANCELLED, RUNNING → 협조적 취소 표시, 그 외 409 */
    @Transactional
    public Map<String, Object> cancel(UUID id) {
        Map<String, Object> j = db.lock("job", id);
        JobStatus s = JobStatus.valueOf(AipJdbc.str(j, "status"));
        if (s == JobStatus.QUEUED) {
            move(id, s, JobStatus.CANCELLED, "finished_at = now()");
        } else if (s == JobStatus.RUNNING) {
            db.jdbc().update("UPDATE aip.job SET cancel_requested = true WHERE id = ?", id);
            event(id, "LOG", Map.of("message", "cancel requested"));
        } else {
            throw new DomainException(AipCodes.INVALID_TRANSITION, "취소할 수 없는 상태: " + s).with("status", s.name());
        }
        return db.get("job", id);
    }

    // ------------------------------------------------------------------ 디큐 · 완료 · 실패 · 리퍼

    /** 설계 B7.4 디큐 SQL(에이징 포함) + 마감 지난 Job 제외. lease = leaseSeconds */
    @Transactional
    public Optional<Map<String, Object>> dequeue(String workerId, List<String> services, int freeMb, long leaseSeconds) {
        List<Map<String, Object>> rows = db.jdbc().queryForList("""
                WITH next AS (
                  SELECT id FROM aip.job
                   WHERE status = 'QUEUED' AND mode = 'ASYNC' AND not_before <= now()
                     AND (deadline_at IS NULL OR deadline_at > now())
                     AND service_code = ANY(?::text[]) AND vram_budget_mb <= ?
                   ORDER BY priority ASC, queued_at - (interval '10 minutes' * (priority - 1)) ASC
                   FOR UPDATE SKIP LOCKED LIMIT 1)
                UPDATE aip.job j SET status = 'RUNNING', started_at = now(), attempts = attempts + 1, worker_id = ?,
                       lease_until = now() + make_interval(secs => ?)
                  FROM next WHERE j.id = next.id
                RETURNING j.*""", "{" + String.join(",", services) + "}", freeMb, workerId, (double) leaseSeconds);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> j = rows.get(0);
        UUID id = AipJdbc.uuid(j, "id");
        event(id, "STATE", Map.of("from", "QUEUED", "to", "RUNNING", "worker", workerId, "attempt",
                AipJdbc.integer(j, "attempts")));
        outbox.publish(new AipEvents.JobStarted(id, AipJdbc.str(j, "service_code"), AipJdbc.integer(j, "priority")));
        return Optional.of(j);
    }

    /**
     * 성공 기록 — lease 를 잃은 뒤 늦게 끝난 워커면 false(결과 버림, TC-AIP-002 결과 1회 저장).
     * 성공 시 감사 1행 · (CRACK_DETECT + 사진 입력) 제안 생성 · aip.inference.completed 를 같은 트랜잭션에.
     */
    @Transactional
    public boolean complete(UUID id, String workerId, JobExecutor.Execution exec) {
        int n = db.jdbc().update("""
                UPDATE aip.job SET status = 'SUCCEEDED', finished_at = now(), progress_pct = 100, result_ref = ?::jsonb,
                       lease_until = NULL, error = NULL
                 WHERE id = ? AND status = 'RUNNING' AND worker_id = ?""", exec.output().toString(), id, workerId);
        if (n == 0) {
            return false;
        }
        Map<String, Object> j = db.get("job", id);
        event(id, "STATE", Map.of("from", "RUNNING", "to", "SUCCEEDED", "worker", workerId));
        Map<String, Object> mv = j.get("model_version_id") == null ? Map.of()
                : db.get("model_version", AipJdbc.uuid(j, "model_version_id"));
        Map<String, Object> pv = j.get("prompt_version_id") == null ? Map.of()
                : db.get("prompt_version", AipJdbc.uuid(j, "prompt_version_id"));
        audit.record(new InferenceAuditService.Entry(id, AipJdbc.str(j, "service_code"), AipJdbc.str(mv, "name"),
                AipJdbc.str(mv, "version"), exec.adapter(), AipJdbc.str(pv, "prompt_key"), AipJdbc.str(pv, "version"),
                AipJdbc.str(j, "input_sha256"), AipRules.sha256Hex(AipRules.canonical(exec.output())),
                stripBlobs(AipJdbc.tree(j.get("params"))), exec.provenance(), exec.retrievedChunkIds(),
                (int) Math.min(Integer.MAX_VALUE, exec.latencyMs())));
        List<UUID> created = AipServiceCode.CRACK_DETECT.name().equals(AipJdbc.str(j, "service_code"))
                ? suggestions.fromCrackDetect(j, exec.output()) : List.of();
        outbox.publish(new AipEvents.InferenceCompleted(id, AipJdbc.str(j, "service_code"),
                "db:aip.job/" + id + "#result_ref", created));
        return true;
    }

    /**
     * 실패 기록 — FAILED 를 거쳐 시도가 남았으면 QUEUED(not_before = now + 2^attempts 초), 아니면 DEAD (A6.1).
     * requeue=false(동기 호출 — 요청자가 재시도 통제)면 FAILED 에 머문다(시도 소진이면 DEAD).
     */
    @Transactional
    public JobStatus fail(UUID id, String workerId, String code, String message, boolean requeue) {
        Map<String, Object> j = db.lock("job", id);
        if (!"RUNNING".equals(AipJdbc.str(j, "status")) || !workerId.equals(AipJdbc.str(j, "worker_id"))) {
            return JobStatus.valueOf(AipJdbc.str(j, "status"));   // lease 잃음 — 무시
        }
        ObjectNode err = AipJdbc.MAPPER.createObjectNode();
        err.put("code", code);
        err.put("message", message == null ? "" : message.substring(0, Math.min(500, message.length())));
        err.put("attempt", AipJdbc.integer(j, "attempts"));
        db.jdbc().update("UPDATE aip.job SET status = 'FAILED', error = ?::jsonb, lease_until = NULL, finished_at = now() "
                + "WHERE id = ?", err.toString(), id);
        event(id, "STATE", Map.of("from", "RUNNING", "to", "FAILED", "code", code));
        int attempts = AipJdbc.integer(j, "attempts");
        int max = AipJdbc.integer(j, "max_attempts");
        JobStatus next = JobStatus.afterFailure(attempts, max);
        JobStatus result = JobStatus.FAILED;
        if (next == JobStatus.DEAD) {
            move(id, JobStatus.FAILED, JobStatus.DEAD, "finished_at = now()");
            result = JobStatus.DEAD;
        } else if (requeue) {
            move(id, JobStatus.FAILED, JobStatus.QUEUED,
                    "worker_id = NULL, finished_at = NULL, not_before = now() + make_interval(secs => " + JobStatus.backoffSeconds(attempts) + ")");
            result = JobStatus.QUEUED;
        }
        outbox.publish(new AipEvents.JobFailed(id, AipJdbc.str(j, "service_code"), AipJdbc.integer(j, "priority"),
                result == JobStatus.DEAD ? "DEAD" : "FAILED", code));
        return result;
    }

    public record ReapSummary(int requeued, int dead, int preempted, int deadlineExceeded) {}

    /** 리퍼(11-IMPL Step 2): lease 만료 RUNNING → QUEUED(백오프)/DEAD, PREEMPTED → QUEUED, 마감 지난 QUEUED → DEAD */
    @Transactional
    public ReapSummary reap() {
        int requeued = 0;
        int dead = 0;
        for (Map<String, Object> j : db.jdbc().queryForList(
                "SELECT * FROM aip.job WHERE status = 'RUNNING' AND lease_until < now() FOR UPDATE SKIP LOCKED")) {
            UUID id = AipJdbc.uuid(j, "id");
            int attempts = AipJdbc.integer(j, "attempts");
            if (JobStatus.afterFailure(attempts, AipJdbc.integer(j, "max_attempts")) == JobStatus.DEAD) {
                db.jdbc().update("UPDATE aip.job SET status = 'FAILED', worker_id = NULL, lease_until = NULL, "
                        + "error = '{\"code\":\"LEASE_EXPIRED\"}'::jsonb WHERE id = ?", id);
                event(id, "STATE", Map.of("from", "RUNNING", "to", "FAILED", "code", "LEASE_EXPIRED"));
                move(id, JobStatus.FAILED, JobStatus.DEAD, "finished_at = now()");
                outbox.publish(new AipEvents.JobFailed(id, AipJdbc.str(j, "service_code"), AipJdbc.integer(j, "priority"),
                        "DEAD", "LEASE_EXPIRED"));
                dead++;
            } else {
                db.jdbc().update("UPDATE aip.job SET status = 'FAILED', worker_id = NULL, lease_until = NULL WHERE id = ?", id);
                event(id, "STATE", Map.of("from", "RUNNING", "to", "FAILED", "code", "LEASE_EXPIRED"));
                move(id, JobStatus.FAILED, JobStatus.QUEUED,
                        "not_before = now() + make_interval(secs => " + JobStatus.backoffSeconds(attempts) + ")");
                requeued++;
            }
        }
        int pre = 0;
        for (UUID id : db.jdbc().queryForList("SELECT id FROM aip.job WHERE status = 'PREEMPTED' FOR UPDATE SKIP LOCKED",
                UUID.class)) {
            move(id, JobStatus.PREEMPTED, JobStatus.QUEUED, "worker_id = NULL, lease_until = NULL");
            pre++;
        }
        int late = 0;
        for (Map<String, Object> j : db.jdbc().queryForList("""
                SELECT * FROM aip.job WHERE status = 'QUEUED' AND deadline_at IS NOT NULL AND deadline_at <= now()
                FOR UPDATE SKIP LOCKED""")) {
            UUID id = AipJdbc.uuid(j, "id");
            move(id, JobStatus.QUEUED, JobStatus.DEAD, "finished_at = now(), error = '{\"code\":\"DEADLINE_EXCEEDED\"}'::jsonb");
            outbox.publish(new AipEvents.JobFailed(id, AipJdbc.str(j, "service_code"), AipJdbc.integer(j, "priority"),
                    "DEAD", "DEADLINE_EXCEEDED"));
            late++;
        }
        return new ReapSummary(requeued, dead, pre, late);
    }

    /** 협조적 취소: 내가 실행 중인 Job 에 취소 요청이 있으면 RUNNING → CANCELLED(결과 버림) */
    @Transactional
    public boolean cancelIfRequested(UUID id, String workerId) {
        Map<String, Object> j = db.lock("job", id);
        if ("RUNNING".equals(AipJdbc.str(j, "status")) && workerId.equals(AipJdbc.str(j, "worker_id"))
                && Boolean.TRUE.equals(j.get("cancel_requested"))) {
            move(id, JobStatus.RUNNING, JobStatus.CANCELLED, "finished_at = now(), lease_until = NULL");
            return true;
        }
        return false;
    }

    /** 학습 창 · OOM 선점(RUNNING → PREEMPTED, A8) */
    @Transactional
    public void preempt(UUID id) {
        Map<String, Object> j = db.lock("job", id);
        move(id, JobStatus.valueOf(AipJdbc.str(j, "status")), JobStatus.PREEMPTED, "lease_until = NULL");
    }

    private void move(UUID id, JobStatus from, JobStatus to, String extraSet) {
        if (!from.canMove(to)) {
            throw new DomainException(AipCodes.INVALID_TRANSITION, "Job 상태 전이 불가: " + from + " → " + to);
        }
        db.jdbc().update("UPDATE aip.job SET status = ?" + (extraSet == null ? "" : ", " + extraSet) + " WHERE id = ?",
                to.name(), id);
        event(id, "STATE", Map.of("from", from.name(), "to", to.name()));
    }

    void event(UUID id, String kind, Map<String, Object> payload) {
        db.jdbc().update("INSERT INTO aip.job_event(job_id, kind, payload) VALUES (?, ?, ?::jsonb)", id, kind,
                AipJdbc.json(new LinkedHashMap<>(payload)));
    }

    /** 동기 실행 준비: 새 Job(또는 재시도 가능한 기존 Job)을 RUNNING 으로 올리고 이벤트 기록 */
    @Transactional
    public Map<String, Object> startSync(UUID id, String workerId, long leaseSeconds) {
        Map<String, Object> j = db.lock("job", id);
        JobStatus s = JobStatus.valueOf(AipJdbc.str(j, "status"));
        if (s == JobStatus.FAILED) {
            move(id, s, JobStatus.QUEUED, "error = NULL, finished_at = NULL");
            s = JobStatus.QUEUED;
        }
        if (s != JobStatus.QUEUED) {
            throw new DomainException(AipCodes.INVALID_TRANSITION, "동기 실행 불가 상태: " + s).with("status", s.name());
        }
        db.jdbc().update("""
                UPDATE aip.job SET status = 'RUNNING', started_at = now(), attempts = attempts + 1, worker_id = ?,
                       lease_until = now() + make_interval(secs => ?) WHERE id = ?""", workerId, (double) leaseSeconds, id);
        event(id, "STATE", Map.of("from", "QUEUED", "to", "RUNNING", "worker", workerId));
        outbox.publish(new AipEvents.JobStarted(id, AipJdbc.str(j, "service_code"), AipJdbc.integer(j, "priority")));
        return db.get("job", id);
    }
}
