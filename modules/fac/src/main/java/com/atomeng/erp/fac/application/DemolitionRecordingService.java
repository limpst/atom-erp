package com.atomeng.erp.fac.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fac.adapter.out.persistence.BuildingRepository;
import com.atomeng.erp.fac.api.DemolitionCompletedContract;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.fac.domain.model.Building;
import com.atomeng.erp.fac.domain.model.FacTypes.BuildingStatus;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * DEM 해체 완료 → 시설물 멸실 처리 (설계 10 v0.3 B6 구독자 FAC: "건축물 멸실 · 점검주기 중지").
 * 호출자(이벤트 리스너)의 트랜잭션 — processed_event INSERT 와 같은 트랜잭션에서 실행된다(멱등).
 * <ul>
 *   <li>building.status → DEMOLISHED(이미 DEMOLISHED 면 변화 없음), 진행 중 점검 도래 → CANCELLED</li>
 *   <li>병합된(MERGED) 시설물이면 병합 대상(정본)으로 따라가 처리</li>
 *   <li>{@code fac.building.demolished} 발행 + 감사 로그</li>
 * </ul>
 */
@Service
public class DemolitionRecordingService {

    private static final Logger log = LoggerFactory.getLogger(DemolitionRecordingService.class);

    private final BuildingRepository buildings;
    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;

    public DemolitionRecordingService(BuildingRepository buildings, JdbcTemplate jdbc, OutboxPublisher outbox,
                                      AuditLog audit) {
        this.buildings = buildings;
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
    }

    /** @return 처리 결과(DEMOLISHED · ALREADY · NOT_FOUND) */
    @Transactional(propagation = Propagation.MANDATORY)
    public String record(DemolitionCompletedContract e) {
        if (e.buildingId() == null) {
            log.warn("dem.demolition.completed 에 buildingId 없음 — 건너뜀 project={}", e.projectId());
            return "NOT_FOUND";
        }
        Building b = buildings.findById(e.buildingId()).orElse(null);
        for (int hop = 0; b != null && b.getStatus() == BuildingStatus.MERGED && hop < 5; hop++) {
            var target = jdbc.queryForList("SELECT merged_into_id FROM fac.building WHERE id = ?", java.util.UUID.class,
                    b.getId());
            b = target.isEmpty() || target.get(0) == null ? null : buildings.findById(target.get(0)).orElse(null);
        }
        if (b == null) {
            log.warn("해체 완료 이벤트의 시설물 없음 — 건너뜀 building={} project={}", e.buildingId(), e.projectId());
            return "NOT_FOUND";
        }
        if (b.getStatus() == BuildingStatus.DEMOLISHED) {
            return "ALREADY";
        }
        String previous = b.getStatus().name();
        b.recordDemolition();
        buildings.saveAndFlush(b);
        int cancelled = jdbc.update("""
                UPDATE fac.inspection_due SET status = 'CANCELLED', updated_at = now(), version = version + 1
                 WHERE building_id = ? AND status NOT IN ('DONE','LOST','CANCELLED')""", b.getId());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("status", "DEMOLISHED");
        after.put("demolishedOn", e.completedOn());
        after.put("projectId", e.projectId());
        after.put("workPackageId", e.workPackageId());
        after.put("cancelledDues", cancelled);
        audit.append("FAC", AuditLog.Action.UPDATE, "building", b.getId(), Map.of("status", previous), after);
        outbox.publish(new FacEvents.BuildingDemolished(b.getId(), previous, e.completedOn(), e.projectId(), cancelled));
        return "DEMOLISHED";
    }
}
