package com.atomeng.erp.dem.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.dem.adapter.out.persistence.DemJdbc;
import com.atomeng.erp.dem.api.DemEvents;
import com.atomeng.erp.hrq.api.HrqEvents;
import com.atomeng.erp.hrq.api.HrqQueryApi;
import com.atomeng.erp.hrq.api.HrqQueryApi.CredentialView;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 감리자 지정 · 해제 · 공백 (FR-DEM-002, BR-DEM-002·003, A8, 10-IMPL Step 3, TC-DEM-001·002·003).
 * <ul>
 *   <li>자격: rule SUPERVISOR_QUALIFICATION_CODES 중 하나를 hrq::api {@link HrqQueryApi#credentialsOf} 로 확인하되,
 *       {@code hrq.credential.expired} 로 받은 자격(dem.hrq_credential_block)은 HRQ 상태와 무관하게 무효로 본다
 *       (이벤트 수신 즉시 차단 — HRQ 만료 스캔과 DEM 사이 시차 제거). 퇴사({@code hrq.employee.terminated}) 직원도 차단</li>
 *   <li>동시 감리 한도(MAX_CONCURRENT_SUPERVISION.chief): 직원 단위 advisory lock 으로 직렬화 후 계수</li>
 *   <li>책임감리자 기간 중복은 DB EXCLUDE(ex_chief_period, 23P01) → 409 DEM-409-003</li>
 *   <li>hrq_check_snapshot: 지정 시점 검증 결과 전체 보존(감사 증빙)</li>
 * </ul>
 */
@Service
public class SupervisorService {

    public record Assign(UUID employeeId, String role, LocalDate validFrom, LocalDate validTo, String reason,
                         UUID basisAttachmentId) {}

    private final DemJdbc dem;
    private final RuleParams rules;
    private final HrqQueryApi hrq;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;

    public SupervisorService(DemJdbc dem, RuleParams rules, HrqQueryApi hrq, OutboxPublisher outbox, AuditLog audit,
                             CurrentActor actor) {
        this.dem = dem;
        this.rules = rules;
        this.hrq = hrq;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional
    public Map<String, Object> assign(UUID workPackageId, Assign a) {
        UUID by = actor.actorIdOrSystem();
        if (a.validTo() != null && a.validTo().isBefore(a.validFrom())) {
            throw new DomainException(DemErrors.INVALID, "validTo 가 validFrom 보다 빠릅니다");
        }
        var wp = dem.one("업무 패키지", "SELECT project_id FROM dem.work_package WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                workPackageId);
        // 같은 직원 동시 지정 요청 직렬화(10-IMPL Step 3 함정: activeCount 경쟁 조건)
        dem.jdbc().queryForObject("SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtext(?))) l", Integer.class,
                "dem-supervisor:" + a.employeeId());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("checkedOn", a.validFrom().toString());
        List<String> reasons = qualificationReasons(a.employeeId(), a.validFrom(), snapshot);
        if (!reasons.isEmpty()) {
            throw new DomainException(DemErrors.SUPERVISOR_NOT_QUALIFIED,
                    "감리자 자격 요건 미충족(BR-DEM-002): " + String.join(", ", reasons))
                    .with("reason", "DEM_SUPERVISOR_NOT_QUALIFIED").with("reasons", reasons);
        }
        if ("CHIEF".equals(a.role())) {
            int limit = rules.get("MAX_CONCURRENT_SUPERVISION", a.validFrom()).path("chief").asInt(Integer.MAX_VALUE);
            Integer active = dem.jdbc().queryForObject("""
                    SELECT count(DISTINCT work_package_id) FROM dem.supervisor_assignment
                     WHERE employee_id = ? AND role = 'CHIEF' AND status = 'ACTIVE' AND deleted_at IS NULL
                       AND work_package_id <> ?
                       AND daterange(valid_from, coalesce(valid_to, 'infinity'::date), '[]')
                           && daterange(?, coalesce(?, 'infinity'::date), '[]')""", Integer.class,
                    a.employeeId(), workPackageId, DemJdbc.d(a.validFrom()), DemJdbc.d(a.validTo()));
            snapshot.put("activeChiefCount", active);
            snapshot.put("maxConcurrentChief", limit);
            if (active != null && active >= limit) {
                throw new DomainException(DemErrors.SUPERVISOR_OVER_LIMIT,
                        "동시 감리 한도 초과(BR-DEM-003): 현재 %d / 한도 %d".formatted(active, limit))
                        .with("reason", "DEM_SUPERVISOR_OVER_LIMIT").with("activeCount", active).with("limit", limit);
            }
        }
        UUID id = UuidV7.next();
        try {
            dem.jdbc().update("""
                    INSERT INTO dem.supervisor_assignment(id, work_package_id, employee_id, role, valid_from, valid_to,
                                                          reason, basis_attachment_id, hrq_check_snapshot, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)""", id, workPackageId, a.employeeId(), a.role(),
                    DemJdbc.d(a.validFrom()), DemJdbc.d(a.validTo()), a.reason(), a.basisAttachmentId(),
                    dem.json(snapshot), by, by);
        } catch (DataIntegrityViolationException ex) {
            if (isExclusionViolation(ex)) {
                throw new DomainException(DemErrors.CHIEF_PERIOD_OVERLAP,
                        "같은 업무 패키지에 기간이 겹치는 책임감리자가 이미 있습니다(TC-DEM-003)")
                        .with("reason", "DEM_CHIEF_PERIOD_OVERLAP");
            }
            throw ex;
        }
        if ("CHIEF".equals(a.role())) {   // 대체 책임감리자 지정 → 공백 해소
            dem.jdbc().update("""
                    UPDATE dem.work_package SET supervisor_gap = false, updated_at = now(), version = version + 1
                     WHERE id = ? AND supervisor_gap""", workPackageId);
            dem.jdbc().update("UPDATE dem.supervisor_alert SET resolved_at = now() WHERE work_package_id = ? "
                    + "AND resolved_at IS NULL", workPackageId);
        }
        outbox.publish(new DemEvents.SupervisorAssigned(id, workPackageId, (UUID) wp.get("project_id"), a.employeeId(),
                a.role(), a.validFrom(), a.validTo()));
        audit.append("DEM", AuditLog.Action.CREATE, "supervisor-assignment", id, null,
                Map.of("workPackageId", workPackageId, "employeeId", a.employeeId(), "role", a.role()));
        return view(id);
    }

    private static boolean isExclusionViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.sql.SQLException se && "23P01".equals(se.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    /**
     * BR-DEM-002 판정 — 빈 목록이면 적격. 사유: EMPLOYEE_TERMINATED · CREDENTIAL_EXPIRED(이벤트로 받은 만료) ·
     * NO_VALID_QUALIFICATION. snapshot 에 판정 근거를 채운다.
     */
    List<String> qualificationReasons(UUID employeeId, LocalDate on, Map<String, Object> snapshot) {
        List<String> reasons = new ArrayList<>();
        List<LocalDate> resigned = dem.jdbc().queryForList(
                "SELECT resigned_on FROM dem.hrq_employee_block WHERE employee_id = ?", java.sql.Date.class, employeeId)
                .stream().map(java.sql.Date::toLocalDate).toList();
        if (!resigned.isEmpty() && !resigned.get(0).isAfter(on)) {
            reasons.add("EMPLOYEE_TERMINATED");
        }
        Set<String> required = new HashSet<>();
        JsonNode any = rules.get("SUPERVISOR_QUALIFICATION_CODES", on).path("any");
        any.forEach(n -> required.add(n.asText()));
        Set<UUID> blocked = new HashSet<>(dem.jdbc().queryForList(
                "SELECT credential_id FROM dem.hrq_credential_block WHERE employee_id = ?", UUID.class, employeeId));
        List<CredentialView> creds = hrq.credentialsOf(employeeId);
        List<Map<String, Object>> evaluated = new ArrayList<>();
        boolean qualified = false;
        boolean expiredQualifying = false;
        for (CredentialView c : creds) {
            if (!required.contains(c.typeCode())) {
                continue;
            }
            boolean blockedByEvent = blocked.contains(c.credentialId());
            boolean valid = c.validOn(on) && !blockedByEvent;
            if (valid) {
                qualified = true;
            } else if (blockedByEvent || "EXPIRED".equals(c.status())
                    || (c.expiresOn() != null && c.expiresOn().isBefore(on))) {
                expiredQualifying = true;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("credentialId", c.credentialId().toString());
            e.put("typeCode", c.typeCode());
            e.put("status", c.status());
            e.put("expiresOn", c.expiresOn() == null ? null : c.expiresOn().toString());
            e.put("expiredEventReceived", blockedByEvent);
            e.put("valid", valid);
            evaluated.add(e);
        }
        snapshot.put("requiredAnyOf", required.stream().sorted().toList());
        snapshot.put("credentials", evaluated);
        snapshot.put("qualified", qualified);
        if (!qualified) {
            reasons.add(expiredQualifying ? "CREDENTIAL_EXPIRED" : "NO_VALID_QUALIFICATION");
        }
        snapshot.put("reasons", reasons);
        return reasons;
    }

    @Transactional
    public Map<String, Object> release(UUID assignmentId, LocalDate releasedOn, String reason) {
        var a = dem.one("감리자 지정", """
                SELECT id, work_package_id, employee_id, valid_from, status FROM dem.supervisor_assignment
                 WHERE id = ? AND deleted_at IS NULL FOR UPDATE""", assignmentId);
        if (!"ACTIVE".equals(a.get("status"))) {
            throw new DomainException(DemErrors.INVALID_TRANSITION, "이미 해제된 지정입니다").with("reason", "DEM_INVALID_TRANSITION");
        }
        LocalDate from = DemJdbc.ld(a.get("valid_from"));
        LocalDate to = releasedOn.isBefore(from) ? from : releasedOn;
        doRelease((UUID) a.get("id"), (UUID) a.get("work_package_id"), (UUID) a.get("employee_id"), to,
                reason == null ? "MANUAL" : reason);
        return view(assignmentId);
    }

    private void doRelease(UUID id, UUID wpId, UUID employeeId, LocalDate on, String reason) {
        dem.jdbc().update("""
                UPDATE dem.supervisor_assignment SET status = 'RELEASED', valid_to = ?, release_reason = ?,
                       updated_at = now(), updated_by = ?, version = version + 1 WHERE id = ?""",
                DemJdbc.d(on), reason, actor.actorIdOrSystem(), id);
        outbox.publish(new DemEvents.SupervisorReleased(id, wpId, employeeId, on, reason));
        audit.append("DEM", AuditLog.Action.UPDATE, "supervisor-assignment", id, Map.of("status", "ACTIVE"),
                Map.of("status", "RELEASED", "validTo", on.toString(), "reason", reason));
    }

    public Map<String, Object> view(UUID assignmentId) {
        var r = dem.one("감리자 지정", """
                SELECT id, work_package_id, employee_id, role, valid_from, valid_to, status, release_reason,
                       hrq_check_snapshot::text AS snap, version FROM dem.supervisor_assignment WHERE id = ?""", assignmentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.get("id"));
        m.put("workPackageId", r.get("work_package_id"));
        m.put("employeeId", r.get("employee_id"));
        m.put("role", r.get("role"));
        m.put("validFrom", DemJdbc.ld(r.get("valid_from")));
        m.put("validTo", DemJdbc.ld(r.get("valid_to")));
        m.put("status", r.get("status"));
        m.put("releaseReason", r.get("release_reason"));
        m.put("hrqCheckSnapshot", dem.tree((String) r.get("snap")));
        m.put("version", r.get("version"));
        return m;
    }

    public List<Map<String, Object>> list(UUID workPackageId) {
        return dem.jdbc().queryForList("SELECT id FROM dem.supervisor_assignment WHERE work_package_id = ? "
                        + "AND deleted_at IS NULL ORDER BY valid_from, id", UUID.class, workPackageId)
                .stream().map(this::view).toList();
    }

    /** 기준일에 업무 패키지의 ACTIVE 감리자(역할 무관)인가 — 일지 작성 권한 */
    public boolean isActiveSupervisor(UUID workPackageId, UUID employeeId, LocalDate on) {
        Integer n = dem.jdbc().queryForObject("""
                SELECT count(*) FROM dem.supervisor_assignment
                 WHERE work_package_id = ? AND employee_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                   AND valid_from <= ? AND (valid_to IS NULL OR valid_to >= ?)""", Integer.class,
                workPackageId, employeeId, DemJdbc.d(on), DemJdbc.d(on));
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ HRQ 이벤트 (A8 감리자 공백)

    /**
     * hrq.credential.expired — 만료 자격을 읽기 모델에 기록(이후 지정 차단) 하고, 그 직원이 지금 맡은 감리 패키지 중
     * 다른 유효 자격이 없는 곳에 공백 경고 · 일지 작성 차단 플래그.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onCredentialExpired(HrqEvents.CredentialExpired e) {
        dem.jdbc().update("""
                INSERT INTO dem.hrq_credential_block(credential_id, employee_id, credential_type, expired_on)
                VALUES (?, ?, ?, ?) ON CONFLICT (credential_id) DO NOTHING""",
                e.credentialId(), e.employeeId(), e.credentialType(), DemJdbc.d(e.expiredOn()));
        List<String> reasons = qualificationReasons(e.employeeId(), e.expiredOn(), new LinkedHashMap<>());
        if (reasons.isEmpty()) {
            return 0;   // 다른 유효 자격이 있어 감리 계속 가능
        }
        int gaps = 0;
        for (var a : activeAssignments(e.employeeId(), e.expiredOn())) {
            gaps += markGap((UUID) a.get("work_package_id"), e.employeeId(), "CREDENTIAL_EXPIRED", e.expiredOn(),
                    Map.of("credentialId", e.credentialId().toString(), "credentialType", e.credentialType(),
                            "assignmentId", a.get("id").toString()));
        }
        return gaps;
    }

    /** hrq.employee.terminated — 퇴사 직원 차단 + 진행 중 지정 해제(valid_to = 퇴사일) + 공백 경고 */
    @Transactional(propagation = Propagation.MANDATORY)
    public int onEmployeeTerminated(HrqEvents.EmployeeTerminated e) {
        dem.jdbc().update("""
                INSERT INTO dem.hrq_employee_block(employee_id, resigned_on) VALUES (?, ?)
                ON CONFLICT (employee_id) DO UPDATE SET resigned_on = EXCLUDED.resigned_on, received_at = now()""",
                e.employeeId(), DemJdbc.d(e.resignedOn()));
        int released = 0;
        for (var a : dem.jdbc().queryForList("""
                SELECT id, work_package_id, role, valid_from FROM dem.supervisor_assignment
                 WHERE employee_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                   AND (valid_to IS NULL OR valid_to >= ?) ORDER BY id FOR UPDATE""", e.employeeId(),
                DemJdbc.d(e.resignedOn()))) {
            LocalDate from = DemJdbc.ld(a.get("valid_from"));
            LocalDate to = e.resignedOn().isBefore(from) ? from : e.resignedOn();
            doRelease((UUID) a.get("id"), (UUID) a.get("work_package_id"), e.employeeId(), to, "HRQ_TERMINATED");
            markGap((UUID) a.get("work_package_id"), e.employeeId(), "EMPLOYEE_TERMINATED", e.resignedOn(),
                    Map.of("assignmentId", a.get("id").toString(), "role", (String) a.get("role")));
            released++;
        }
        return released;
    }

    private List<Map<String, Object>> activeAssignments(UUID employeeId, LocalDate on) {
        return dem.jdbc().queryForList("""
                SELECT id, work_package_id FROM dem.supervisor_assignment
                 WHERE employee_id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
                   AND (valid_to IS NULL OR valid_to >= ?) ORDER BY id""", employeeId, DemJdbc.d(on));
    }

    private int markGap(UUID wpId, UUID employeeId, String kind, LocalDate on, Map<String, Object> detail) {
        var wp = dem.one("업무 패키지", "SELECT project_id FROM dem.work_package WHERE id = ? FOR UPDATE", wpId);
        dem.jdbc().update("""
                UPDATE dem.work_package SET supervisor_gap = true, updated_at = now(), version = version + 1 WHERE id = ?""",
                wpId);
        dem.jdbc().update("""
                INSERT INTO dem.supervisor_alert(id, work_package_id, employee_id, kind, detail) VALUES (?, ?, ?, ?, ?::jsonb)""",
                UuidV7.next(), wpId, employeeId, kind, dem.json(detail));
        outbox.publish(new DemEvents.SupervisorGapDetected(wpId, (UUID) wp.get("project_id"), employeeId, kind, on));
        return 1;
    }

    public List<Map<String, Object>> alerts(UUID workPackageId) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : dem.jdbc().queryForList("""
                SELECT id, employee_id, kind, detail::text AS detail, created_at, resolved_at FROM dem.supervisor_alert
                 WHERE work_package_id = ? ORDER BY created_at, id""", workPackageId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.get("id"));
            m.put("employeeId", r.get("employee_id"));
            m.put("kind", r.get("kind"));
            m.put("detail", dem.tree((String) r.get("detail")));
            m.put("createdAt", DemJdbc.inst(r.get("created_at")));
            m.put("resolvedAt", DemJdbc.inst(r.get("resolved_at")));
            out.add(m);
        }
        return out;
    }
}
