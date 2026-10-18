package com.atomeng.erp.fcd.application;

import com.atomeng.common.audit.AuditLog;
import com.atomeng.common.events.OutboxPublisher;
import com.atomeng.erp.fcd.api.FcdEvents;
import com.atomeng.erp.hrq.api.HrqEvents.EmployeeTerminated;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * hrq.employee.terminated 처리 (설계 A8 "사용자 퇴사 · 카드 미반납", BR-FCD-014).
 * 퇴사일을 읽기 모델(fcd.employee_status)에 기록해 R-CARD-INACTIVE(퇴사일 이후 사용) 판정에 쓰고, 소지 카드에
 * '정지 검토' 표시만 한다. <b>카드 상태 · 결제는 자동으로 막지 않는다</b>(정지는 EXEC 승인 후 사람 실행).
 */
@Service
public class EmployeeLifecycleService {

    private final JdbcTemplate jdbc;
    private final OutboxPublisher outbox;
    private final AuditLog audit;

    public EmployeeLifecycleService(JdbcTemplate jdbc, OutboxPublisher outbox, AuditLog audit) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.audit = audit;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> onTerminated(EmployeeTerminated e) {
        jdbc.update("""
                INSERT INTO fcd.employee_status(employee_id, emp_no, resigned_on, source) VALUES (?, ?, ?, ?)
                ON CONFLICT (employee_id) DO UPDATE SET emp_no = EXCLUDED.emp_no, resigned_on = EXCLUDED.resigned_on,
                     source = EXCLUDED.source, received_at = now()""",
                e.employeeId(), e.empNo(), e.resignedOn() == null ? null : Date.valueOf(e.resignedOn()), e.source());
        String reason = "퇴사 확정(hrq.employee.terminated, " + e.resignedOn() + ") — 정지 요청 검토 필요(자동 차단 없음)";
        List<UUID> cards = jdbc.query("""
                UPDATE fcd.card SET deactivation_flag = true, deactivation_reason = ?, deactivation_flagged_at = now(),
                       updated_at = now(), version = version + 1
                 WHERE holder_employee_id = ? AND status <> 'CLOSED' AND NOT deactivation_flag RETURNING id""",
                (rs, i) -> rs.getObject(1, UUID.class), reason, e.employeeId());
        for (UUID c : cards) {
            outbox.publish(new FcdEvents.CardDeactivationFlagged(c, e.employeeId(), e.resignedOn(), reason));
            audit.append("FCD", AuditLog.Action.UPDATE, "card", c, Map.of("deactivation_flag", false),
                    Map.of("deactivation_flag", true, "reason", reason));
        }
        return cards;
    }
}
