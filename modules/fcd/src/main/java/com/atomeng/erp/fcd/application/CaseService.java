package com.atomeng.erp.fcd.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fcd.api.FcdEvents;
import com.atomeng.erp.fcd.domain.casework.CaseMachine;
import com.atomeng.erp.fcd.domain.features.HolidayCalendar;
import com.atomeng.erp.fcd.domain.scoring.RiskCombiner;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 케이스(소명) 워크플로 (설계 A6.2 · BR-FCD-010/011/014/016/018, Python {@code casework.py} 이식).
 * <ul>
 *   <li>케이스는 판정 전 "확인이 필요한 사용"으로만 표현(F12) — 시스템은 판정하지 않는다</li>
 *   <li>쪼개기(R-SPLIT · R-SPLIT-MULTI) 적중 거래는 같은 묶음(split_group) · 사용자 단위 1케이스</li>
 *   <li>소명 기한 = 생성일 + 영업일 5일, 기한 초과 → OVERDUE (부서장 알림 대상)</li>
 *   <li>이해충돌: 판정자는 거래 사용자 본인 · 그 정산 결재자가 아니어야 하고 AUDITOR · EXEC 여야 한다</li>
 *   <li>부당(MISUSE · MINOR_VIOLATION) 판정 + 이미 전기된 정산 라인 → {@code fcd.expense.reversed}(환수, BR-FCD-016)</li>
 * </ul>
 */
@Service
public class CaseService {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Set<String> SPLIT_RULES = Set.of("R-SPLIT", "R-SPLIT-MULTI");

    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final HolidayCalendar holidays;
    private final ExpenseService expenses;
    private final Clock clock;

    public CaseService(JdbcTemplate jdbc, OutboxPublisher outbox, AuditLog audit, HolidayCalendar holidays,
                       ExpenseService expenses, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.holidays = holidays;
        this.expenses = expenses;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    /** 검토 대상 거래(점수 내림차순)로 케이스 생성 — 이미 케이스가 있는 거래는 제외 */
    @Transactional
    public List<UUID> openFor(List<UUID> txnIds, String reasonPrefix, boolean auditSample) {
        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        List<UUID> opened = new ArrayList<>();
        for (UUID tid : txnIds) {
            Integer exists = jdbc.queryForObject("SELECT count(*) FROM fcd.case_txn WHERE txn_id = ?", Integer.class, tid);
            if (exists != null && exists > 0) {
                continue;
            }
            List<Map<String, Object>> l = jdbc.queryForList("""
                    SELECT t.id, t.employee_id, t.risk_score, r.features->'tx'->>'split_group' AS split_group,
                           r.hits::text AS hits, r.reasons::text AS reasons
                      FROM fcd.txn t
                      JOIN LATERAL (SELECT * FROM fcd.risk_score rs WHERE rs.txn_id = t.id ORDER BY rs.scored_at DESC, rs.id DESC
                                     LIMIT 1) r ON true
                     WHERE t.id = ?""", tid);
            if (l.isEmpty()) {
                continue;
            }
            Map<String, Object> r = l.get(0);
            boolean split = SPLIT_RULES.stream().anyMatch(code -> String.valueOf(r.get("hits")).contains("\"" + code + "\""));
            String grp = split ? (String) r.get("split_group") : null;
            if (grp != null) {
                grouped.computeIfAbsent(grp + "|" + r.get("employee_id"), k -> new ArrayList<>()).add(r);
                continue;
            }
            opened.add(newCase(List.of(tid), (UUID) r.get("employee_id"), ((Number) r.get("risk_score")).intValue(),
                    reason(reasonPrefix, (String) r.get("reasons")), auditSample));
        }
        for (List<Map<String, Object>> items : grouped.values()) {
            items.sort((a, b) -> Integer.compare(((Number) b.get("risk_score")).intValue(),
                    ((Number) a.get("risk_score")).intValue()));
            Map<String, Object> top = items.get(0);
            opened.add(newCase(items.stream().map(x -> (UUID) x.get("id")).toList(), (UUID) top.get("employee_id"),
                    ((Number) top.get("risk_score")).intValue(), reason(reasonPrefix, (String) top.get("reasons")),
                    auditSample));
        }
        return opened;
    }

    private static String reason(String prefix, String reasonsJson) {
        String r = reasonsJson == null ? "" : reasonsJson.replaceAll("^\\[|\\]$", "").replace("\",\"", " / ")
                .replace("\"", "");
        String body = r.isBlank() ? "확인이 필요한 사용" : r;
        return prefix == null ? body : prefix + " — " + body;
    }

    private UUID newCase(List<UUID> txnIds, UUID subject, int score, String reason, boolean auditSample) {
        UUID id = UuidV7.next();
        LocalDate today = today();
        long n = jdbc.queryForObject("SELECT nextval('fcd.case_no_seq')", Long.class);
        String caseNo = "FCD-%d-%06d".formatted(today.getYear(), n);
        String grade = RiskCombiner.gradeCode(score);
        jdbc.update("""
                INSERT INTO fcd.fcd_case(id, case_no, status, grade, score, subject_employee_id, opened_reason, due_on,
                                         audit_sample, suspension_candidate)
                VALUES (?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?)""", id, caseNo, grade, score, subject, reason,
                Date.valueOf(holidays.addBusinessDays(today, 5)), auditSample, score >= 80);
        for (UUID t : txnIds) {
            jdbc.update("INSERT INTO fcd.case_txn(case_id, txn_id) VALUES (?, ?)", id, t);
        }
        event(id, null, "open", "OPEN", UUID.nameUUIDFromBytes("SYSTEM".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                reason);
        outbox.publish(new FcdEvents.CaseOpened(id, caseNo, txnIds, grade, score, subject, auditSample));
        return id;
    }

    private void event(UUID caseId, String from, String ev, String to, UUID actor, String note) {
        jdbc.update("INSERT INTO fcd.case_event(id, case_id, from_status, event, to_status, actor_id, note) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UuidV7.next(), caseId, from, ev, to, actor, note);
    }

    record CaseRow(UUID id, String caseNo, String status, UUID subject, boolean auditSample) {}

    private CaseRow lock(UUID id) {
        CaseRow c = jdbc.query("SELECT id, case_no, status, subject_employee_id, audit_sample FROM fcd.fcd_case WHERE id = ? "
                        + "FOR UPDATE", rs -> rs.next() ? new CaseRow(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getString(3), rs.getObject(4, UUID.class), rs.getBoolean(5)) : null, id);
        if (c == null) {
            throw new DomainException(FcdErrors.NOT_FOUND, "케이스 없음: " + id);
        }
        return c;
    }

    private String fire(CaseRow c, String ev, UUID actor, String note) {
        String next = CaseMachine.next(c.status(), ev).orElseThrow(() -> new DomainException(FcdErrors.STATE,
                "케이스 상태 " + c.status() + " 에서 '" + ev + "' 불가"));
        jdbc.update("UPDATE fcd.fcd_case SET status = ?, version = version + 1 WHERE id = ?", next, c.id());
        event(c.id(), c.status(), ev, next, actor, note);
        return next;
    }

    /** 소명 요청 — 푸시 본문 최소화(B8.3): 금액 · 가맹점 · "확인 필요" 만 */
    @Transactional
    public Map<String, Object> requestExplanation(UUID id, UUID actor) {
        CaseRow c = lock(id);
        fire(c, "request", actor, null);
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("to", c.subject());
        push.put("title", "법인카드 사용 확인 요청");
        push.put("body", "확인이 필요한 사용이 있습니다. 앱에서 소명해 주세요.");
        push.put("caseNo", c.caseNo());
        push.put("due", jdbc.queryForObject("SELECT due_on FROM fcd.fcd_case WHERE id = ?", Date.class, id).toString());
        return push;
    }

    @Transactional
    public Map<String, Object> submitExplanation(UUID id, UUID author, String purpose, String text,
                                                 String attendeesJson) {
        CaseRow c = lock(id);
        if (!author.equals(c.subject())) {
            throw new DomainException(FcdErrors.NOT_OWNER, "소명은 본인만 제출할 수 있습니다");
        }
        jdbc.update("""
                INSERT INTO fcd.explanation(id, case_id, author_id, purpose_code, text, attendees)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)""", UuidV7.next(), id, author, purpose, text, attendeesJson);
        String next = fire(c, "submit", author, null);
        return Map.of("caseNo", c.caseNo(), "status", next);
    }

    @Transactional
    public Map<String, Object> requestSupplement(UUID id, UUID actor, String note) {
        CaseRow c = lock(id);
        return Map.of("status", fire(c, "request_supplement", actor, note));
    }

    @Transactional
    public Map<String, Object> escalate(UUID id, UUID actor) {
        CaseRow c = lock(id);
        return Map.of("status", fire(c, "escalate", actor, null));
    }

    /** 이해충돌 검사 (BR-FCD-018, TC-FCD-025) */
    void checkConflict(CaseRow c, UUID reviewer, Set<String> roles) {
        if (reviewer.equals(c.subject())) {
            throw new DomainException(FcdErrors.CONFLICT_OF_INTEREST, "BR-FCD-018: 본인 거래 케이스는 판정할 수 없습니다");
        }
        if (c.subject() != null) {
            Integer approver = jdbc.queryForObject("""
                    SELECT count(*) FROM fcd.expense_report WHERE employee_id = ? AND approved_by = ?""", Integer.class,
                    c.subject(), reviewer);
            if (approver != null && approver > 0) {
                throw new DomainException(FcdErrors.CONFLICT_OF_INTEREST, "BR-FCD-018: 결재선 상위자는 판정할 수 없습니다");
            }
        }
        if (roles.stream().noneMatch(r -> r.equals("AUDITOR") || r.equals("EXEC"))) {
            throw new DomainException(FcdErrors.CONFLICT_OF_INTEREST, "판정은 AUDITOR · EXEC 권한 필요");
        }
    }

    @Transactional
    public Map<String, Object> resolve(UUID id, UUID reviewer, Set<String> roles, String label, String reason,
                                       BigDecimal recoveryAmount) {
        if (!CaseMachine.LABELS.contains(label)) {
            throw new DomainException(FcdErrors.INVALID, "label: " + CaseMachine.LABELS);
        }
        CaseRow c = lock(id);
        checkConflict(c, reviewer, roles);
        String next = fire(c, CaseMachine.resolveEvent(label), reviewer, reason);
        jdbc.update("""
                UPDATE fcd.fcd_case SET label = ?, resolution = ?, resolved_by = ?, resolved_at = now(), recovery_amount = ?
                 WHERE id = ?""", label, reason, reviewer, "MISUSE".equals(label) ? recoveryAmount : null, id);
        List<UUID> txns = jdbc.query("SELECT txn_id FROM fcd.case_txn WHERE case_id = ?", (rs, i) -> rs.getObject(1, UUID.class),
                id);
        for (UUID t : txns) {
            jdbc.update("""
                    INSERT INTO fcd.feedback(id, txn_id, case_id, label, reviewer_id, reason, audit_sample)
                    VALUES (?, ?, ?, ?, ?, ?, ?)""", UuidV7.next(), t, id, label, reviewer, reason, c.auditSample());
        }
        outbox.publish(new FcdEvents.CaseResolved(id, c.caseNo(), label, txns));
        audit.append("FCD", AuditLog.Action.UPDATE, "fcd_case", id, Map.of("status", c.status()),
                Map.of("status", next, "label", label));
        if (label.equals("MISUSE") || label.equals("MINOR_VIOLATION")) {
            for (UUID t : txns) {
                expenses.reverseForTxn(t, "부당 사용 판정(" + c.caseNo() + ") — 환수 처리");
            }
        }
        return Map.of("caseNo", c.caseNo(), "status", next, "label", label);
    }

    @Transactional
    public Map<String, Object> startRecovery(UUID id, UUID actor) {
        return Map.of("status", fire(lock(id), "start_recovery", actor, null));
    }

    @Transactional
    public Map<String, Object> close(UUID id, UUID actor) {
        return Map.of("status", fire(lock(id), "close", actor, null));
    }

    /** 기한 점검: 소명 요청 후 기한 지난 케이스 → OVERDUE(부서장 알림 대상) */
    @Transactional
    public Map<String, Object> sweep(UUID actor) {
        LocalDate today = today();
        List<UUID> ids = jdbc.query("SELECT id FROM fcd.fcd_case WHERE status = 'EXPLANATION_REQUESTED' AND due_on < ?",
                (rs, i) -> rs.getObject(1, UUID.class), Date.valueOf(today));
        List<String> overdue = new ArrayList<>();
        for (UUID id : ids) {
            CaseRow c = lock(id);
            fire(c, "overdue", actor, "기한 초과");
            overdue.add(c.caseNo());
        }
        return Map.of("overdue", overdue);
    }

    /** BR-FCD-011: 미소명 · 기한초과 케이스(무작위 감사 표본 제외) → 정산 상신 불가 */
    public List<String> blockingCases(UUID employeeId) {
        return jdbc.queryForList("""
                SELECT case_no FROM fcd.fcd_case WHERE subject_employee_id = ? AND NOT audit_sample
                   AND status IN ('EXPLANATION_REQUESTED','OVERDUE','SUPPLEMENT_REQUESTED') ORDER BY case_no""",
                String.class, employeeId);
    }

    public Map<String, Object> view(UUID id) {
        List<Map<String, Object>> l = jdbc.queryForList("""
                SELECT id, case_no, status, grade, score, subject_employee_id, opened_reason, opened_at, due_on, audit_sample,
                       score_dropped, suspension_candidate, label, resolution, resolved_by, resolved_at
                  FROM fcd.fcd_case WHERE id = ?""", id);
        if (l.isEmpty()) {
            throw new DomainException(FcdErrors.NOT_FOUND, "케이스 없음: " + id);
        }
        Map<String, Object> m = new LinkedHashMap<>(l.get(0));
        m.put("txnIds", jdbc.query("SELECT txn_id FROM fcd.case_txn WHERE case_id = ?", (rs, i) -> rs.getObject(1, UUID.class),
                id));
        m.put("history", jdbc.queryForList("SELECT from_status, event, to_status, actor_id, note, at FROM fcd.case_event "
                + "WHERE case_id = ? ORDER BY at, id", id));
        return m;
    }

    /** FCD-18: 대상 직원(SELF) 화면용 — 탐지 사유(임계값 · 기준선이 든 규칙 메시지)를 요약 문구로 바꾸고 생성 이벤트 메모를 지운다 */
    public static final String SUBJECT_REASON = "확인이 필요한 사용이 있습니다(세부 탐지 기준은 공개하지 않습니다)";

    public static Map<String, Object> subjectSummary(Map<String, Object> view) {
        Map<String, Object> m = new LinkedHashMap<>(view);
        m.put("opened_reason", SUBJECT_REASON);
        m.put("detail", false);
        Object h = view.get("history");
        if (h instanceof List<?> hist) {
            m.put("history", hist.stream().map(e -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> ev = new LinkedHashMap<>((Map<String, Object>) e);
                if ("open".equals(ev.get("event"))) {
                    ev.put("note", null);
                }
                return ev;
            }).toList());
        }
        return m;
    }

    public List<Map<String, Object>> list(UUID subjectOrNull) {
        return jdbc.queryForList("""
                SELECT id, case_no, status, grade, score, subject_employee_id, due_on, audit_sample, score_dropped
                  FROM fcd.fcd_case WHERE (?::uuid IS NULL OR subject_employee_id = ?) ORDER BY opened_at DESC""",
                subjectOrNull, subjectOrNull);
    }
}
