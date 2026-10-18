package com.atomeng.erp.eqp.application;

import com.atomeng.common.core.UuidV7;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc.EquipmentRow;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 예약 (FR-EQP-006, BR-EQP-002, 04-IMPL Step 7).
 * 이중 예약 방지는 애플리케이션 락이 아니라 DB 배타 제약 {@code no_double_booking}(btree_gist EXCLUDE, NFR-EQP-05) —
 * 동시에 같은 기간을 요청하면 뒤 트랜잭션이 앞 커밋을 기다렸다가 23P01 로 실패 → 409 EQP-409-002 + 대체 장비(TC-EQP-002).
 * 실패한 트랜잭션은 PostgreSQL 에서 더 쿼리할 수 없으므로, 삽입은 짧은 트랜잭션으로 하고 대체 추천은 롤백 뒤 따로 계산한다.
 * 기간 [from, to] 은 daterange [from, to+1) 로 저장한다.
 */
@Service
public class ReservationService {

    private static final Set<String> ACTIVE = Set.of("REQUESTED", "CONFIRMED", "IN_USE", "OVERDUE");

    private final EqpJdbc eqp;
    private final CalibrationValidityService validity;
    private final AlternativeFinder alternatives;
    private final CurrentActor actor;
    private final TransactionTemplate tx;

    public ReservationService(EqpJdbc eqp, CalibrationValidityService validity, AlternativeFinder alternatives,
                              CurrentActor actor, TransactionTemplate tx) {
        this.eqp = eqp;
        this.validity = validity;
        this.alternatives = alternatives;
        this.actor = actor;
        this.tx = tx;
    }

    public Map<String, Object> request(UUID equipmentId, UUID projectId, LocalDate from, LocalDate to, String base) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new DomainException(EqpErrors.INVALID, "예약 기간 오류: from ≤ to");
        }
        UUID by = actor.actorIdOrSystem();
        EquipmentRow e = eqp.get(equipmentId);
        // 단일 판정기: 시작일 무효(만료 · 고장 · 키트) → 사유별 409, 종료일 무효(기간 중 만료) → BR-EQP-002 거부
        ValidityResult vFrom = validity.validate(equipmentId, from, projectId);
        if (!vFrom.valid()) {
            throw CalibrationValidityService.blocked(equipmentId, vFrom,
                    alternatives.find(e.typeId(), from, to, equipmentId, projectId, base));
        }
        ValidityResult vTo = validity.validate(equipmentId, to, projectId);
        if (!vTo.valid()) {
            throw new DomainException(EqpErrors.CAL_EXPIRES_IN_PERIOD,
                    "예약 기간 중 검교정 만료(" + vFrom.validUntil() + ")")
                    .with("reason", vTo.reason()).with("equipmentId", equipmentId)
                    .with("alternatives", alternatives.find(e.typeId(), from, to, equipmentId, projectId, base));
        }
        UUID id = UuidV7.next();
        try {
            tx.executeWithoutResult(s -> eqp.jdbc().update("""
                    INSERT INTO eqp.reservation(id, equipment_id, project_id, period, status, requested_by)
                    VALUES (?, ?, ?, daterange(?::date, ?::date + 1, '[)'), 'REQUESTED', ?)""",
                    id, equipmentId, projectId, EqpJdbc.d(from), EqpJdbc.d(to), by));
        } catch (DataAccessException ex) {
            if ("23P01".equals(sqlState(ex))) {
                throw new DomainException(EqpErrors.DOUBLE_BOOKING, "같은 장비가 겹치는 기간에 이미 예약되어 있습니다")
                        .with("equipmentId", equipmentId)
                        .with("alternatives", alternatives.find(e.typeId(), from, to, equipmentId, projectId, base));
            }
            throw ex;
        }
        return view(id);
    }

    /** REQUESTED → CONFIRMED (PM · ADMIN) */
    public Map<String, Object> approve(UUID id) {
        return move(id, Set.of("REQUESTED"), "CONFIRMED", true);
    }

    /** REQUESTED · CONFIRMED → CANCELLED — EXCLUDE 조건에서 빠져 슬롯이 자동으로 풀린다 */
    public Map<String, Object> cancel(UUID id) {
        return move(id, Set.of("REQUESTED", "CONFIRMED"), "CANCELLED", false);
    }

    private Map<String, Object> move(UUID id, Set<String> from, String to, boolean approver) {
        UUID by = actor.actorIdOrSystem();
        tx.executeWithoutResult(s -> {
            var cur = eqp.jdbc().queryForList("SELECT status FROM eqp.reservation WHERE id = ? FOR UPDATE", String.class, id);
            if (cur.isEmpty()) {
                throw new DomainException(EqpErrors.NOT_FOUND, "예약 없음: " + id);
            }
            if (!from.contains(cur.get(0))) {
                throw new DomainException(EqpErrors.STATE, "예약 상태 전이 불가: %s → %s".formatted(cur.get(0), to));
            }
            eqp.jdbc().update("""
                    UPDATE eqp.reservation SET status = ?, approved_by = CASE WHEN ? THEN ? ELSE approved_by END,
                           version = version + 1, updated_at = now() WHERE id = ?""", to, approver, by, id);
        });
        return view(id);
    }

    public Map<String, Object> view(UUID id) {
        var rows = eqp.jdbc().queryForList("""
                SELECT id, equipment_id, project_id, lower(period) AS from_date, upper(period) - 1 AS to_date, status,
                       conditional, requested_by, approved_by, version FROM eqp.reservation WHERE id = ?""", id);
        if (rows.isEmpty()) {
            throw new DomainException(EqpErrors.NOT_FOUND, "예약 없음: " + id);
        }
        return new LinkedHashMap<>(rows.get(0));
    }

    public List<Map<String, Object>> list(UUID equipmentId, UUID projectId) {
        return eqp.jdbc().queryForList("""
                SELECT id, equipment_id, project_id, lower(period) AS from_date, upper(period) - 1 AS to_date, status,
                       conditional, version FROM eqp.reservation
                 WHERE (?::uuid IS NULL OR equipment_id = ?::uuid) AND (?::uuid IS NULL OR project_id = ?::uuid)
                 ORDER BY lower(period), created_at""", equipmentId, equipmentId, projectId, projectId);
    }

    /** GET /reservations/availability — 유형 · 기간 기준 가용 장비 상위 3 (B7.2) */
    public List<Map<String, Object>> availability(String typeCode, LocalDate from, LocalDate to, UUID projectId,
                                                  String base) {
        var ids = eqp.jdbc().queryForList("SELECT id FROM eqp.equipment_type WHERE code = ?", UUID.class, typeCode);
        if (ids.isEmpty()) {
            throw new DomainException(EqpErrors.INVALID, "알 수 없는 장비 유형: " + typeCode);
        }
        if (from == null || to == null || to.isBefore(from)) {
            throw new DomainException(EqpErrors.INVALID, "기간 오류: from ≤ to");
        }
        return alternatives.find(ids.get(0), from, to, null, projectId, base);
    }

    static boolean isActive(String status) {
        return ACTIVE.contains(status);
    }

    private static String sqlState(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException se && se.getSQLState() != null) {
                return se.getSQLState();
            }
        }
        return null;
    }
}
