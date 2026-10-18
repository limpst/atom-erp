package com.atomeng.erp.fac.application;

import com.atomeng.common.core.actor.CurrentActor;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fac.adapter.out.persistence.BuildingRepository;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.fac.api.ReportPublishedContract;
import com.atomeng.erp.fac.domain.model.FacTypes.Grade;
import com.atomeng.erp.fac.domain.model.FacTypes.InspectionType;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 보고서 발행 → 안전등급 append (02-IMPL Step 9, BR-FAC-004·005·011, TC-FAC-003·005).
 * 호출자(이벤트 리스너)의 트랜잭션 — processed_event INSERT 와 같은 트랜잭션에서 실행된다.
 */
@Service
public class GradeRecordingService {

    private static final Logger log = LoggerFactory.getLogger(GradeRecordingService.class);

    private final FacJdbc jdbc;
    private final BuildingRepository buildings;
    private final CycleRecomputeService cycle;
    private final OutboxPublisher outbox;

    public GradeRecordingService(FacJdbc jdbc, BuildingRepository buildings, CycleRecomputeService cycle,
                                 OutboxPublisher outbox) {
        this.jdbc = jdbc;
        this.buildings = buildings;
        this.cycle = cycle;
        this.outbox = outbox;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public int record(ReportPublishedContract e) {
        Grade grade = Grade.valueOf(e.grade());
        InspectionType type = InspectionType.valueOf(e.inspectionType());
        UUID by = e.approvedBy() != null ? e.approvedBy() : CurrentActor.SYSTEM;
        int recorded = 0;
        for (UUID bid : e.buildingIds()) {
            var building = buildings.findById(bid);
            if (building.isEmpty()) {
                log.warn("rpt 이벤트의 시설물 없음 — 건너뜀 building={} report={}", bid, e.reportId());
                continue;
            }
            String prev = jdbc.latestGrade(bid).map(Enum::name).orElse(null);
            jdbc.insertGrade(bid, grade, type, e.performedOn(), e.reportId(), "REPORT", by);
            cycle.recompute(bid);
            if (grade.isCritical()) {
                building.get().flagUseRestriction();
                buildings.saveAndFlush(building.get());
                outbox.publish(new FacEvents.GradeCriticalDetected(bid, grade.name()));
            }
            outbox.publish(new FacEvents.GradeRecorded(bid, grade.name(), prev, type.name(), e.performedOn(),
                    e.reportId()));
            recorded++;
        }
        return recorded;
    }
}
