package com.atomeng.erp.fac.application;

import com.atomeng.erp.fac.api.FacQueryApi;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link FacQueryApi} 구현 — 시설물 · 최신 등급 · 진행 중 점검 도래 읽기 전용 조회 */
@Service
class FacQueryService implements FacQueryApi {

    private static final String SUMMARY = """
            SELECT b.id, s.owner_client_id, b.name, b.dong_name, b.road_address, b.structure_nm, b.gross_floor_area,
                   b.floors_above, b.floors_below, b.approval_date, b.status, b.merged_into_id,
                   g.grade, g.performed_on
              FROM fac.building b
              JOIN fac.site s ON s.id = b.site_id
              LEFT JOIN LATERAL (SELECT gh.grade, gh.performed_on FROM fac.grade_history gh
                                  WHERE gh.building_id = b.id
                                  ORDER BY gh.performed_on DESC, gh.recorded_at DESC LIMIT 1) g ON true
            """;

    private final JdbcTemplate jdbc;

    FacQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public List<BuildingSummary> buildingsOwnedBy(UUID clientId) {
        return jdbc.query(SUMMARY + """
                 WHERE s.owner_client_id = ? AND b.deleted_at IS NULL AND s.deleted_at IS NULL AND b.status <> 'MERGED'
                 ORDER BY b.name, b.dong_name, b.id""", FacQueryService::summary, clientId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BuildingSummary> building(UUID buildingId) {
        return jdbc.query(SUMMARY + " WHERE b.id = ? AND b.deleted_at IS NULL", FacQueryService::summary, buildingId)
                .stream().findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public List<GradeEntry> gradeHistory(UUID buildingId) {
        return jdbc.query("""
                SELECT grade, inspection_type, performed_on, report_id, correction_of IS NOT NULL AS correction
                  FROM fac.grade_history WHERE building_id = ? ORDER BY performed_on DESC, recorded_at DESC""",
                (rs, i) -> new GradeEntry(rs.getString("grade"), rs.getString("inspection_type"),
                        date(rs, "performed_on"), rs.getObject("report_id", UUID.class), rs.getBoolean("correction")),
                buildingId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<InspectionDue> openDues(UUID buildingId) {
        return jdbc.query("""
                SELECT building_id, inspection_type, due_date, status FROM fac.inspection_due
                 WHERE building_id = ? AND status NOT IN ('DONE','LOST','CANCELLED')
                 ORDER BY due_date, inspection_type""",
                (rs, i) -> new InspectionDue(rs.getObject("building_id", UUID.class), rs.getString("inspection_type"),
                        date(rs, "due_date"), rs.getString("status")), buildingId);
    }

    private static BuildingSummary summary(ResultSet rs, int i) throws SQLException {
        return new BuildingSummary(rs.getObject("id", UUID.class), rs.getObject("owner_client_id", UUID.class),
                rs.getString("name"), rs.getString("dong_name"), rs.getString("road_address"), rs.getString("structure_nm"),
                rs.getObject("gross_floor_area", BigDecimal.class), (Integer) intOrNull(rs, "floors_above"),
                (Integer) intOrNull(rs, "floors_below"), date(rs, "approval_date"), rs.getString("status"),
                rs.getObject("merged_into_id", UUID.class), rs.getString("grade"), date(rs, "performed_on"));
    }

    private static Object intOrNull(ResultSet rs, String col) throws SQLException {
        int v = rs.getInt(col);
        return rs.wasNull() ? null : v;
    }

    private static LocalDate date(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }
}
