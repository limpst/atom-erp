package com.atomeng.erp.fac.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.core.error.DomainException;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fac.adapter.out.persistence.BuildingRepository;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import com.atomeng.erp.fac.adapter.out.persistence.NodeJdbc;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.fac.domain.location.MemberCode;
import com.atomeng.erp.fac.domain.model.Building;
import com.atomeng.erp.fac.domain.model.FacTypes.BuildingStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 중복 시설물 병합 (02-IMPL Step 5, FR-FAC-003, TC-FAC-011). source(병합되는 쪽) → target(존속, survivor).
 * 한 트랜잭션에서 하위 이력 전부 이관, 실패 시 부분 이관 0건.
 * <ul>
 *   <li>잠금: 두 행을 id 오름차순 SELECT … FOR UPDATE (교착 방지)</li>
 *   <li>위치 노드: building_id·경로 루트 교체, 코드 충돌은 "-M1" 접미사</li>
 *   <li>등급 이력: append-only 라 이동 불가 → target 기준 복제 + correction_of = 원본</li>
 *   <li>도래: source 활성 due 취소 → target 재계산 · 도면·보수·필지 이동</li>
 *   <li>타 모듈 데이터(FLD 결함 등)는 직접 수정하지 않고 fac.building.merged 이벤트로 알린다</li>
 * </ul>
 */
@Service
public class MergeService {

    public static final String MERGE_INVALID = "FAC-409-003";

    public record MergeReport(UUID sourceId, UUID targetId, int nodes, int drawings, int grades, int duesCancelled,
                              int repairs, int parcels) {}

    private final JdbcTemplate jdbc;
    private final BuildingRepository buildings;
    private final NodeJdbc nodes;
    private final FacJdbc fac;
    private final CycleRecomputeService cycle;
    private final OutboxPublisher outbox;
    private final AuditLog audit;
    private final CurrentActor actor;

    public MergeService(JdbcTemplate jdbc, BuildingRepository buildings, NodeJdbc nodes, FacJdbc fac,
                        CycleRecomputeService cycle, OutboxPublisher outbox, AuditLog audit, CurrentActor actor) {
        this.jdbc = jdbc;
        this.buildings = buildings;
        this.nodes = nodes;
        this.fac = fac;
        this.cycle = cycle;
        this.outbox = outbox;
        this.audit = audit;
        this.actor = actor;
    }

    @Transactional
    public MergeReport merge(UUID sourceId, UUID targetId, String reason) {
        if (sourceId.equals(targetId)) {
            throw new DomainException(MERGE_INVALID, "같은 시설물로 병합할 수 없습니다");
        }
        if (reason == null || reason.isBlank()) {
            throw new DomainException(BuildingService.INVALID, "병합 사유(reason)가 필요합니다");
        }
        List<UUID> order = Stream.of(sourceId, targetId).sorted().toList();
        for (UUID id : order) {
            jdbc.queryForList("SELECT id FROM fac.building WHERE id = ? AND deleted_at IS NULL FOR UPDATE", id);
        }
        Building src = buildings.findById(sourceId)
                .orElseThrow(() -> new DomainException(BuildingService.NOT_FOUND, "source 시설물 없음: " + sourceId));
        Building tgt = buildings.findById(targetId)
                .orElseThrow(() -> new DomainException(BuildingService.NOT_FOUND, "target 시설물 없음: " + targetId));
        if (tgt.getStatus() == BuildingStatus.MERGED || tgt.getStatus() == BuildingStatus.DEMOLISHED) {
            throw new DomainException(MERGE_INVALID, "target 이 이미 " + tgt.getStatus() + " 입니다");
        }
        UUID by = actor.actorIdOrSystem();

        int nodeCount = nodes.reparentForMerge(sourceId, targetId, MemberCode.buildingLabel(targetId), by);
        int drawings = jdbc.update("UPDATE fac.drawing SET building_id = ?, updated_at = now(), updated_by = ?, "
                + "version = version + 1 WHERE building_id = ?", targetId, by, sourceId);
        int grades = jdbc.update("""
                INSERT INTO fac.grade_history(id, building_id, grade, inspection_type, performed_on, report_id,
                                              performer, source, correction_of, note, recorded_by)
                SELECT core.uuid_generate_v7(), ?, g.grade, g.inspection_type, g.performed_on, g.report_id,
                       g.performer, g.source, g.id, 'merged from ' || g.building_id, ?
                  FROM fac.grade_history g
                 WHERE g.building_id = ?
                   AND NOT EXISTS (SELECT 1 FROM fac.grade_history c WHERE c.correction_of = g.id)""",
                targetId, by, sourceId);
        int dues = jdbc.update("""
                UPDATE fac.inspection_due SET status = 'CANCELLED', updated_at = now(), version = version + 1
                 WHERE building_id = ? AND status NOT IN ('DONE','LOST','CANCELLED')""", sourceId);
        int repairs = jdbc.update("UPDATE fac.repair_history SET building_id = ?, updated_at = now(), updated_by = ?, "
                + "version = version + 1 WHERE building_id = ?", targetId, by, sourceId);
        int parcels = jdbc.update("""
                INSERT INTO fac.building_parcel(building_id, pnu, is_primary)
                SELECT ?, pnu, false FROM fac.building_parcel WHERE building_id = ? ON CONFLICT DO NOTHING""",
                targetId, sourceId);
        jdbc.update("UPDATE fac.legal_classification SET is_current = false WHERE building_id = ? AND is_current",
                sourceId);

        String prevStatus = src.getStatus().name();
        src.markMerged(targetId);
        buildings.saveAndFlush(src);
        if (fac.currentClassification(targetId).isPresent()) {
            cycle.recompute(targetId);
        }
        var report = new MergeReport(sourceId, targetId, nodeCount, drawings, grades, dues, repairs, parcels);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("targetId", targetId.toString());
        after.put("reason", reason);
        after.put("report", report);
        audit.append("FAC", AuditLog.Action.UPDATE, "BuildingMerge", sourceId,
                Map.of("status", prevStatus),
                after);
        outbox.publish(new FacEvents.BuildingMerged(sourceId, targetId, nodeCount, grades));
        return report;
    }
}
