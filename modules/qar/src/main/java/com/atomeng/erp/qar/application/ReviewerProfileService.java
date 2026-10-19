package com.atomeng.erp.qar.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.web.http.IfMatch;
import com.atomeng.erp.core.api.EmployeeDirectoryApi;
import com.atomeng.erp.qar.adapter.out.persistence.QarJdbc;
import com.atomeng.erp.qar.domain.QarCodes;
import com.fasterxml.jackson.core.type.TypeReference;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * QA 검토자 프로필 관리 (07 B7.2 배정 후보 · SEC-QAR-02 수동 배정 자격, V0037) — ADMIN 전용, 감사 로그(before/after).
 * 배정 판정에 쓰는 값: active · stepCode(QA_REVIEW) · maxOpen(동시 미완료 상한) · expertise(보고서 유형별 과거 검토 건수).
 * qualifications 는 표시 · 감사용(자격 · 전문분야 메모). 비활성화는 active = false — 자동 · 수동 배정 후보에서 빠진다.
 */
@Service
public class ReviewerProfileService {

    public static final Set<String> STEPS = Set.of("QA_REVIEW");
    private static final TypeReference<Map<String, Integer>> EXPERTISE = new TypeReference<>() {};

    public record Profile(UUID employeeId, String stepCode, boolean active, int maxOpen, Map<String, Integer> expertise,
                          List<String> qualifications, String note, long openAssignments, Instant createdAt,
                          Instant updatedAt, UUID updatedBy, long version) {}

    public record Upsert(String stepCode, Integer maxOpen, Map<String, Integer> expertise, List<String> qualifications,
                         String note, Boolean active) {}

    private final QarJdbc qar;
    private final EmployeeDirectoryApi employees;
    private final AuditLog audit;
    private final CurrentActor actor;

    public ReviewerProfileService(QarJdbc qar, EmployeeDirectoryApi employees, AuditLog audit, CurrentActor actor) {
        this.qar = qar;
        this.employees = employees;
        this.audit = audit;
        this.actor = actor;
    }

    private static final String SELECT = """
            SELECT p.*, (SELECT count(*) FROM qar.review_assignment a WHERE a.assignee_id = p.employee_id
                                                            AND a.completed_at IS NULL) AS open_count
              FROM qar.reviewer_profile p""";

    public List<Profile> list() {
        return qar.jdbc().query(SELECT + " ORDER BY p.active DESC, p.created_at", (rs, i) -> profile(rs));
    }

    public Profile get(UUID employeeId) {
        return qar.jdbc().query(SELECT + " WHERE p.employee_id = ?", (rs, i) -> profile(rs), employeeId).stream()
                .findFirst().orElseThrow(() -> QarJdbc.notFound("reviewer_profile", employeeId));
    }

    @Transactional
    public Profile create(UUID employeeId, Upsert u) {
        if (!employees.isActiveEmployee(employeeId)) {
            throw new DomainException(QarCodes.INVALID, "재직 중인 직원이 아닙니다: " + employeeId);
        }
        Integer exists = qar.jdbc().queryForObject("SELECT count(*) FROM qar.reviewer_profile WHERE employee_id = ?",
                Integer.class, employeeId);
        if (exists != null && exists > 0) {
            throw new DomainException(QarCodes.PROFILE_EXISTS,
                    "이미 등록된 검토자입니다 — 비활성 상태라면 수정에서 다시 활성화하세요");
        }
        String step = u.stepCode() == null ? "QA_REVIEW" : u.stepCode();
        int maxOpen = u.maxOpen() == null ? 5 : u.maxOpen();
        validate(step, maxOpen, u.expertise());
        UUID by = actor.actorIdOrSystem();
        qar.jdbc().update(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO qar.reviewer_profile(employee_id, step_code, active, max_open, expertise, qualifications,
                                                     note, created_by)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?)""");
            ps.setObject(1, employeeId);
            ps.setString(2, step);
            ps.setBoolean(3, u.active() == null || u.active());
            ps.setInt(4, maxOpen);
            ps.setString(5, QarJdbc.json(u.expertise() == null ? Map.of() : u.expertise()));
            ps.setArray(6, con.createArrayOf("text", clean(u.qualifications()).toArray()));
            ps.setString(7, blankToNull(u.note()));
            ps.setObject(8, by);
            return ps;
        });
        Profile p = get(employeeId);
        audit.append("QAR", AuditLog.Action.CREATE, "ReviewerProfile", employeeId, null, view(p));
        return p;
    }

    /** 부분 수정(If-Match) — null 인 필드는 그대로. active=false 가 "비활성화" */
    @Transactional
    public Profile update(UUID employeeId, long expectedVersion, Upsert u) {
        List<Integer> locked = qar.jdbc().queryForList("SELECT 1 FROM qar.reviewer_profile WHERE employee_id = ? "
                + "FOR UPDATE", Integer.class, employeeId);
        if (locked.isEmpty()) {
            throw QarJdbc.notFound("reviewer_profile", employeeId);
        }
        Profile cur = get(employeeId);
        IfMatch.check(expectedVersion, cur.version());
        String step = u.stepCode() == null ? cur.stepCode() : u.stepCode();
        int maxOpen = u.maxOpen() == null ? cur.maxOpen() : u.maxOpen();
        Map<String, Integer> expertise = u.expertise() == null ? cur.expertise() : u.expertise();
        List<String> quals = u.qualifications() == null ? cur.qualifications() : clean(u.qualifications());
        boolean active = u.active() == null ? cur.active() : u.active();
        validate(step, maxOpen, expertise);
        if (active && !cur.active() && !employees.isActiveEmployee(employeeId)) {
            throw new DomainException(QarCodes.INVALID, "재직 중인 직원만 다시 활성화할 수 있습니다");
        }
        String note = u.note() == null ? cur.note() : blankToNull(u.note());
        UUID by = actor.actorIdOrSystem();
        qar.jdbc().update(con -> {
            var ps = con.prepareStatement("""
                    UPDATE qar.reviewer_profile SET step_code = ?, active = ?, max_open = ?, expertise = ?::jsonb,
                           qualifications = ?, note = ?, updated_at = now(), updated_by = ?, version = version + 1
                     WHERE employee_id = ?""");
            ps.setString(1, step);
            ps.setBoolean(2, active);
            ps.setInt(3, maxOpen);
            ps.setString(4, QarJdbc.json(expertise));
            ps.setArray(5, con.createArrayOf("text", quals.toArray()));
            ps.setString(6, note);
            ps.setObject(7, by);
            ps.setObject(8, employeeId);
            return ps;
        });
        Profile after = get(employeeId);
        audit.append("QAR", AuditLog.Action.UPDATE, "ReviewerProfile", employeeId, view(cur), view(after));
        return after;
    }

    private static void validate(String step, int maxOpen, Map<String, Integer> expertise) {
        if (!STEPS.contains(step)) {
            throw new DomainException(QarCodes.INVALID, "stepCode: " + STEPS + " (최종 승인은 PM 역할 — 프로필 대상 아님)");
        }
        if (maxOpen < 1 || maxOpen > 50) {
            throw new DomainException(QarCodes.INVALID, "maxOpen 은 1~50");
        }
        if (expertise != null) {
            for (var e : expertise.entrySet()) {
                if (e.getKey() == null || !e.getKey().matches("^RT_[A-Z_]{2,20}$") || e.getValue() == null
                        || e.getValue() < 0) {
                    throw new DomainException(QarCodes.INVALID,
                            "expertise 는 {보고서유형(RT_…): 0 이상 건수} 형식이어야 합니다: " + e.getKey());
                }
            }
        }
    }

    private static List<String> clean(List<String> q) {
        if (q == null) {
            return List.of();
        }
        List<String> out = q.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).distinct().toList();
        if (out.size() > 20 || out.stream().anyMatch(x -> x.length() > 100)) {
            throw new DomainException(QarCodes.INVALID, "자격은 20개 이하, 각 100자 이하");
        }
        return out;
    }

    private static Profile profile(ResultSet rs) throws SQLException {
        Map<String, Integer> expertise;
        try {
            String raw = rs.getString("expertise");
            expertise = raw == null ? Map.of() : QarJdbc.MAPPER.readValue(raw, EXPERTISE);
        } catch (Exception e) {
            expertise = Map.of();
        }
        Array arr = rs.getArray("qualifications");
        List<String> quals = arr == null ? List.of() : Arrays.asList((String[]) arr.getArray());
        var c = rs.getTimestamp("created_at");
        var u = rs.getTimestamp("updated_at");
        return new Profile(rs.getObject("employee_id", UUID.class), rs.getString("step_code"), rs.getBoolean("active"),
                rs.getInt("max_open"), expertise, quals, rs.getString("note"), rs.getLong("open_count"),
                c == null ? null : c.toInstant(), u == null ? null : u.toInstant(),
                rs.getObject("updated_by", UUID.class), rs.getLong("version"));
    }

    private static Map<String, Object> view(Profile p) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("employeeId", p.employeeId().toString());
        v.put("stepCode", p.stepCode());
        v.put("active", p.active());
        v.put("maxOpen", p.maxOpen());
        v.put("expertise", p.expertise());
        v.put("qualifications", p.qualifications());
        v.put("note", p.note());
        v.put("version", p.version());
        return v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
