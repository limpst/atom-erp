package com.atomeng.erp.fac.application;

import com.atomeng.erp.fac.adapter.out.persistence.BuildingRepository;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.BuildingSnapshot;
import com.atomeng.erp.fac.domain.cycle.InspectionCycleEngine.DueCalculation;
import com.atomeng.erp.fac.domain.model.FacTypes.DueStatus;
import com.atomeng.erp.fac.domain.model.FacTypes.InspectionType;
import com.atomeng.erp.fac.domain.model.FacTypes.SitbeopClass;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시설물 1건의 차기 도래일 재계산 (02-IMPL Step 10 CycleRecomputeService).
 * 결과가 같으면 기존 due 유지(알림 상태 보존), 다르면 기존 due 종료(수행됨=DONE, 그 외 CANCELLED) 후 새 due.
 */
@Service
public class CycleRecomputeService {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final FacJdbc jdbc;
    private final BuildingRepository buildings;
    private final InspectionCycleEngine engine;
    private final Clock clock;

    public CycleRecomputeService(FacJdbc jdbc, BuildingRepository buildings, Clock clock) {
        this.jdbc = jdbc;
        this.buildings = buildings;
        this.engine = new InspectionCycleEngine(jdbc);
        this.clock = clock;
    }

    public BuildingSnapshot snapshot(UUID buildingId) {
        var b = buildings.findById(buildingId).orElseThrow();
        var cls = jdbc.currentClassification(buildingId);
        return new BuildingSnapshot(buildingId,
                cls.map(FacJdbc.Classification::sitbeopClass).orElse(SitbeopClass.NONE),
                cls.map(FacJdbc.Classification::bmaTarget).orElse(false),
                b.getApprovalDate(), jdbc.latestGrade(buildingId).orElse(null), jdbc.lastPerformed(buildingId));
    }

    @Transactional
    public List<DueCalculation> recompute(UUID buildingId) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        List<DueCalculation> calcs = engine.compute(snapshot(buildingId), today);
        Map<String, FacJdbc.DueRow> active = new HashMap<>();
        jdbc.activeDues(buildingId).forEach(d -> active.put(d.inspectionType(), d));
        for (DueCalculation c : calcs) {
            FacJdbc.DueRow existing = active.remove(c.type().name());
            if (existing != null) {
                if (existing.dueDate().equals(c.dueDate()) && existing.ruleId().equals(c.rule().id())) {
                    continue; // 변화 없음
                }
                boolean performed = c.baseDate() != null
                        && (existing.baseDate() == null || c.baseDate().isAfter(existing.baseDate()));
                jdbc.closeDue(existing.id(), performed ? DueStatus.DONE : DueStatus.CANCELLED);
            }
            jdbc.insertDue(buildingId, c.type(), c.baseDate(), c.dueDate(), c.rule().id(), c.trace());
        }
        // 더 이상 대상이 아닌 유형(분류 변경 등)은 취소
        active.values().forEach(d -> jdbc.closeDue(d.id(), DueStatus.CANCELLED));
        return calcs;
    }

    static boolean isPeriodic(String type) {
        return InspectionType.periodic().contains(InspectionType.valueOf(type));
    }
}
