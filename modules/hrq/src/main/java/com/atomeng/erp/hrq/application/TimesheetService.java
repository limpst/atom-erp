package com.atomeng.erp.hrq.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.common.identity.AtomAuthentication;
import com.atomeng.common.identity.AtomPrincipal;
import com.atomeng.common.identity.Role;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc.TimesheetRow;
import com.atomeng.erp.hrq.api.HrqEvents;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임시트 입력·승인·정정·월 마감 (03-IMPL Step 12, FR-HRQ-012·014·020, BR-HRQ-009·013·014).
 * <ul>
 *   <li>승인 → 일 단위 hrq.timesheet.confirmed (CST 원가 전기의 유일 원천). 승인자는 프로젝트 RESP, 또는 EXEC · ADMIN 대리
 *       승인(사유 필수) — 본인 것은 누구도 승인 불가 (보안 검토 HRQ-01)</li>
 *   <li>정정 → append-only 새 리비전, 직전 리비전 SUPERSEDED. 프로젝트 RESP · ADMIN 의 정정은 바로 확정되어
 *       hrq.timesheet.corrected(멱등키 timesheetKey:newRevision), 본인(ENG 등) 정정은 SUBMITTED 리비전으로 두고
 *       재승인 때 비로소 corrected 이벤트(역분개 · 재전기) — HRQ-02</li>
 *   <li>입력은 근무일에 투입된 프로젝트만, 하루 합계 24h 이하 — HRQ-03</li>
 *   <li>마감 월 정정: 재오픈 없으면 409, ADMIN adjustment=true 면 다음 미마감 월 posting_period 로 조정 리비전</li>
 *   <li>월 마감 → APPROVED→LOCKED, hrq.manday.closed (대사·잠금 전용, 원가 재전기 금지). 재오픈은 SYSADMIN(HRQ-07)</li>
 * </ul>
 */
@Service
public class TimesheetService {

    private static final Set<String> WORK_TYPES = Set.of("FIELD", "OFFICE", "REPORT", "TRAVEL", "REVIEW", "LABELING");
    private static final BigDecimal DAY_MAX = BigDecimal.valueOf(24);
    /** 대리 승인 사유 최소 길이 (HRQ-01) */
    public static final int DELEGATION_REASON_MIN = 5;

    public record EntryInput(UUID projectId, LocalDate workDate, String workType, BigDecimal hours) {}

    public record Correction(BigDecimal hours, String workType, String reason, boolean adjustment) {}

    /** @param approved 승인 건수 @param delegated 그중 대리 승인(DELEGATED) 건수 */
    public record ApprovalResult(int approved, int delegated) {}

    public record CloseResult(String yearMonth, int rows, BigDecimal totalMandays, int lockedEntries,
                              List<HrqEvents.MandayRow> mandays) {}

    private final HrqJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;
    private final Clock clock;

    public TimesheetService(HrqJdbc jdbc, OutboxPublisher outbox, AuditLog audit, CurrentActor actor, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
        this.clock = clock;
    }

    static LocalDate monday(String isoWeek) {
        if (isoWeek == null || !isoWeek.matches("^\\d{4}-W\\d{2}$")) {
            throw new DomainException(HrqErrors.INVALID, "ISO 주차 형식 YYYY-Www: " + isoWeek);
        }
        int year = Integer.parseInt(isoWeek.substring(0, 4));
        int week = Integer.parseInt(isoWeek.substring(6));
        return LocalDate.of(year, 1, 4).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week).with(DayOfWeek.MONDAY);
    }

    private boolean closed(String ym) {
        return jdbc.monthStatus(ym).map("CLOSED"::equals).orElse(false);
    }

    /** 요청한 사람의 직원 ID(인증 없으면 null — 시스템 실행) */
    private static UUID currentEmployee() {
        return AtomAuthentication.currentPrincipal().map(AtomPrincipal::actorId).orElse(null);
    }

    private static boolean has(Role r) {
        return AtomAuthentication.currentPrincipal().map(p -> p.has(r)).orElse(false);
    }

    /**
     * 주간 저장/제출. 확정 행 · 재승인 대기 정정 리비전은 건드리지 않고 최초 리비전의 DRAFT·SUBMITTED 만 교체.
     * 마감 월이면 409(TC-HRQ-007). HRQ-03: 근무일에 투입된 프로젝트만(HRQ-422-003),
     * 하루 합계(프로젝트 합산 · 남는 행 포함) 24h 이하(HRQ-422-004).
     */
    @Transactional
    public List<TimesheetRow> saveWeek(UUID empId, String isoWeek, List<EntryInput> entries, boolean submit) {
        jdbc.lockEmployee(empId).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "기술자 없음: " + empId));
        LocalDate mon = monday(isoWeek);
        LocalDate sun = mon.plusDays(6);
        Map<LocalDate, BigDecimal> perDay = new TreeMap<>();
        for (EntryInput e : entries) {
            if (e.workDate().isBefore(mon) || e.workDate().isAfter(sun)) {
                throw new DomainException(HrqErrors.INVALID, "주차 범위 밖 일자: " + e.workDate());
            }
            if (!WORK_TYPES.contains(e.workType())) {
                throw new DomainException(HrqErrors.INVALID, "업무유형: " + WORK_TYPES);
            }
            perDay.merge(e.workDate(), e.hours(), BigDecimal::add);
        }
        if (entries.stream().anyMatch(x -> closed(HrqErrors.ym(x.workDate())))) {
            throw new DomainException(HrqErrors.MONTH_CLOSED,
                    "마감된 월의 타임시트는 수정할 수 없습니다 — 정정 전표(POST /timesheets/{id}:correct, "
                            + "ADMIN adjustment)를 사용하세요");
        }
        for (EntryInput e : entries) {
            if (!jdbc.assignedOn(empId, e.projectId(), e.workDate())) {
                throw new DomainException(HrqErrors.NOT_ASSIGNED, "근무일(" + e.workDate()
                        + ")에 투입되지 않은 프로젝트입니다: " + e.projectId())
                        .with("projectId", e.projectId().toString()).with("workDate", e.workDate().toString());
            }
        }
        perDay.forEach((day, hours) -> requireDayLimit(day, jdbc.retainedHoursOn(empId, day).add(hours)));
        UUID by = actor.actorIdOrSystem();
        jdbc.deleteOpenEntries(empId, mon, sun);
        for (EntryInput e : entries) {
            UUID id = UuidV7.next();
            jdbc.insertTimesheet(new TimesheetRow(id, empId, e.projectId(), e.workDate(), e.workType(), e.hours(),
                    submit ? "SUBMITTED" : "DRAFT", id, 1, null, null, HrqErrors.ym(e.workDate())), by, null);
        }
        return jdbc.entries(empId, mon, sun);
    }

    /** HRQ-03: 하루 합계 ≤ 24h (저장: 남는 행 + 새 입력, 정정: 살아 있는 행 − 현재 + 새 값) */
    private static void requireDayLimit(LocalDate day, BigDecimal total) {
        if (total.compareTo(DAY_MAX) > 0) {
            throw new DomainException(HrqErrors.DAY_LIMIT, day + " 하루 합계 " + total.stripTrailingZeros().toPlainString()
                    + "h — 24시간을 넘을 수 없습니다(프로젝트 합산)").with("workDate", day.toString());
        }
    }

    /**
     * 승인 → APPROVED (HRQ-01). 최초 리비전은 (직원, 일자) 단위 hrq.timesheet.confirmed, 재승인 대기 정정 리비전(HRQ-02)은
     * 승인 때 비로소 hrq.timesheet.corrected(역분개 + 재전기).
     * <ul>
     *   <li>본인 타임시트는 어떤 역할로도 승인 불가(HRQ-403-002)</li>
     *   <li>오늘(Asia/Seoul) 그 프로젝트 RESP 로 투입 중이면 RESP 승인</li>
     *   <li>아니면 EXEC · ADMIN 만 대리 승인(DELEGATED) — 사유 5자 이상 필수(HRQ-422-002), 행(delegation_reason) · 감사에 기록</li>
     *   <li>그 밖(비 RESP PM) → HRQ-403-001</li>
     * </ul>
     */
    @Transactional
    public ApprovalResult approve(List<UUID> entryIds, String delegationReason) {
        UUID by = actor.actorIdOrSystem();
        UUID me = currentEmployee();
        boolean canDelegate = has(Role.EXEC) || has(Role.ADMIN);
        String reason = delegationReason == null ? null : delegationReason.strip();
        LocalDate today = HrqErrors.today(clock);
        Map<String, List<TimesheetRow>> byDay = new LinkedHashMap<>();
        List<TimesheetRow> revisions = new ArrayList<>();
        int delegated = 0;
        for (UUID id : entryIds) {
            TimesheetRow t = jdbc.lockTimesheet(id)
                    .orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "타임시트 없음: " + id));
            if (!"SUBMITTED".equals(t.status())) {
                throw new DomainException(HrqErrors.INVALID_STATE, "제출(SUBMITTED)된 항목만 승인: " + t.status());
            }
            if (closed(t.postingPeriod())) {
                throw new DomainException(HrqErrors.MONTH_CLOSED, "마감된 월(" + t.postingPeriod() + ") 항목은 승인 불가");
            }
            if (me != null && me.equals(t.employeeId())) {
                throw new DomainException(HrqErrors.SELF_APPROVAL, "본인 타임시트는 승인할 수 없습니다(역할과 무관)")
                        .with("entryId", id.toString());
            }
            String mode;
            if (me != null && jdbc.responsibleOn(me, t.projectId(), today)) {
                mode = "RESP";
            } else if (canDelegate) {
                if (reason == null || reason.length() < DELEGATION_REASON_MIN) {
                    throw new DomainException(HrqErrors.DELEGATION_REASON_REQUIRED,
                            "프로젝트 책임기술자가 아닌 대리 승인은 사유(" + DELEGATION_REASON_MIN + "자 이상)가 필요합니다")
                            .with("entryId", id.toString());
                }
                mode = "DELEGATED";
            } else {
                throw new DomainException(HrqErrors.NOT_PROJECT_RESPONSIBLE,
                        "이 프로젝트의 책임기술자(RESP)만 승인할 수 있습니다").with("entryId", id.toString())
                        .with("projectId", t.projectId().toString());
            }
            boolean isDelegated = "DELEGATED".equals(mode);
            jdbc.approveTimesheet(id, by, mode, isDelegated ? reason : null);
            if (isDelegated) {
                delegated++;
                audit.append("HRQ", AuditLog.Action.APPROVE, "TimesheetDelegatedApproval", t.timesheetKey(),
                        Map.of("status", t.status()), Map.of("entryId", id.toString(), "revision", t.revisionNo(),
                                "mode", mode, "reason", reason, "projectId", t.projectId().toString()));
            }
            if (t.supersedesId() != null) {
                revisions.add(t);
            } else {
                byDay.computeIfAbsent(t.employeeId() + "|" + t.workDate(), k -> new ArrayList<>()).add(t);
            }
        }
        byDay.values().forEach(rows -> {
            TimesheetRow first = rows.get(0);
            outbox.publish(new HrqEvents.TimesheetConfirmed(first.employeeId(), first.workDate(), first.postingPeriod(),
                    rows.stream().map(r -> new HrqEvents.TimesheetLine(r.id(), r.timesheetKey(), r.revisionNo(),
                            r.projectId(), null, r.workType(), r.hours())).toList()));
        });
        for (TimesheetRow next : revisions) {
            TimesheetRow prev = jdbc.lockTimesheet(next.supersedesId()).orElseThrow();
            publishCorrected(prev, next, jdbc.timesheetCreatedBy(next.id()), false);
        }
        return new ApprovalResult(entryIds.size(), delegated);
    }

    private void publishCorrected(TimesheetRow cur, TimesheetRow next, UUID correctedBy, boolean adjustment) {
        BigDecimal delta = next.hours().subtract(cur.hours());
        outbox.publish(new HrqEvents.TimesheetCorrected(cur.timesheetKey(), cur.id(), next.id(), cur.revisionNo(),
                next.revisionNo(), cur.employeeId(), cur.workDate(), next.postingPeriod(), cur.projectId(), null,
                next.workType(), cur.hours(), next.hours(), delta, next.correctionReason(), correctedBy, adjustment,
                cur.timesheetKey() + ":" + next.revisionNo()));
    }

    /**
     * 확정 타임시트 정정 (FR-HRQ-020, BR-HRQ-014, TC-HRQ-011·012). adjustment 는 ADMIN 만.
     * HRQ-02: 정정자가 타임시트 본인이면(ENG · 역할 무관) 새 리비전은 SUBMITTED — 재승인 전에는 CST 역분개 · 재전기 없음.
     * 본인이 아니면 그 프로젝트 RESP(오늘 투입 중) 또는 ADMIN 만, 바로 APPROVED + corrected 이벤트. 비 RESP PM → HRQ-403-001.
     */
    @Transactional
    public TimesheetRow correct(UUID entryId, Correction c) {
        if (c.reason() == null || c.reason().isBlank()) {
            throw new DomainException(HrqErrors.INVALID, "정정 사유 필수");
        }
        TimesheetRow cur = jdbc.lockTimesheet(entryId)
                .orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "타임시트 없음: " + entryId));
        if (!"APPROVED".equals(cur.status()) && !"LOCKED".equals(cur.status())) {
            throw new DomainException(HrqErrors.INVALID_STATE,
                    "확정(APPROVED·LOCKED)된 현재 리비전만 정정 대상입니다(현재 " + cur.status() + ")");
        }
        UUID me = currentEmployee();
        boolean owner = me != null && me.equals(cur.employeeId());
        boolean isAdmin = has(Role.ADMIN);
        boolean immediate;
        if (owner) {
            immediate = false;
        } else if (isAdmin || (me != null && jdbc.responsibleOn(me, cur.projectId(), HrqErrors.today(clock)))) {
            immediate = true;
        } else {
            throw new DomainException(HrqErrors.NOT_PROJECT_RESPONSIBLE,
                    "남의 타임시트 정정은 그 프로젝트의 책임기술자(RESP) 또는 경영지원(ADMIN)만 할 수 있습니다")
                    .with("projectId", cur.projectId().toString());
        }
        String workType = c.workType() != null ? c.workType() : cur.workType();
        if (!WORK_TYPES.contains(workType)) {
            throw new DomainException(HrqErrors.INVALID, "업무유형: " + WORK_TYPES);
        }
        if (c.hours().compareTo(cur.hours()) == 0 && workType.equals(cur.workType())) {
            throw new DomainException(HrqErrors.INVALID, "변경 내용이 없습니다");
        }
        requireDayLimit(cur.workDate(),
                jdbc.activeHoursOn(cur.employeeId(), cur.workDate()).subtract(cur.hours()).add(c.hours()));
        String posting = cur.postingPeriod();
        boolean adjustment = false;
        if (closed(posting)) {
            if (!c.adjustment()) {
                throw new DomainException(HrqErrors.MONTH_CLOSED, "마감 월(" + posting + ") 정정은 거부됩니다 — "
                        + "SYSADMIN 재오픈 또는 adjustment=true(다음 미마감 월 조정분)");
            }
            if (!isAdmin) {
                throw new DomainException("CORE-403-001", "마감 후 조정 정정 전표는 ADMIN 만(BR-HRQ-009)");
            }
            if (owner) {
                throw new DomainException(HrqErrors.SELF_APPROVAL, "본인 타임시트의 마감 후 조정 정정 전표는 만들 수 없습니다");
            }
            YearMonth m = HrqErrors.parseYm(posting).plusMonths(1);
            while (closed(HrqErrors.ym(m))) {
                m = m.plusMonths(1);
            }
            posting = HrqErrors.ym(m);
            adjustment = true;
        }
        UUID by = actor.actorIdOrSystem();
        jdbc.setTimesheetStatus(cur.id(), "SUPERSEDED", by, false);   // 값 불변, 상태만 (트리거가 보장)
        UUID newId = UuidV7.next();
        TimesheetRow next = new TimesheetRow(newId, cur.employeeId(), cur.projectId(), cur.workDate(), workType,
                c.hours(), immediate ? "APPROVED" : "SUBMITTED", cur.timesheetKey(), cur.revisionNo() + 1, cur.id(),
                c.reason(), posting);
        jdbc.insertTimesheet(next, by, immediate ? by : null);
        if (immediate) {
            publishCorrected(cur, next, by, adjustment);
        }
        audit.append("HRQ", AuditLog.Action.UPDATE, "TimesheetCorrection", cur.timesheetKey(),
                Map.of("revision", cur.revisionNo(), "hours", cur.hours()),
                Map.of("revision", next.revisionNo(), "hours", c.hours(), "postingPeriod", posting,
                        "adjustment", adjustment, "reason", c.reason(), "status", next.status()));
        return jdbc.lockTimesheet(newId).orElseThrow();
    }

    /** 월 마감 (ADMIN): 미승인 0건 → 인·일 집계 → LOCKED → hrq.manday.closed */
    @Transactional
    public CloseResult closeMonth(String ym) {
        YearMonth m = HrqErrors.parseYm(ym);
        String status = jdbc.lockMonth(ym).orElse("OPEN");
        if ("CLOSED".equals(status)) {
            throw new DomainException(HrqErrors.MONTH_ALREADY_CLOSED, ym + " 은 이미 마감되었습니다");
        }
        int open = jdbc.countOpenInMonth(m.atDay(1), m.plusMonths(1).atDay(1));
        if (open > 0) {
            throw new DomainException(HrqErrors.UNAPPROVED_EXIST, "미승인(DRAFT·SUBMITTED) 타임시트 " + open + "건");
        }
        List<HrqEvents.MandayRow> rows = jdbc.aggregateMandays(ym).stream().map(r -> new HrqEvents.MandayRow(
                (UUID) r.get("project_id"), (UUID) r.get("employee_id"), (String) r.get("grade"),
                (String) r.get("work_type"), (BigDecimal) r.get("mandays"))).toList();
        BigDecimal total = rows.stream().map(HrqEvents.MandayRow::mandays).reduce(BigDecimal.ZERO, BigDecimal::add);
        UUID by = actor.actorIdOrSystem();
        int locked = jdbc.lockApproved(ym, by);
        UUID eventId = outbox.publish(new HrqEvents.MandayClosed(ym, total, rows));
        jdbc.closeMonth(ym, total, eventId, by);
        audit.append("HRQ", AuditLog.Action.APPROVE, "MonthClose", null, Map.of("status", status),
                Map.of("yearMonth", ym, "totalMandays", total, "rows", rows.size()));
        return new CloseResult(ym, rows.size(), total, locked, rows);
    }

    /** 마감 월 재오픈 (SYSADMIN — hrq:month-close:reopen, HRQ-07. 사유 필수·감사) */
    @Transactional
    public void reopenMonth(String ym, String reason) {
        HrqErrors.parseYm(ym);
        if (reason == null || reason.isBlank()) {
            throw new DomainException(HrqErrors.INVALID, "재오픈 사유 필수");
        }
        String status = jdbc.lockMonth(ym).orElse("OPEN");
        if (!"CLOSED".equals(status)) {
            throw new DomainException(HrqErrors.INVALID_STATE, ym + " 은 마감 상태가 아닙니다(" + status + ")");
        }
        jdbc.reopenMonth(ym, reason, actor.actorIdOrSystem());
        audit.append("HRQ", AuditLog.Action.UPDATE, "MonthReopen", null, Map.of("status", "CLOSED"),
                Map.of("yearMonth", ym, "status", "REOPENED", "reason", reason));
    }
}
