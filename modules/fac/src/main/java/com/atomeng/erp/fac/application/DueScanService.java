package com.atomeng.erp.fac.application;

import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fac.adapter.out.persistence.FacJdbc;
import com.atomeng.erp.fac.api.FacEvents;
import com.atomeng.erp.fac.domain.model.FacTypes.DueStatus;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도래 스캔 (02-IMPL Step 11, FR-FAC-012, BR-FAC-008, TC-FAC-006).
 * 남은 일수 ≤ threshold 인 가장 작은 threshold 하나만 발행 — 스캔이 하루 빠져도 다음 날 보충 발행.
 * 멱등키 {buildingId}:{type}:{dueDate}:{threshold} INSERT 성공 시에만 이벤트 → 같은 날 2회 실행해도 1건.
 */
@Service
public class DueScanService {

    public record ScanResult(int approaching, int overdue) {}

    private final FacJdbc jdbc;
    private final OutboxPublisher outbox;
    private final Clock clock;
    private final List<Integer> thresholds;

    public DueScanService(FacJdbc jdbc, OutboxPublisher outbox, Clock clock,
                          @Value("${atom.fac.due-scan.thresholds:180,90,30}") List<Integer> thresholds) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
        this.thresholds = thresholds.stream().sorted().toList(); // 30, 90, 180
    }

    @Transactional
    public ScanResult scan() {
        LocalDate today = LocalDate.now(clock.withZone(CycleRecomputeService.SEOUL));
        int approaching = 0;
        int overdue = 0;
        for (FacJdbc.DueRow d : jdbc.allActiveDues()) {
            long daysLeft = ChronoUnit.DAYS.between(today, d.dueDate());
            DueStatus current = DueStatus.valueOf(d.status());
            if (daysLeft < 0) {
                String key = key(d, 0);
                if (jdbc.insertNotificationIfAbsent(key, d.id(), 0)) {
                    jdbc.advanceDue(d.id(), DueStatus.OVERDUE, 0);
                    outbox.publish(new FacEvents.InspectionOverdue(d.buildingId(), d.inspectionType(), d.dueDate()));
                    overdue++;
                }
                continue;
            }
            Integer threshold = thresholds.stream().filter(t -> daysLeft <= t).findFirst().orElse(null);
            if (threshold == null) {
                continue;
            }
            DueStatus next = DueStatus.approaching(threshold);
            if (current.rank() >= next.rank()) {
                continue; // 퇴행 방지
            }
            String key = key(d, threshold);
            if (jdbc.insertNotificationIfAbsent(key, d.id(), threshold)) {
                jdbc.advanceDue(d.id(), next, threshold);
                outbox.publish(new FacEvents.InspectionDueApproaching(d.buildingId(), d.inspectionType(),
                        d.dueDate(), threshold, key));
                approaching++;
            }
        }
        return new ScanResult(approaching, overdue);
    }

    static String key(FacJdbc.DueRow d, int threshold) {
        return "%s:%s:%s:%d".formatted(d.buildingId(), d.inspectionType(), d.dueDate(), threshold);
    }
}
