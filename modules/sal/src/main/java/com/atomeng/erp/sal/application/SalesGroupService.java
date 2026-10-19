package com.atomeng.erp.sal.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.api.EmployeeDirectoryApi;
import com.atomeng.erp.sal.adapter.out.persistence.SalJdbc;
import com.atomeng.erp.sal.domain.SalCodes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 영업 그룹 소속 관리 (BR-SAL-006 SALES_LEAD 할인 한도, V0036) — ADMIN 전용, 모든 변경은 감사 로그(before/after).
 * 비활성화는 active = false(행 보존). 할인 한도 해석({@link SalJdbc#groupsOf})은 active 행만 본다.
 */
@Service
public class SalesGroupService {

    public static final Set<String> GROUPS = Set.of("SALES_LEAD");

    public record Member(UUID employeeId, String groupCode, boolean active, String note, Instant createdAt,
                         UUID createdBy, Instant updatedAt, UUID updatedBy, long version) {}

    private final SalJdbc sal;
    private final EmployeeDirectoryApi employees;
    private final AuditLog audit;
    private final CurrentActor actor;

    public SalesGroupService(SalJdbc sal, EmployeeDirectoryApi employees, AuditLog audit, CurrentActor actor) {
        this.sal = sal;
        this.employees = employees;
        this.audit = audit;
        this.actor = actor;
    }

    public List<Member> list() {
        return sal.jdbc().query("SELECT * FROM sal.sales_group_member ORDER BY group_code, active DESC, created_at",
                (rs, i) -> member(rs));
    }

    public Member get(UUID employeeId, String groupCode) {
        return find(employeeId, groupCode, false);
    }

    @Transactional
    public Member add(UUID employeeId, String groupCode, String note) {
        checkGroup(groupCode);
        if (!employees.isActiveEmployee(employeeId)) {
            throw new DomainException(SalCodes.INVALID, "재직 중인 직원이 아닙니다: " + employeeId);
        }
        Integer exists = sal.jdbc().queryForObject("SELECT count(*) FROM sal.sales_group_member WHERE employee_id = ? "
                + "AND group_code = ?", Integer.class, employeeId, groupCode);
        if (exists != null && exists > 0) {
            throw duplicate();
        }
        try {
            sal.jdbc().update("INSERT INTO sal.sales_group_member(employee_id, group_code, note, created_by) "
                    + "VALUES (?, ?, ?, ?)", employeeId, groupCode, blankToNull(note), actor.actorIdOrSystem());
        } catch (DuplicateKeyException e) {
            throw duplicate();
        }
        Member m = get(employeeId, groupCode);
        audit.append("SAL", AuditLog.Action.CREATE, "SalesGroupMember", employeeId, null, view(m));
        return m;
    }

    /** 메모 · 활성 여부 변경(If-Match). active=false 가 "비활성화" */
    @Transactional
    public Member update(UUID employeeId, String groupCode, long expectedVersion, Boolean active, String note) {
        checkGroup(groupCode);
        Member cur = find(employeeId, groupCode, true);
        IfMatch.check(expectedVersion, cur.version());
        if (Boolean.TRUE.equals(active) && !cur.active() && !employees.isActiveEmployee(employeeId)) {
            throw new DomainException(SalCodes.INVALID, "재직 중인 직원만 다시 활성화할 수 있습니다");
        }
        sal.jdbc().update("""
                UPDATE sal.sales_group_member SET active = ?, note = ?, updated_at = now(), updated_by = ?,
                       version = version + 1 WHERE employee_id = ? AND group_code = ?""",
                active == null ? cur.active() : active, note == null ? cur.note() : blankToNull(note),
                actor.actorIdOrSystem(), employeeId, groupCode);
        Member after = get(employeeId, groupCode);
        audit.append("SAL", AuditLog.Action.UPDATE, "SalesGroupMember", employeeId, view(cur), view(after));
        return after;
    }

    private static DomainException duplicate() {
        return new DomainException(SalCodes.GROUP_MEMBER_EXISTS,
                "이미 등록된 소속입니다 — 비활성 상태라면 수정에서 다시 활성화하세요");
    }

    private Member find(UUID employeeId, String groupCode, boolean lock) {
        return sal.jdbc().query("SELECT * FROM sal.sales_group_member WHERE employee_id = ? AND group_code = ?"
                        + (lock ? " FOR UPDATE" : ""), (rs, i) -> member(rs), employeeId, groupCode).stream().findFirst()
                .orElseThrow(() -> new DomainException(SalCodes.NOT_FOUND,
                        "영업 그룹 소속 없음: " + employeeId + "/" + groupCode));
    }

    private static void checkGroup(String groupCode) {
        if (!GROUPS.contains(groupCode)) {
            throw new DomainException(SalCodes.INVALID, "groupCode: " + GROUPS);
        }
    }

    private static Member member(ResultSet rs) throws SQLException {
        var c = rs.getTimestamp("created_at");
        var u = rs.getTimestamp("updated_at");
        return new Member(rs.getObject("employee_id", UUID.class), rs.getString("group_code"), rs.getBoolean("active"),
                rs.getString("note"), c == null ? null : c.toInstant(), rs.getObject("created_by", UUID.class),
                u == null ? null : u.toInstant(), rs.getObject("updated_by", UUID.class), rs.getLong("version"));
    }

    private static Map<String, Object> view(Member m) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("employeeId", m.employeeId().toString());
        v.put("groupCode", m.groupCode());
        v.put("active", m.active());
        v.put("note", m.note());
        v.put("version", m.version());
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
