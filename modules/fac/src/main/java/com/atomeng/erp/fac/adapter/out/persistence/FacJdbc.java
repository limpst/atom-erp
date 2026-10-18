package com.atomeng.erp.fac.adapter.out.persistence;

import com.atomeng.common.core.UuidV7;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.CycleRule;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.RuleSource;
import com.atomeng.erp.fac.domain.model.FacTypes.DueStatus;
import com.atomeng.erp.fac.domain.model.FacTypes.Grade;
import com.atomeng.erp.fac.domain.model.FacTypes.InspectionType;
import com.atomeng.erp.fac.domain.model.FacTypes.SitbeopClass;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * FAC JDBC 저장소: 등급 이력(append-only), 점검주기 규칙(RuleSource), 도래, 분류, 도래 알림 멱등키.
 * (불변·시계열 테이블은 JPA 대신 JDBC — 엔티티 dirty checking 으로 UPDATE 가 나갈 여지를 없앤다)
 */
@Repository
public class FacJdbc implements RuleSource {

    public record GradeRow(UUID id, UUID buildingId, String grade, String inspectionType, LocalDate performedOn,
                           UUID reportId, String performer, String source, UUID correctionOf, UUID recordedBy) {}

    public record DueRow(UUID id, UUID buildingId, String inspectionType, LocalDate baseDate, LocalDate dueDate,
                         UUID ruleId, String status, String calcTrace) {}

    public record Classification(SitbeopClass sitbeopClass, boolean bmaTarget) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper om;

    public FacJdbc(JdbcTemplate jdbc, ObjectMapper om) {
        this.jdbc = jdbc;
        this.om = om;
    }

    // ------------------------------------------------------------ 등급 이력
    public UUID insertGrade(UUID buildingId, Grade grade, InspectionType type, LocalDate performedOn, UUID reportId,
                            String source, UUID recordedBy) {
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO fac.grade_history(id, building_id, grade, inspection_type, performed_on, report_id,
                                              performer, source, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, 'ATOM', ?, ?)""",
                id, buildingId, grade.name(), type.name(), Date.valueOf(performedOn), reportId, source, recordedBy);
        return id;
    }

    public List<GradeRow> grades(UUID buildingId) {
        return jdbc.query("""
                SELECT * FROM fac.grade_history WHERE building_id = ?
                 ORDER BY performed_on DESC, recorded_at DESC, id DESC""", (rs, i) -> new GradeRow(
                rs.getObject("id", UUID.class), rs.getObject("building_id", UUID.class), rs.getString("grade"),
                rs.getString("inspection_type"), rs.getDate("performed_on").toLocalDate(),
                rs.getObject("report_id", UUID.class), rs.getString("performer"), rs.getString("source"),
                rs.getObject("correction_of", UUID.class), rs.getObject("recorded_by", UUID.class)), buildingId);
    }

    /** 정정된(다른 행의 correction_of 로 참조된) 원본은 제외한 최신 등급 */
    public Optional<Grade> latestGrade(UUID buildingId) {
        return jdbc.queryForList("""
                SELECT g.grade FROM fac.grade_history g
                 WHERE g.building_id = ?
                   AND NOT EXISTS (SELECT 1 FROM fac.grade_history c WHERE c.correction_of = g.id)
                 ORDER BY g.performed_on DESC, g.recorded_at DESC, g.id DESC LIMIT 1""", String.class, buildingId)
                .stream().findFirst().map(Grade::valueOf);
    }

    public Map<InspectionType, LocalDate> lastPerformed(UUID buildingId) {
        Map<InspectionType, LocalDate> m = new EnumMap<>(InspectionType.class);
        jdbc.query("""
                SELECT g.inspection_type, max(g.performed_on) AS last FROM fac.grade_history g
                 WHERE g.building_id = ?
                   AND NOT EXISTS (SELECT 1 FROM fac.grade_history c WHERE c.correction_of = g.id)
                 GROUP BY g.inspection_type""", rs -> {
            m.put(InspectionType.valueOf(rs.getString(1)), rs.getDate(2).toLocalDate());
        }, buildingId);
        return m;
    }

    // ------------------------------------------------------------ 분류
    public Optional<Classification> currentClassification(UUID buildingId) {
        return jdbc.query("""
                SELECT sitbeop_class, bma_target FROM fac.legal_classification
                 WHERE building_id = ? AND is_current""",
                (rs, i) -> new Classification(rs.getString(1) == null ? SitbeopClass.NONE
                        : SitbeopClass.valueOf(rs.getString(1)), rs.getBoolean(2)), buildingId)
                .stream().findFirst();
    }

    public UUID confirmClassification(UUID buildingId, SitbeopClass cls, boolean bma, List<UUID> ruleIds,
                                      Map<String, Object> evidence, UUID by) {
        jdbc.update("UPDATE fac.legal_classification SET is_current = false WHERE building_id = ? AND is_current",
                buildingId);
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO fac.legal_classification(id, building_id, sitbeop_class, bma_target, rule_ids, evidence,
                                                     confirmed_by)
                VALUES (?, ?, ?, ?, ?::uuid[], ?::jsonb, ?)""",
                id, buildingId, cls.name(), bma, toPgArray(ruleIds), json(evidence), by);
        return id;
    }

    // ------------------------------------------------------------ 규칙 (RuleSource)
    @Override
    public Optional<CycleRule> findEffective(String lawCode, String facilityClass, Grade grade, InspectionType type,
                                             LocalDate asOf) {
        return jdbc.query("""
                SELECT * FROM fac.inspection_cycle_rule
                 WHERE law_code = ? AND facility_class = ? AND inspection_type = ?
                   AND grade IS NOT DISTINCT FROM CAST(? AS char(1))
                   AND effective_from <= ? AND (effective_to IS NULL OR effective_to > ?)
                 ORDER BY effective_from DESC LIMIT 1""", this::rule,
                lawCode, facilityClass, type.name(), grade == null ? null : grade.name(), Date.valueOf(asOf),
                Date.valueOf(asOf)).stream().findFirst();
    }

    private CycleRule rule(ResultSet rs, int i) throws SQLException {
        Array subs = rs.getArray("substitutes");
        List<InspectionType> substitutes = subs == null ? List.of()
                : Arrays.stream((Object[]) subs.getArray()).map(o -> InspectionType.valueOf(o.toString())).toList();
        Date to = rs.getDate("effective_to");
        Object fdm = rs.getObject("first_due_months");
        String g = rs.getString("grade");
        return new CycleRule(rs.getObject("id", UUID.class), rs.getString("rule_code"), rs.getString("law_code"),
                rs.getString("facility_class"), g == null ? null : Grade.valueOf(g.trim()),
                InspectionType.valueOf(rs.getString("inspection_type")), rs.getInt("cycle_months"),
                rs.getString("first_due_basis"), fdm == null ? null : ((Number) fdm).intValue(), substitutes,
                rs.getDate("effective_from").toLocalDate(), to == null ? null : to.toLocalDate(),
                rs.getString("legal_reference"));
    }

    // ------------------------------------------------------------ 도래
    private static final String ACTIVE = "status NOT IN ('DONE','LOST','CANCELLED')";

    public List<DueRow> activeDues(UUID buildingId) {
        return jdbc.query("SELECT * FROM fac.inspection_due WHERE building_id = ? AND " + ACTIVE
                + " ORDER BY due_date", this::due, buildingId);
    }

    public List<DueRow> allActiveDues() {
        return jdbc.query("SELECT * FROM fac.inspection_due WHERE " + ACTIVE
                + " AND status NOT IN ('CONTRACTED') ORDER BY due_date", this::due);
    }

    public void closeDue(UUID dueId, DueStatus status) {
        jdbc.update("UPDATE fac.inspection_due SET status = ?, updated_at = now(), version = version + 1 WHERE id = ?",
                status.name(), dueId);
    }

    public UUID insertDue(UUID buildingId, InspectionType type, LocalDate base, LocalDate due, UUID ruleId,
                          Map<String, Object> trace) {
        UUID id = UuidV7.next();
        jdbc.update("""
                INSERT INTO fac.inspection_due(id, building_id, inspection_type, base_date, due_date, rule_id, calc_trace)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)""",
                id, buildingId, type.name(), base == null ? null : Date.valueOf(base), Date.valueOf(due), ruleId,
                json(trace));
        return id;
    }

    public void advanceDue(UUID dueId, DueStatus status, int threshold) {
        jdbc.update("""
                UPDATE fac.inspection_due SET status = ?, last_notified_threshold = ?, updated_at = now(),
                       version = version + 1 WHERE id = ?""", status.name(), threshold, dueId);
    }

    /** BR-FAC-008: 멱등키 INSERT … ON CONFLICT DO NOTHING → 1행이면 최초 */
    public boolean insertNotificationIfAbsent(String key, UUID dueId, int threshold) {
        return jdbc.update("""
                INSERT INTO fac.due_notification(idempotency_key, due_id, threshold_days) VALUES (?, ?, ?)
                ON CONFLICT (idempotency_key) DO NOTHING""", key, dueId, threshold) == 1;
    }

    private DueRow due(ResultSet rs, int i) throws SQLException {
        Date base = rs.getDate("base_date");
        return new DueRow(rs.getObject("id", UUID.class), rs.getObject("building_id", UUID.class),
                rs.getString("inspection_type"), base == null ? null : base.toLocalDate(),
                rs.getDate("due_date").toLocalDate(), rs.getObject("rule_id", UUID.class), rs.getString("status"),
                rs.getString("calc_trace"));
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String toPgArray(List<UUID> ids) {
        List<String> s = new ArrayList<>();
        (ids == null ? List.<UUID>of() : ids).forEach(u -> s.add(u.toString()));
        return "{" + String.join(",", s) + "}";
    }
}
