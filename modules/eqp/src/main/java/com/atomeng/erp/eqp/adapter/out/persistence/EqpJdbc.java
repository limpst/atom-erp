package com.atomeng.erp.eqp.adapter.out.persistence;

import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.eqp.application.EqpErrors;
import com.atomeng.erp.eqp.domain.EquipmentStatus;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * eqp 스키마 공통 JDBC 저장소 — 장비 행 조회 · 잠금 · 상태 전이(전이표 + 이력 + version 증가).
 * 이력·판정 테이블은 append-only 라 JPA dirty checking 대신 JDBC (HRQ · FCD 와 같은 방식).
 */
@Repository
public class EqpJdbc {

    public record EquipmentRow(UUID id, String code, UUID typeId, String typeCode, String typeName,
                               boolean calibrationRequired, UUID parentId, String model, String manufacturer,
                               String serialNo, String ownership, EquipmentStatus status, String spec, String nfcUid,
                               String homeLocation, LocalDate acquiredOn, BigDecimal acquisitionCost,
                               Integer usefulLifeMonths, BigDecimal salvageValue, String depreciationMethod,
                               BigDecimal dailyRate, LocalDate disposedOn, BigDecimal disposalAmount, long version) {}

    private static final String SELECT = """
            SELECT e.id, e.code, e.type_id, t.code AS type_code, t.name AS type_name, t.calibration_required, e.parent_id,
                   e.model, e.manufacturer, e.serial_no, e.ownership, e.status, e.spec::text AS spec, e.nfc_uid,
                   e.home_location, e.acquired_on, e.acquisition_cost, e.useful_life_months, e.salvage_value,
                   e.depreciation_method, e.daily_rate, e.disposed_on, e.disposal_amount, e.version
              FROM eqp.equipment e JOIN eqp.equipment_type t ON t.id = e.type_id""";

    private static final RowMapper<EquipmentRow> ROW = (rs, i) -> new EquipmentRow(
            rs.getObject("id", UUID.class), rs.getString("code"), rs.getObject("type_id", UUID.class),
            rs.getString("type_code"), rs.getString("type_name"), rs.getBoolean("calibration_required"),
            rs.getObject("parent_id", UUID.class), rs.getString("model"), rs.getString("manufacturer"),
            rs.getString("serial_no"), rs.getString("ownership"), EquipmentStatus.valueOf(rs.getString("status")),
            rs.getString("spec"), rs.getString("nfc_uid"), rs.getString("home_location"), ld(rs, "acquired_on"),
            rs.getBigDecimal("acquisition_cost"), (Integer) rs.getObject("useful_life_months"),
            rs.getBigDecimal("salvage_value"), rs.getString("depreciation_method"), rs.getBigDecimal("daily_rate"),
            ld(rs, "disposed_on"), rs.getBigDecimal("disposal_amount"), rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public EqpJdbc(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JdbcTemplate jdbc() {
        return jdbc;
    }

    public static LocalDate ld(ResultSet rs, String col) throws SQLException {
        Date d = rs.getDate(col);
        return d == null ? null : d.toLocalDate();
    }

    public static Date d(LocalDate x) {
        return x == null ? null : Date.valueOf(x);
    }

    public Optional<EquipmentRow> find(UUID id) {
        return jdbc.query(SELECT + " WHERE e.id = ?", ROW, id).stream().findFirst();
    }

    public EquipmentRow get(UUID id) {
        return find(id).orElseThrow(() -> new DomainException(EqpErrors.NOT_FOUND, "장비 없음: " + id));
    }

    public Optional<EquipmentRow> findByCode(String code) {
        return jdbc.query(SELECT + " WHERE e.code = ?", ROW, code).stream().findFirst();
    }

    /** 장비 행 잠금(SELECT … FOR UPDATE OF e) — 같은 장비의 반출 · 반납 · 교정 · 스캔을 직렬화 */
    public EquipmentRow lock(UUID id) {
        return jdbc.query(SELECT + " WHERE e.id = ? FOR UPDATE OF e", ROW, id).stream().findFirst()
                .orElseThrow(() -> new DomainException(EqpErrors.NOT_FOUND, "장비 없음: " + id));
    }

    public List<EquipmentRow> list(String status, String typeCode, Integer calDueWithinDays, LocalDate today,
                                   int limit, int offset) {
        return jdbc.query(SELECT + listWhere() + " ORDER BY e.code LIMIT ? OFFSET ?", ROW,
                status, status, typeCode, typeCode, calDueWithinDays, d(today), d(today), calDueWithinDays, limit, offset);
    }

    public long count(String status, String typeCode, Integer calDueWithinDays, LocalDate today) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM eqp.equipment e JOIN eqp.equipment_type t ON t.id = e.type_id"
                + listWhere(), Long.class, status, status, typeCode, typeCode, calDueWithinDays, d(today), d(today),
                calDueWithinDays);
        return n == null ? 0 : n;
    }

    /** calDueWithinDays: 현재 유효 교정(최신 비FAIL)의 next_due_on 이 today + n 이내 */
    private static String listWhere() {
        return " " + """
                 WHERE (?::text IS NULL OR e.status = ?::text)
                   AND (?::text IS NULL OR t.code = ?::text)
                   AND (?::int IS NULL OR (t.calibration_required AND (
                        SELECT max(c.next_due_on) FROM eqp.calibration c
                         WHERE c.equipment_id = e.id AND c.result <> 'FAIL' AND c.calibrated_on <= ?::date)
                        <= ?::date + ?::int))""";
    }

    public byte[] qrSecret(UUID id) {
        return jdbc.queryForObject("SELECT qr_secret FROM eqp.equipment WHERE id = ?", byte[].class, id);
    }

    public List<EquipmentRow> children(UUID parentId) {
        return jdbc.query(SELECT + " WHERE e.parent_id = ? AND e.status <> 'DISPOSED' ORDER BY e.code", ROW, parentId);
    }

    /**
     * 전이표(설계 A6.1)를 지키는 상태 변경 + 이력 + version 증가. 허용되지 않으면 409 EQP-409-006.
     * @return 새 version
     */
    public long transition(EquipmentRow eq, EquipmentStatus to, String reason, UUID refId, UUID actor) {
        if (!eq.status().canTransitionTo(to)) {
            throw new DomainException(EqpErrors.STATE, "상태 전이 불가: %s %s → %s".formatted(eq.code(), eq.status(), to));
        }
        int n = jdbc.update("""
                UPDATE eqp.equipment SET status = ?, version = version + 1, updated_at = now(), updated_by = ?
                 WHERE id = ? AND status = ?""", to.name(), actor, eq.id(), eq.status().name());
        if (n != 1) {
            throw new DomainException(EqpErrors.STATE, "장비 상태가 바뀌었습니다: " + eq.code());
        }
        jdbc.update("""
                INSERT INTO eqp.equipment_status_history(equipment_id, from_status, to_status, reason_code, ref_id, changed_by)
                VALUES (?, ?, ?, ?, ?, ?)""", eq.id(), eq.status().name(), to.name(), reason, refId, actor);
        return eq.version() + 1;
    }

    /** 경로 전이(예: CAL_DUE → IN_CALIBRATION → AVAILABLE). 각 단계 이력 기록 */
    public EquipmentRow transitionPath(EquipmentRow eq, List<EquipmentStatus> path, String reason, UUID refId,
                                       UUID actor) {
        EquipmentRow cur = eq;
        for (EquipmentStatus s : path) {
            transition(cur, s, reason, refId, actor);
            cur = get(eq.id());
        }
        return cur;
    }

    public void history(UUID equipmentId, String from, String to, String reason, UUID refId, UUID actor) {
        jdbc.update("""
                INSERT INTO eqp.equipment_status_history(equipment_id, from_status, to_status, reason_code, ref_id, changed_by)
                VALUES (?, ?, ?, ?, ?, ?)""", equipmentId, from, to, reason, refId, actor);
    }
}
