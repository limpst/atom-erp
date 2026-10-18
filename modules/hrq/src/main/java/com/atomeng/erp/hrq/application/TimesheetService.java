package com.atomeng.erp.hrq.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc;
import com.atomeng.erp.hrq.adapter.out.persistence.HrqJdbc.TimesheetRow;
import com.atomeng.erp.hrq.api.HrqEvents;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 타임시트 입력·승인·정정·월 마감 (03-IMPL Step 12, FR-HRQ-012·014·020, BR-HRQ-009·013·014).
 * <ul>
 *   <li>승인 → 일 단위 hrq.timesheet.confirmed (CST 원가 전기의 유일 원천)</li>
 *   <li>정정 → append-only 새 리비전, 직전 리비전 SUPERSEDED, hrq.timesheet.corrected(멱등키 timesheetKey:newRevision)</li>
 *   <li>마감 월 정정: 재오픈 없으면 409, ADMIN adjustment=true 면 다음 미마감 월 posting_period 로 조정 리비전</li>
 *   <li>월 마감 → APPROVED→LOCKED, hrq.manday.closed (대사·잠금 전용, 원가 재전기 금지)</li>
 * </ul>
 */
@Service
public class TimesheetService {

    private static final Set<String> WORK_TYPES = Set.of("FIELD", "OFFICE", "REPORT", "TRAVEL", "REVIEW", "LABELING");

    public record EntryInput(UUID projectId, LocalDate workDate, String workType, BigDecimal hours) {}

    public record Correction(BigDecimal hours, String workType, String reason, boolean adjustment) {}

    public record CloseResult(String yearMonth, int rows, BigDecimal totalMandays, int lockedEntries,
                              List<HrqEvents.MandayRow> mandays) {}

    private final HrqJdbc jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;

    public TimesheetService(HrqJdbc jdbc, OutboxPublisher outbox, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
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

    /** 주간 저장/제출. 확정 행은 건드리지 않고 DRAFT·SUBMITTED 만 교체. 마감 월이면 409(TC-HRQ-007) */
    @Transactional
    public List<TimesheetRow> saveWeek(UUID empId, String isoWeek, List<EntryInput> entries, boolean submit) {
        jdbc.lockEmployee(empId).orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "기술자 없음: " + empId));
        LocalDate mon = monday(isoWeek);
        LocalDate sun = mon.plusDays(6);
        for (EntryInput e : entries) {
            if (e.workDate().isBefore(mon) || e.workDate().isAfter(sun)) {
                throw new DomainException(HrqErrors.INVALID, "주차 범위 밖 일자: " + e.workDate());
            }
            if (!WORK_TYPES.contains(e.workType())) {
                throw new DomainException(HrqErrors.INVALID, "업무유형: " + WORK_TYPES);
            }
        }
        if (entries.stream().anyMatch(x -> closed(HrqErrors.ym(x.workDate())))) {
            throw new DomainException(HrqErrors.MONTH_CLOSED,
                    "마감된 월의 타임시트는 수정할 수 없습니다 — 정정 전표(POST /timesheets/{id}:correct, "
                            + "ADMIN adjustment)를 사용하세요");
        }
        UUID by = actor.actorIdOrSystem();
        jdbc.deleteOpenEntries(empId, mon, sun);
        for (EntryInput e : entries) {
            UUID id = UuidV7.next();
            jdbc.insertTimesheet(new TimesheetRow(id, empId, e.projectId(), e.workDate(), e.workType(), e.hours(),
                    submit ? "SUBMITTED" : "DRAFT", id, 1, null, null, HrqErrors.ym(e.workDate())), by, null);
        }
        return jdbc.entries(empId, mon, sun);
    }

    /** PM 일괄 승인 → APPROVED, (직원, 일자) 단위 hrq.timesheet.confirmed */
    @Transactional
    public int approve(List<UUID> entryIds) {
        UUID by = actor.actorIdOrSystem();
        Map<String, List<TimesheetRow>> byDay = new LinkedHashMap<>();
        for (UUID id : entryIds) {
            TimesheetRow t = jdbc.lockTimesheet(id)
                    .orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "타임시트 없음: " + id));
            if (!"SUBMITTED".equals(t.status())) {
                throw new DomainException(HrqErrors.INVALID_STATE, "제출(SUBMITTED)된 항목만 승인: " + t.status());
            }
            if (closed(t.postingPeriod())) {
                throw new DomainException(HrqErrors.MONTH_CLOSED, "마감된 월(" + t.postingPeriod() + ") 항목은 승인 불가");
            }
            jdbc.setTimesheetStatus(id, "APPROVED", by, true);
            byDay.computeIfAbsent(t.employeeId() + "|" + t.workDate(), k -> new ArrayList<>()).add(t);
        }
        byDay.values().forEach(rows -> {
            TimesheetRow first = rows.get(0);
            outbox.publish(new HrqEvents.TimesheetConfirmed(first.employeeId(), first.workDate(), first.postingPeriod(),
                    rows.stream().map(r -> new HrqEvents.TimesheetLine(r.id(), r.timesheetKey(), r.revisionNo(),
                            r.projectId(), null, r.workType(), r.hours())).toList()));
        });
        return entryIds.size();
    }

    /** 확정 타임시트 정정 (FR-HRQ-020, BR-HRQ-014, TC-HRQ-011·012). adjustment 는 ADMIN 만 */
    @Transactional
    public TimesheetRow correct(UUID entryId, Correction c, boolean isAdmin) {
        if (c.reason() == null || c.reason().isBlank()) {
            throw new DomainException(HrqErrors.INVALID, "정정 사유 필수");
        }
        TimesheetRow cur = jdbc.lockTimesheet(entryId)
                .orElseThrow(() -> new DomainException(HrqErrors.NOT_FOUND, "타임시트 없음: " + entryId));
        if (!"APPROVED".equals(cur.status()) && !"LOCKED".equals(cur.status())) {
            throw new DomainException(HrqErrors.INVALID_STATE,
                    "확정(APPROVED·LOCKED)된 현재 리비전만 정정 대상입니다(현재 " + cur.status() + ")");
        }
        String workType = c.workType() != null ? c.workType() : cur.workType();
        if (!WORK_TYPES.contains(workType)) {
            throw new DomainException(HrqErrors.INVALID, "업무유형: " + WORK_TYPES);
        }
        if (c.hours().compareTo(cur.hours()) == 0 && workType.equals(cur.workType())) {
            throw new DomainException(HrqErrors.INVALID, "변경 내용이 없습니다");
        }
        String posting = cur.postingPeriod();
        boolean adjustment = false;
        if (closed(posting)) {
            if (!c.adjustment()) {
                throw new DomainException(HrqErrors.MONTH_CLOSED, "마감 월(" + posting + ") 정정은 거부됩니다 — "
                        + "ADMIN 재오픈 또는 adjustment=true(다음 미마감 월 조정분)");
            }
            if (!isAdmin) {
                throw new DomainException("CORE-403-001", "마감 후 조정 정정 전표는 ADMIN 만(BR-HRQ-009)");
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
                c.hours(), "APPROVED", cur.timesheetKey(), cur.revisionNo() + 1, cur.id(), c.reason(), posting);
        jdbc.insertTimesheet(next, by, by);
        BigDecimal delta = c.hours().subtract(cur.hours());
        outbox.publish(new HrqEvents.TimesheetCorrected(cur.timesheetKey(), cur.id(), newId, cur.revisionNo(),
                next.revisionNo(), cur.employeeId(), cur.workDate(), posting, cur.projectId(), null, workType,
                cur.hours(), c.hours(), delta, c.reason(), by, adjustment,
                cur.timesheetKey() + ":" + next.revisionNo()));
        audit.append("HRQ", AuditLog.Action.UPDATE, "TimesheetCorrection", cur.timesheetKey(),
                Map.of("revision", cur.revisionNo(), "hours", cur.hours()),
                Map.of("revision", next.revisionNo(), "hours", c.hours(), "postingPeriod", posting,
                        "adjustment", adjustment, "reason", c.reason()));
        return next;
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

    /** 마감 월 재오픈 (ADMIN, 사유 필수·감사) */
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
