package com.atomeng.erp.eqp.application;

import com.atomeng.erp.eqp.adapter.out.persistence.EqpJdbc;
import com.atomeng.erp.eqp.api.CalibrationValidityPort.ValidityResult;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 대체 장비 추천 (설계 B7.2, 04-IMPL Step 7). 후보 = 같은 유형 · 사용 가능 상태 · 요청 기간에 활성 예약 없음 ·
 * 기간 시작과 종료일 모두 유효 판정 통과. 점수 = 0.5 × min(종료일 이후 교정 잔여일/365, 1)
 * + 0.3 × (보관 위치 = 기준 위치) + 0.2 × (1 − min(최근 90일 고장 수/3, 1)). 상위 3개.
 */
@Component
public class AlternativeFinder {

    private final EqpJdbc eqp;
    private final CalibrationValidityService validity;

    public AlternativeFinder(EqpJdbc eqp, CalibrationValidityService validity) {
        this.eqp = eqp;
        this.validity = validity;
    }

    public List<Map<String, Object>> find(UUID typeId, LocalDate from, LocalDate to, UUID excludeId, UUID projectId,
                                          String base) {
        var rows = eqp.jdbc().queryForList("""
                SELECT e.id, e.code, e.home_location,
                       (SELECT count(*) FROM eqp.fault f WHERE f.equipment_id = e.id
                           AND f.reported_at > (?::date - 90)) AS faults90
                  FROM eqp.equipment e
                 WHERE e.type_id = ? AND e.status IN ('AVAILABLE','RESERVED','REGISTERED')
                   AND (?::uuid IS NULL OR e.id <> ?::uuid)
                   AND NOT EXISTS (SELECT 1 FROM eqp.reservation r WHERE r.equipment_id = e.id
                                     AND r.status IN ('REQUESTED','CONFIRMED','IN_USE','OVERDUE')
                                     AND r.period && daterange(?::date, ?::date + 1))
                   AND NOT EXISTS (SELECT 1 FROM eqp.checkout c WHERE c.equipment_id = e.id
                                     AND c.in_at IS NULL AND NOT c.sync_conflict)
                 ORDER BY e.code LIMIT 200""", EqpJdbc.d(to), typeId, excludeId, excludeId, EqpJdbc.d(from),
                EqpJdbc.d(to));
        List<Map<String, Object>> out = new ArrayList<>();
        for (var r : rows) {
            UUID id = (UUID) r.get("id");
            ValidityResult vFrom = validity.validate(id, from, projectId);
            ValidityResult vTo = validity.validate(id, to, projectId);
            if (!vFrom.valid() || !vTo.valid()) {
                continue;
            }
            double calScore = vTo.validUntil() == null ? 1.0
                    : Math.min(ChronoUnit.DAYS.between(to, vTo.validUntil()) / 365.0, 1.0);
            double loc = base != null && base.equals(r.get("home_location")) ? 1.0 : 0.0;
            double faults = ((Number) r.get("faults90")).doubleValue();
            double score = 0.5 * Math.max(calScore, 0) + 0.3 * loc + 0.2 * (1 - Math.min(faults / 3.0, 1.0));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("equipmentId", id);
            m.put("code", r.get("code"));
            m.put("validUntil", vTo.validUntil());
            m.put("score", Math.round(score * 1000) / 1000.0);
            out.add(m);
        }
        out.sort(Comparator.comparingDouble((Map<String, Object> m) -> (Double) m.get("score")).reversed()
                .thenComparing(m -> (String) m.get("code")));
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : out;
    }
}
