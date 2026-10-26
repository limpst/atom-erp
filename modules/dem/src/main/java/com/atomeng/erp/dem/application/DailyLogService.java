package com.atomeng.erp.dem.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.evidence.HashChain;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.atomeng.erp.dem.api.DemEvents;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감리일지 (FR-DEM-007, BR-DEM-005·006, A8 오프라인, 10-IMPL Step 4·5·7, TC-DEM-004·005).
 * <ul>
 *   <li>업서트 키 (work_package, log_date, author) — 지정 감리자(ACTIVE)만, 감리자 공백이면 409</li>
 *   <li>오프라인 동기화: {@code clientOpId} 필수. 같은 clientOpId 재전송 = 저장된 같은 응답(replayed=true), 재처리 0</li>
 *   <li>If-Match(선택) 불일치 412, 사진 필수 항목 PASS 에 사진 없음 422(BR-DEM-005), FAIL → 시정지시 자동 발행</li>
 *   <li>서버 수신이 log_date 다음 날 00:00(KST) 이후면 late_sync. 이미 LOCKED 면 원문은 그대로 두고 Amendment 초안(PENDING) — 202</li>
 *   <li>자동 잠금: 전일 이전 미잠금 일지 → 정규화 JSON(키 정렬 · 항목 seq 정렬 · 사진은 sha256 정렬 목록) SHA-256 + LOCKED</li>
 * </ul>
 */
@Service
public class DailyLogService {

    public record ChecklistInput(UUID templateItemId, String result, String severity, String note, List<UUID> photoIds) {}

    public record Upsert(UUID clientOpId, String phaseCode, Integer progressPct, Integer workersCount,
                         Map<String, Object> weather, List<Map<String, Object>> equipment, List<ChecklistInput> checklist,
                         String remarks, Instant deviceSignedAt, boolean submit) {}

    /** httpStatus 200 · 202(잠금 후 도착 → Amendment 초안) */
    public record Result(int httpStatus, long version, Map<String, Object> body) {}

    private static final ObjectMapper CANONICAL = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final DemJdbc dem;
    private final SupervisorService supervisors;
    private final RuleParams rules;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final ObjectMapper om;
    private final Clock clock;

    private final DemAccess access;

    public DailyLogService(DemJdbc dem, SupervisorService supervisors, RuleParams rules, OutboxPublisher outbox,
                           AuditLog audit, CurrentActor actor, ObjectMapper om, Clock clock, DemAccess access) {
        this.access = access;
        this.dem = dem;
        this.supervisors = supervisors;
        this.rules = rules;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.om = om;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(DemProjectService.SEOUL));
    }

    // ------------------------------------------------------------------ 점검표 템플릿 (Step 4)

    /** 일지 작성일 기준 유효 템플릿(effective_from ≤ 날짜 중 최신 버전) */
    public Map<String, Object> resolveTemplate(String phaseCode, LocalDate on) {
        List<Map<String, Object>> t = dem.jdbc().queryForList("""
                SELECT id, phase_code, version_no, effective_from, legal_basis, approved_by FROM dem.checklist_template
                 WHERE phase_code = ? AND effective_from <= ? ORDER BY version_no DESC LIMIT 1""", phaseCode,
                DemJdbc.d(on));
        if (t.isEmpty()) {
            throw new DomainException(DemErrors.RULE_MISSING, "점검표 템플릿 없음: " + phaseCode + " @" + on)
                    .with("reason", "DEM_TEMPLATE_MISSING");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.get(0).get("id"));
        m.put("phaseCode", t.get(0).get("phase_code"));
        m.put("versionNo", t.get(0).get("version_no"));
        m.put("effectiveFrom", DemJdbc.ld(t.get(0).get("effective_from")));
        m.put("legalBasis", t.get(0).get("legal_basis"));
        m.put("example", t.get(0).get("approved_by") == null);
        List<Map<String, Object>> items = new ArrayList<>();
        for (var i : dem.jdbc().queryForList("""
                SELECT id, seq, title, photo_required, default_severity FROM dem.checklist_template_item
                 WHERE template_id = ? ORDER BY seq""", t.get(0).get("id"))) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", i.get("id"));
            x.put("seq", i.get("seq"));
            x.put("title", i.get("title"));
            x.put("photoRequired", i.get("photo_required"));
            x.put("defaultSeverity", i.get("default_severity"));
            items.add(x);
        }
        m.put("items", items);
        return m;
    }

    // ------------------------------------------------------------------ 업서트 (Step 5 + A8 오프라인)

    @Transactional
    public Result upsert(UUID wpId, LocalDate date, Upsert u, Long expectedVersion) {
        UUID author = actor.actorIdOrSystem();
        if (u.clientOpId() == null) {
            throw new DomainException(DemErrors.INVALID, "clientOpId 필수(오프라인 동기화 멱등 키)");
        }
        // 같은 clientOpId 동시 재전송 직렬화 → 먼저 커밋된 결과를 그대로 재생
        dem.jdbc().queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) l", Integer.class,
                "dem-op:" + u.clientOpId());
        var prior = dem.jdbc().queryForList("SELECT daily_log_id, http_status, response::text AS body, actor_id "
                + "FROM dem.sync_op WHERE client_op_id = ?", u.clientOpId());
        if (!prior.isEmpty()) {
            return replay(prior.get(0), author, wpId);
        }
        if (date.isAfter(today())) {
            throw new DomainException(DemErrors.INVALID, "미래 날짜 일지는 작성할 수 없습니다: " + date);
        }
        var wp = dem.one("업무 패키지", "SELECT supervisor_gap FROM dem.work_package WHERE id = ? AND deleted_at IS NULL",
                wpId);
        if (!supervisors.isActiveSupervisor(wpId, author, date)) {
            throw new DomainException(DemErrors.NOT_ASSIGNED_SUPERVISOR, "이 업무 패키지 · 날짜의 지정 감리자가 아닙니다")
                    .with("reason", "DEM_NOT_ASSIGNED_SUPERVISOR");
        }
        if (Boolean.TRUE.equals(wp.get("supervisor_gap"))) {
            throw new DomainException(DemErrors.SUPERVISOR_GAP,
                    "감리자 공백(퇴사 · 자격 만료) — 대체 책임감리자 지정 전에는 일지를 작성할 수 없습니다")
                    .with("reason", "DEM_SUPERVISOR_GAP");
        }
        Map<String, Object> template = resolveTemplate(u.phaseCode(), date);
        UUID templateId = (UUID) template.get("id");
        Instant now = clock.instant();
        int created = dem.jdbc().update("""
                INSERT INTO dem.daily_log(id, work_package_id, log_date, author_id, template_id, phase_code,
                                          server_received_at, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (work_package_id, log_date, author_id) DO NOTHING""",
                UuidV7.next(), wpId, DemJdbc.d(date), author, templateId, u.phaseCode(), Timestamp.from(now), author,
                author);
        var log = dem.one("감리일지", """
                SELECT id, status, version, content_sha256 FROM dem.daily_log
                 WHERE work_package_id = ? AND log_date = ? AND author_id = ? FOR UPDATE""", wpId, DemJdbc.d(date), author);
        UUID logId = (UUID) log.get("id");
        long version = ((Number) log.get("version")).longValue();
        if ("LOCKED".equals(log.get("status"))) {
            return lockedArrival(logId, version, (String) log.get("content_sha256"), u, author, now);
        }
        if (expectedVersion != null && created == 0) {
            IfMatch.check(expectedVersion, version);
        }
        validateChecklist(templateId, wpId, u.checklist());

        boolean lateSync = !now.isBefore(date.plusDays(1).atStartOfDay(DemProjectService.SEOUL).toInstant());
        boolean wasSubmitted = "SUBMITTED".equals(log.get("status"));
        String status = u.submit() || wasSubmitted ? "SUBMITTED" : "DRAFT";
        long newVersion = created == 1 ? version : version + 1;
        dem.jdbc().update("""
                UPDATE dem.daily_log SET template_id = ?, phase_code = ?, progress_pct = ?, workers_count = ?,
                       weather_json = ?::jsonb, equipment_json = ?::jsonb, remarks = ?, status = ?,
                       device_signed_at = coalesce(?, device_signed_at), server_received_at = ?, late_sync = ?,
                       updated_at = ?, updated_by = ?, version = ?
                 WHERE id = ?""", templateId, u.phaseCode(), u.progressPct(), u.workersCount(), dem.json(u.weather()),
                dem.json(u.equipment()), u.remarks(), status, DemJdbc.ts(u.deviceSignedAt()), Timestamp.from(now),
                lateSync, Timestamp.from(now), author, newVersion, logId);

        List<Map<String, Object>> orders = new ArrayList<>();
        int failCount = 0;
        for (ChecklistInput c : u.checklist() == null ? List.<ChecklistInput>of() : u.checklist()) {
            UUID resultId = upsertResult(logId, c);
            if ("FAIL".equals(c.result())) {
                failCount++;
                Map<String, Object> o = issueOrderIfAbsent(resultId, wpId, c, date);
                if (o != null) {
                    orders.add(o);
                }
            }
        }
        if (u.submit() && !wasSubmitted) {
            outbox.publish(new DemEvents.DailyLogSubmitted(logId, wpId, date, failCount, lateSync));
        }
        Map<String, Object> body = view(logId);
        body.put("correctiveOrders", orders);
        body.put("amendmentPending", false);
        body.put("replayed", false);
        body.put("warnings", lateSync ? List.of("LATE_SYNC") : List.of());
        record(u.clientOpId(), logId, 200, body, author);
        return new Result(200, newVersion, body);
    }

    private Result replay(Map<String, Object> prior, UUID author, UUID wpId) {
        if (!author.equals(prior.get("actor_id"))) {
            throw new DomainException(DemErrors.INVALID, "clientOpId 가 다른 사용자의 작업에 이미 쓰였습니다");
        }
        UUID logId = (UUID) prior.get("daily_log_id");
        if (logId != null) {
            UUID priorWp = dem.jdbc().queryForObject("SELECT work_package_id FROM dem.daily_log WHERE id = ?",
                    UUID.class, logId);
            if (!wpId.equals(priorWp)) {
                throw new DomainException(DemErrors.INVALID, "clientOpId 가 다른 업무 패키지 일지에 이미 쓰였습니다");
            }
        }
        JsonNode stored = dem.tree((String) prior.get("body"));
        @SuppressWarnings("unchecked")
        Map<String, Object> body = om.convertValue(stored, LinkedHashMap.class);
        body.put("replayed", true);
        long version = stored.path("version").asLong();
        return new Result(((Number) prior.get("http_status")).intValue(), version, body);
    }

    /** 잠금 이후 도착한 동기화(B7.1 · TC-DEM-005): 원문 · 잠금 해시는 그대로, 자동 Amendment 초안(PENDING) → PM 확인 대기 */
    private Result lockedArrival(UUID logId, long version, String sha, Upsert u, UUID author, Instant now) {
        UUID amendmentId = UuidV7.next();
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("phaseCode", u.phaseCode());
        proposed.put("progressPct", u.progressPct());
        proposed.put("workersCount", u.workersCount());
        proposed.put("weather", u.weather());
        proposed.put("equipment", u.equipment());
        proposed.put("checklist", u.checklist());
        proposed.put("remarks", u.remarks());
        proposed.put("deviceSignedAt", u.deviceSignedAt() == null ? null : u.deviceSignedAt().toString());
        proposed.put("serverReceivedAt", now.toString());
        dem.jdbc().update("""
                INSERT INTO dem.log_amendment(id, daily_log_id, field_path, old_value, new_value, reason, status,
                                              client_op_id, amended_by)
                VALUES (?, ?, '$', NULL, ?::jsonb, ?, 'PENDING', ?, ?)""", amendmentId, logId, dem.json(proposed),
                "LATE_SYNC_AFTER_LOCK — 잠금 이후 도착한 오프라인 동기화(PM 확인 대기)", u.clientOpId(), author);
        Map<String, Object> body = view(logId);
        body.put("amendmentPending", true);
        body.put("amendmentId", amendmentId);
        body.put("contentSha256", sha);
        body.put("correctiveOrders", List.of());
        body.put("replayed", false);
        body.put("warnings", List.of("LOCKED_LOG_AMENDMENT_PENDING"));
        record(u.clientOpId(), logId, 202, body, author);
        return new Result(202, version, body);
    }

    private void record(UUID clientOpId, UUID logId, int status, Map<String, Object> body, UUID actorId) {
        dem.jdbc().update("""
                INSERT INTO dem.sync_op(client_op_id, daily_log_id, op_kind, http_status, response, actor_id)
                VALUES (?, ?, 'DAILY_LOG_UPSERT', ?, ?::jsonb, ?)""", clientOpId, logId, status, dem.json(body), actorId);
    }

    /** 항목 소속 · 사진 존재(같은 업무 패키지) · BR-DEM-005 사진 필수 */
    private void validateChecklist(UUID templateId, UUID wpId, List<ChecklistInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            return;
        }
        Map<UUID, Boolean> photoRequired = new HashMap<>();
        for (var r : dem.jdbc().queryForList("SELECT id, photo_required FROM dem.checklist_template_item "
                + "WHERE template_id = ?", templateId)) {
            photoRequired.put((UUID) r.get("id"), (Boolean) r.get("photo_required"));
        }
        List<UUID> unknownItems = new ArrayList<>();
        List<UUID> missingPhotos = new ArrayList<>();
        Set<UUID> photoIds = new HashSet<>();
        Set<UUID> seen = new HashSet<>();
        for (ChecklistInput c : inputs) {
            if (c.templateItemId() == null || !photoRequired.containsKey(c.templateItemId())) {
                unknownItems.add(c.templateItemId());
                continue;
            }
            if (!seen.add(c.templateItemId())) {
                throw new DomainException(DemErrors.INVALID, "같은 점검 항목이 두 번 있습니다: " + c.templateItemId());
            }
            boolean noPhoto = c.photoIds() == null || c.photoIds().isEmpty();
            if ("PASS".equals(c.result()) && photoRequired.get(c.templateItemId()) && noPhoto) {
                missingPhotos.add(c.templateItemId());
            }
            if (c.photoIds() != null) {
                photoIds.addAll(c.photoIds());
            }
        }
        if (!unknownItems.isEmpty()) {
            throw new DomainException(DemErrors.INVALID, "템플릿에 없는 점검 항목 %d건".formatted(unknownItems.size()))
                    .with("invalidItems", unknownItems);
        }
        if (!missingPhotos.isEmpty()) {
            throw new DomainException(DemErrors.PHOTO_REQUIRED,
                    "사진 필수 항목 %d건에 사진이 없습니다(BR-DEM-005)".formatted(missingPhotos.size()))
                    .with("reason", "DEM_PHOTO_REQUIRED").with("invalidItems", missingPhotos);
        }
        if (!photoIds.isEmpty()) {
            List<UUID> known = dem.jdbc().queryForList("SELECT id FROM dem.evidence_photo WHERE work_package_id = ? "
                    + "AND id = ANY(?::uuid[])", UUID.class, wpId, DemJdbc.uuidArray(photoIds));
            if (known.size() != photoIds.size()) {
                Set<UUID> unknown = new HashSet<>(photoIds);
                known.forEach(unknown::remove);
                throw new DomainException(DemErrors.INVALID, "이 업무 패키지에 등록되지 않은 증거 사진 %d건(사진 먼저 동기화)"
                        .formatted(unknown.size())).with("unknownPhotoIds", unknown.stream().sorted().toList());
            }
        }
    }

    private UUID upsertResult(UUID logId, ChecklistInput c) {
        UUID id = dem.jdbc().queryForObject("""
                INSERT INTO dem.checklist_result(id, daily_log_id, template_item_id, result, severity, note)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (daily_log_id, template_item_id) DO UPDATE
                   SET result = EXCLUDED.result, severity = EXCLUDED.severity, note = EXCLUDED.note
                RETURNING id""", UUID.class, UuidV7.next(), logId, c.templateItemId(), c.result(), c.severity(), c.note());
        dem.jdbc().update("DELETE FROM dem.checklist_photo WHERE checklist_result_id = ?", id);
        if (c.photoIds() != null) {
            for (UUID p : new java.util.LinkedHashSet<>(c.photoIds())) {
                dem.jdbc().update("INSERT INTO dem.checklist_photo(checklist_result_id, evidence_photo_id) VALUES (?, ?)",
                        id, p);
            }
        }
        return id;
    }

    /** FAIL → 시정지시 CO-YYYY-NNNN, 기한 = 심각도별 rule CO_DUE_HOURS, CRITICAL → 작업중지 요청 표시 */
    private Map<String, Object> issueOrderIfAbsent(UUID resultId, UUID wpId, ChecklistInput c, LocalDate date) {
        Integer existing = dem.jdbc().queryForObject("SELECT count(*) FROM dem.corrective_order WHERE checklist_result_id = ?",
                Integer.class, resultId);
        if (existing != null && existing > 0) {
            return null;
        }
        String severity = c.severity() != null ? c.severity() : dem.jdbc().queryForObject(
                "SELECT default_severity FROM dem.checklist_template_item WHERE id = ?", String.class, c.templateItemId());
        if (c.severity() == null) {
            dem.jdbc().update("UPDATE dem.checklist_result SET severity = ? WHERE id = ?", severity, resultId);
        }
        int hours = rules.get("CO_DUE_HOURS", date).path(severity).asInt(48);
        Instant now = clock.instant();
        Instant due = now.plus(Duration.ofHours(hours));
        boolean stop = "CRITICAL".equals(severity);
        UUID id = UuidV7.next();
        String no = dem.nextCode("CO", LocalDate.ofInstant(now, DemProjectService.SEOUL).getYear());
        UUID by = actor.actorIdOrSystem();
        dem.jdbc().update("""
                INSERT INTO dem.corrective_order(id, checklist_result_id, order_no, severity, due_at, stop_work_requested,
                                                 created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", id, resultId, no, severity, Timestamp.from(due), stop, by, by);
        outbox.publish(new DemEvents.CorrectiveOrderIssued(id, no, wpId, severity, due, stop));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", no);
        m.put("severity", severity);
        m.put("dueAt", due);
        m.put("stopWorkRequested", stop);
        return m;
    }

    public Map<String, Object> view(UUID logId) {
        var r = dem.one("감리일지", """
                SELECT id, work_package_id, log_date, author_id, template_id, phase_code, progress_pct, workers_count,
                       weather_json::text AS weather, equipment_json::text AS equipment, remarks, status, device_signed_at,
                       server_received_at, late_sync, locked_at, content_sha256, version
                  FROM dem.daily_log WHERE id = ?""", logId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("workPackageId", r.get("work_package_id"));
        m.put("logDate", DemJdbc.ld(r.get("log_date")).toString());
        m.put("authorId", r.get("author_id"));
        m.put("templateId", r.get("template_id"));
        m.put("phaseCode", r.get("phase_code"));
        m.put("progressPct", r.get("progress_pct"));
        m.put("workersCount", r.get("workers_count"));
        m.put("weather", dem.tree((String) r.get("weather")));
        m.put("equipment", dem.tree((String) r.get("equipment")));
        m.put("remarks", r.get("remarks"));
        m.put("status", r.get("status"));
        m.put("deviceSignedAt", str(DemJdbc.inst(r.get("device_signed_at"))));
        m.put("serverReceivedAt", str(DemJdbc.inst(r.get("server_received_at"))));
        m.put("lateSync", r.get("late_sync"));
        m.put("lockedAt", str(DemJdbc.inst(r.get("locked_at"))));
        m.put("contentSha256", r.get("content_sha256"));
        m.put("version", ((Number) r.get("version")).longValue());
        List<Map<String, Object>> results = new ArrayList<>();
        for (var c : dem.jdbc().queryForList("""
                SELECT cr.id, cr.template_item_id, i.seq, i.title, cr.result, cr.severity, cr.note,
                       (SELECT array_agg(cp.evidence_photo_id::text ORDER BY cp.evidence_photo_id)
                          FROM dem.checklist_photo cp WHERE cp.checklist_result_id = cr.id)::text AS photos,
                       co.order_no
                  FROM dem.checklist_result cr JOIN dem.checklist_template_item i ON i.id = cr.template_item_id
                  LEFT JOIN dem.corrective_order co ON co.checklist_result_id = cr.id
                 WHERE cr.daily_log_id = ? ORDER BY i.seq""", logId)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", c.get("id"));
            x.put("templateItemId", c.get("template_item_id"));
            x.put("seq", c.get("seq"));
            x.put("title", c.get("title"));
            x.put("result", c.get("result"));
            x.put("severity", c.get("severity"));
            x.put("note", c.get("note"));
            String photos = (String) c.get("photos");
            x.put("photoIds", photos == null ? List.of() : List.of(photos.substring(1, photos.length() - 1).split(",")));
            x.put("correctiveOrderNo", c.get("order_no"));
            results.add(x);
        }
        m.put("checklist", results);
        return m;
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    /**
     * GET /daily-logs/{id}({@link #read}) 범위 검사(보안 검토 2026-10-19: 경로 id 로 다른 프로젝트 · 다른 고객사 일지 열람 차단).
     * PROJECT 범위는 투입 인원 외에 일지 작성자 · 해당 패키지 지정 감리자도 허용(직접 작성 · 담당하는 일지).
     */
    private void requireReadScope(UUID logId) {
        var r = dem.one("감리일지", """
                SELECT l.author_id, l.work_package_id, w.project_id
                  FROM dem.daily_log l JOIN dem.work_package w ON w.id = l.work_package_id WHERE l.id = ?""", logId);
        UUID wp = (UUID) r.get("work_package_id");
        UUID author = (UUID) r.get("author_id");
        access.requireProject("dem:supervision-log:read", (UUID) r.get("project_id"), emp -> emp.equals(author)
                || Boolean.TRUE.equals(dem.jdbc().queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM dem.supervisor_assignment
                                        WHERE work_package_id = ? AND employee_id = ? AND status = 'ACTIVE')""",
                        Boolean.class, wp, emp)));
    }

    /** 원문 + 정정 이력 + 유효 뷰(APPLIED 정정을 순서대로 최상위 필드에 적용) */
    public Map<String, Object> read(UUID logId) {
        requireReadScope(logId);
        return detail(logId);
    }

    public Map<String, Object> detail(UUID logId) {
        Map<String, Object> m = view(logId);
        List<Map<String, Object>> amendments = new ArrayList<>();
        Map<String, Object> effective = new LinkedHashMap<>(canonicalView(logId));
        for (var a : dem.jdbc().queryForList("""
                SELECT a.id, a.field_path, a.old_value::text AS old_value, a.new_value::text AS new_value, a.reason,
                       CASE WHEN a.status = 'APPLIED' THEN 'APPLIED'
                            WHEN d.decision = 'APPROVED' THEN 'APPLIED'
                            WHEN d.decision = 'REJECTED' THEN 'REJECTED'
                            ELSE 'PENDING' END AS status,
                       a.status AS requested_status, a.amended_by, a.amended_at,
                       d.decided_by, d.decided_at, d.note AS decision_note
                  FROM dem.log_amendment a LEFT JOIN dem.log_amendment_decision d ON d.amendment_id = a.id
                 WHERE a.daily_log_id = ? ORDER BY a.amended_at, a.id""",
                logId)) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("id", a.get("id"));
            x.put("fieldPath", a.get("field_path"));
            x.put("oldValue", dem.tree((String) a.get("old_value")));
            x.put("newValue", dem.tree((String) a.get("new_value")));
            x.put("reason", a.get("reason"));
            x.put("status", a.get("status"));
            x.put("amendedBy", a.get("amended_by"));
            x.put("amendedAt", str(DemJdbc.inst(a.get("amended_at"))));
            // DEM-03(b): 본인 일지 정정 · 잠금 후 동기화는 요청(PENDING) → 다른 사람의 승인(APPROVED → 유효 뷰 반영)
            x.put("request", "PENDING".equals(a.get("requested_status")));
            x.put("decidedBy", a.get("decided_by"));
            x.put("decidedAt", str(DemJdbc.inst(a.get("decided_at"))));
            x.put("decisionNote", a.get("decision_note"));
            amendments.add(x);
            if ("APPLIED".equals(a.get("status"))) {
                effective.put((String) a.get("field_path"), dem.tree((String) a.get("new_value")));
            }
        }
        m.put("amendments", amendments);
        m.put("effective", effective);
        return m;
    }

    // ------------------------------------------------------------------ 자동 잠금 · Amendment (Step 7)

    /** 정규화 뷰 — 키 정렬 · 점검 항목 seq 정렬 · 사진은 sha256 정렬 목록(사진 교체 위변조 방지) */
    Map<String, Object> canonicalView(UUID logId) {
        var r = dem.one("감리일지", """
                SELECT id, work_package_id, log_date, author_id, template_id, phase_code, progress_pct, workers_count,
                       weather_json::text AS weather, equipment_json::text AS equipment, remarks, device_signed_at
                  FROM dem.daily_log WHERE id = ?""", logId);
        Map<String, Object> m = new TreeMap<>();
        m.put("id", r.get("id").toString());
        m.put("workPackageId", r.get("work_package_id").toString());
        m.put("logDate", DemJdbc.ld(r.get("log_date")).toString());
        m.put("authorId", r.get("author_id").toString());
        m.put("templateId", r.get("template_id").toString());
        m.put("phaseCode", r.get("phase_code"));
        m.put("progressPct", r.get("progress_pct") == null ? null : ((Number) r.get("progress_pct")).intValue());
        m.put("workersCount", r.get("workers_count") == null ? null : ((Number) r.get("workers_count")).intValue());
        m.put("weather", plain((String) r.get("weather")));
        m.put("equipment", plain((String) r.get("equipment")));
        m.put("remarks", r.get("remarks"));
        m.put("deviceSignedAt", str(DemJdbc.inst(r.get("device_signed_at"))));
        List<Map<String, Object>> items = new ArrayList<>();
        for (var c : dem.jdbc().queryForList("""
                SELECT cr.id, i.seq, cr.template_item_id, cr.result, cr.severity, cr.note,
                       (SELECT array_agg(p.sha256 ORDER BY p.sha256) FROM dem.checklist_photo cp
                          JOIN dem.evidence_photo p ON p.id = cp.evidence_photo_id
                         WHERE cp.checklist_result_id = cr.id)::text AS shas
                  FROM dem.checklist_result cr JOIN dem.checklist_template_item i ON i.id = cr.template_item_id
                 WHERE cr.daily_log_id = ? ORDER BY i.seq""", logId)) {
            Map<String, Object> x = new TreeMap<>();
            x.put("seq", ((Number) c.get("seq")).intValue());
            x.put("templateItemId", c.get("template_item_id").toString());
            x.put("result", c.get("result"));
            x.put("severity", c.get("severity"));
            x.put("note", c.get("note"));
            String shas = (String) c.get("shas");
            x.put("photoSha256", shas == null ? List.of() : List.of(shas.substring(1, shas.length() - 1).split(",")));
            items.add(x);
        }
        m.put("checklist", items);
        return m;
    }

    private Object plain(String json) {
        return json == null ? null : om.convertValue(dem.tree(json), Object.class);
    }

    /** 정규화 JSON 문자열(키 정렬 — 중첩 Map 포함) */
    public String canonicalJson(UUID logId) {
        try {
            return CANONICAL.writeValueAsString(canonicalView(logId));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** BR-DEM-006: today(KST) 이전 날짜의 미잠금 일지 전부 잠금(미제출 DRAFT 도 잠금 — PM 알림 대상 표시) */
    @Transactional
    public int lockDue() {
        LocalDate today = today();
        int n = 0;
        for (var r : dem.jdbc().queryForList("""
                SELECT id, work_package_id, log_date, status FROM dem.daily_log
                 WHERE log_date < ? AND status <> 'LOCKED' AND deleted_at IS NULL
                 ORDER BY log_date, id FOR UPDATE SKIP LOCKED""", DemJdbc.d(today))) {
            UUID id = (UUID) r.get("id");
            String sha = HashChain.sha256(new ByteArrayInputStream(canonicalJson(id).getBytes(StandardCharsets.UTF_8)));
            dem.jdbc().update("""
                    UPDATE dem.daily_log SET status = 'LOCKED', locked_at = ?, content_sha256 = ?, updated_at = now(),
                           version = version + 1 WHERE id = ?""", Timestamp.from(clock.instant()), sha, id);
            boolean submitted = "SUBMITTED".equals(r.get("status"));
            outbox.publish(new DemEvents.DailyLogLocked(id, (UUID) r.get("work_package_id"), DemJdbc.ld(r.get("log_date")),
                    sha, submitted));
            n++;
        }
        return n;
    }

    public static final Set<String> AMENDABLE = Set.of("phaseCode", "progressPct", "workersCount", "weather",
            "equipment", "remarks", "checklist");

    /**
     * 잠금 일지 정정 — 원문(daily_log)은 그대로, 정정 행(append-only)만 추가.
     * 보안 검토 2026-10-19 DEM-03(b): 본인(일지 작성자) 정정은 즉시 적용하지 않고 정정 요청(PENDING)으로 남긴다 →
     * 다른 사람이 {@link #decideAmendment} 로 승인해야 유효 뷰에 반영. 남의 일지 정정은 승인 권한자
     * (dem:supervision-log:approve — PM · EXEC)만 즉시 적용, 그 외(감리자 ENG)는 403.
     * DEM-02: 범위 = 투입 인원 또는 일지 작성자 본인.
     */
    @Transactional
    public Map<String, Object> amend(UUID logId, String fieldPath, Object newValue, String reason) {
        var log = dem.one("감리일지", """
                SELECT l.status, l.author_id, w.project_id FROM dem.daily_log l
                  JOIN dem.work_package w ON w.id = l.work_package_id WHERE l.id = ? FOR UPDATE OF l""", logId);
        UUID author = (UUID) log.get("author_id");
        access.requireProject("dem:log-amendment:create", (UUID) log.get("project_id"), emp -> emp.equals(author));
        if (!"LOCKED".equals(log.get("status"))) {
            throw new DomainException(DemErrors.INVALID, "잠금 전 일지는 Amendment 대신 일지 업서트로 고칩니다")
                    .with("reason", "DEM_LOG_NOT_LOCKED");
        }
        if (!AMENDABLE.contains(fieldPath)) {
            throw new DomainException(DemErrors.INVALID, "정정할 수 없는 필드: " + fieldPath).with("allowed",
                    AMENDABLE.stream().sorted().toList());
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException(DemErrors.INVALID, "정정 사유 필수");
        }
        Object oldValue = canonicalView(logId).get(fieldPath);
        UUID by = actor.actorIdOrSystem();
        boolean own = by.equals(author);
        if (!own && !access.hasPermission("dem:supervision-log:approve")) {
            throw new DomainException(DemErrors.AMENDMENT_NOT_ALLOWED,
                    "남의 감리일지는 승인 권한자만 정정합니다 — 감리자는 자기 일지 정정 요청만 올릴 수 있습니다")
                    .with("reason", "DEM_AMENDMENT_NOT_ALLOWED");
        }
        String status = own ? "PENDING" : "APPLIED";
        UUID id = UuidV7.next();
        dem.jdbc().update("""
                INSERT INTO dem.log_amendment(id, daily_log_id, field_path, old_value, new_value, reason, status, amended_by)
                VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)""", id, logId, fieldPath, dem.json(oldValue),
                dem.json(newValue), reason, status, by);
        audit.append("DEM", AuditLog.Action.UPDATE, "daily-log-amendment", logId, Map.of("field", fieldPath),
                Map.of("amendmentId", id, "reason", reason, "status", status));
        Map<String, Object> body = detail(logId);
        body.put("amendmentId", id);
        body.put("amendmentStatus", status);
        return body;
    }

    /**
     * DEM-03(b) 정정 요청 승인 · 반려: POST /daily-logs/{logId}/amendments/{amendmentId}:approve | :reject.
     * 권한 dem:supervision-log:approve(PM 투입 프로젝트 · EXEC 전체). 결정자 ≠ 요청자 · ≠ 일지 작성자(403 DEM-403-003),
     * 대기(PENDING · 결정 없음) 요청만(409 DEM-409-009). 잠금 후 동기화 초안(fieldPath '$')은 전체 덮어쓰기라 승인하지 않고
     * 반려만 한다(필드별 정정으로 다시 올림 — 422).
     */
    @Transactional
    public Map<String, Object> decideAmendment(UUID logId, UUID amendmentId, boolean approve, String note) {
        var log = dem.one("감리일지", """
                SELECT l.author_id, w.project_id FROM dem.daily_log l
                  JOIN dem.work_package w ON w.id = l.work_package_id WHERE l.id = ?""", logId);
        access.requireProject("dem:supervision-log:approve", (UUID) log.get("project_id"), null);
        var a = dem.one("정정 요청", """
                SELECT id, field_path, status, amended_by FROM dem.log_amendment
                 WHERE id = ? AND daily_log_id = ? FOR UPDATE""", amendmentId, logId);
        Integer decided = dem.jdbc().queryForObject(
                "SELECT count(*) FROM dem.log_amendment_decision WHERE amendment_id = ?", Integer.class, amendmentId);
        if (!"PENDING".equals(a.get("status")) || (decided != null && decided > 0)) {
            throw new DomainException(DemErrors.AMENDMENT_NOT_PENDING, "대기 중인 정정 요청이 아닙니다")
                    .with("reason", "DEM_AMENDMENT_NOT_PENDING");
        }
        UUID by = actor.actorIdOrSystem();
        if (by.equals(a.get("amended_by")) || by.equals(log.get("author_id"))) {
            throw new DomainException(DemErrors.SOD_VIOLATION, "정정 요청자 · 일지 작성자는 그 정정을 승인 · 반려할 수 없습니다")
                    .with("reason", "DEM_SOD_VIOLATION");
        }
        if (approve && !AMENDABLE.contains((String) a.get("field_path"))) {
            throw new DomainException(DemErrors.INVALID, "잠금 후 동기화 초안은 승인할 수 없습니다 — 필드별 정정으로 다시 올리세요")
                    .with("reason", "DEM_AMENDMENT_NOT_FIELD");
        }
        if (!approve && (note == null || note.isBlank())) {
            throw new DomainException(DemErrors.INVALID, "반려 사유 필수");
        }
        String decision = approve ? "APPROVED" : "REJECTED";
        dem.jdbc().update("""
                INSERT INTO dem.log_amendment_decision(amendment_id, decision, note, decided_by) VALUES (?, ?, ?, ?)""",
                amendmentId, decision, note, by);
        audit.append("DEM", approve ? AuditLog.Action.APPROVE : AuditLog.Action.UPDATE, "daily-log-amendment", logId,
                Map.of("amendmentId", amendmentId, "status", "PENDING"), Map.of("decision", decision));
        return detail(logId);
    }
}
